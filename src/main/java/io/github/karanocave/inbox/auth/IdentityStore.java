package io.github.karanocave.inbox.auth;

import io.github.karanocave.inbox.engine.Account;
import io.github.karanocave.inbox.engine.EngineException;
import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@Repository
class IdentityStore {
    private static final String KEY_PREFIX = "inbox_";
    private static final Pattern KEY_PATTERN = Pattern.compile(KEY_PREFIX + "[A-Za-z0-9_-]{43}");
    private final JdbcTemplate jdbc;
    private final AuthSettings settings;

    IdentityStore(JdbcTemplate jdbc, AuthSettings settings) {
        this.jdbc = jdbc;
        this.settings = settings;
    }

    @PostConstruct
    void initialize() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS inbox_auth_accounts (
                    id TEXT PRIMARY KEY,
                    username TEXT NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS inbox_auth_api_keys (
                    id TEXT PRIMARY KEY,
                    account_id TEXT NOT NULL REFERENCES inbox_auth_accounts(id) ON DELETE CASCADE,
                    name TEXT NOT NULL,
                    digest TEXT NOT NULL UNIQUE,
                    created_at TEXT NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS inbox_auth_api_keys_account_idx
                ON inbox_auth_api_keys(account_id)
                """);
        if (settings.demo) {
            saveAccount(AuthSettings.DEMO_ACCOUNT);
        }
    }

    void saveAccount(Account account) {
        jdbc.update("""
                INSERT INTO inbox_auth_accounts (id, username) VALUES (?, ?)
                ON CONFLICT (id) DO UPDATE SET username = excluded.username
                """, account.id(), account.username());
    }

    Account findAccount(String id) {
        return jdbc.query("""
                SELECT id, username FROM inbox_auth_accounts WHERE id = ?
                """, (row, index) -> new Account(row.getString("id"), row.getString("username")), id)
                .stream().findFirst().orElse(null);
    }

    Account authenticateKey(String key) {
        if (!KEY_PATTERN.matcher(key).matches()) {
            return null;
        }
        return jdbc.query("""
                SELECT a.id, a.username
                FROM inbox_auth_api_keys k JOIN inbox_auth_accounts a ON a.id = k.account_id
                WHERE k.digest = ?
                """, (row, index) -> new Account(row.getString("id"), row.getString("username")),
                SecretValues.digest(key)).stream().findFirst().orElse(null);
    }

    List<KeyInfo> listKeys(Account account) {
        return jdbc.query("""
                SELECT id, name, created_at FROM inbox_auth_api_keys WHERE account_id = ?
                ORDER BY created_at DESC, id
                """, (row, index) -> new KeyInfo(row.getString("id"), row.getString("name"),
                row.getString("created_at")), account.id());
    }

    NewKey createKey(Account account, String name) {
        if (name == null || name.isBlank() || name.strip().length() > 100
                || name.codePoints().anyMatch(Character::isISOControl)) {
            throw new EngineException(400, "invalid_key_name", "API key name must contain 1–100 characters", Map.of());
        }
        String token = KEY_PREFIX + SecretValues.generate();
        NewKey key = new NewKey(UUID.randomUUID().toString(), name.strip(), Instant.now().toString(), token);
        jdbc.update("""
                INSERT INTO inbox_auth_api_keys (id, account_id, name, digest, created_at)
                VALUES (?, ?, ?, ?, ?)
                """, key.id(), account.id(), key.name(), SecretValues.digest(token), key.createdAt());
        return key;
    }

    void revokeKey(Account account, String id) {
        int removed = jdbc.update("DELETE FROM inbox_auth_api_keys WHERE id = ? AND account_id = ?", id, account.id());
        if (removed == 0) {
            throw new EngineException(404, "key_not_found", "API key not found", Map.of());
        }
    }

    record KeyInfo(String id, String name, String createdAt) {}

    record NewKey(String id, String name, String createdAt, String token) {
        @Override
        public String toString() {
            return "NewKey[id=" + id + "]";
        }
    }
}
