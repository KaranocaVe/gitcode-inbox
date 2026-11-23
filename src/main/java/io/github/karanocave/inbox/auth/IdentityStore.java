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
    static final String INBOX_SCOPE = "inbox";
    static final String MCP_ADMIN_SCOPE = "mcp_admin";
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
                    created_at TEXT NOT NULL,
                    scope TEXT NOT NULL DEFAULT 'inbox'
                )
                """);
        Integer scopeColumn = jdbc.queryForObject("""
                SELECT count(*) FROM pragma_table_info('inbox_auth_api_keys') WHERE name = 'scope'
                """, Integer.class);
        if (scopeColumn != null && scopeColumn == 0) {
            jdbc.execute("ALTER TABLE inbox_auth_api_keys ADD COLUMN scope TEXT NOT NULL DEFAULT 'inbox'");
        }
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

    AuthenticatedKey authenticateKey(String key) {
        if (!KEY_PATTERN.matcher(key).matches()) {
            return null;
        }
        return jdbc.query("""
                SELECT a.id, a.username, k.scope
                FROM inbox_auth_api_keys k JOIN inbox_auth_accounts a ON a.id = k.account_id
                WHERE k.digest = ?
                """, (row, index) -> new AuthenticatedKey(new Account(row.getString("id"), row.getString("username")),
                        row.getString("scope")), SecretValues.digest(key)).stream().findFirst().orElse(null);
    }

    List<KeyInfo> listKeys(Account account) {
        return jdbc.query("""
                SELECT id, name, created_at, scope FROM inbox_auth_api_keys WHERE account_id = ?
                ORDER BY created_at DESC, id
                """, (row, index) -> new KeyInfo(row.getString("id"), row.getString("name"),
                row.getString("created_at"), row.getString("scope")), account.id());
    }

    NewKey createKey(Account account, String name, String requestedScope) {
        if (name == null || name.isBlank() || name.strip().length() > 100
                || name.codePoints().anyMatch(Character::isISOControl)) {
            throw new EngineException(400, "invalid_key_name", "API key name must contain 1–100 characters", Map.of());
        }
        String scope = requestedScope == null ? INBOX_SCOPE : requestedScope;
        if (!scope.equals(INBOX_SCOPE) && !scope.equals(MCP_ADMIN_SCOPE)) {
            throw new EngineException(400, "invalid_key_scope", "Unknown API key scope", Map.of());
        }
        if (scope.equals(MCP_ADMIN_SCOPE) && !settings.isAdmin(account)) {
            throw new EngineException(403, "admin_required", "An administrator account is required", Map.of());
        }
        String token = KEY_PREFIX + SecretValues.generate();
        NewKey key = new NewKey(UUID.randomUUID().toString(), name.strip(), Instant.now().toString(), scope, token);
        jdbc.update("""
                INSERT INTO inbox_auth_api_keys (id, account_id, name, digest, created_at, scope)
                VALUES (?, ?, ?, ?, ?, ?)
                """, key.id(), account.id(), key.name(), SecretValues.digest(token), key.createdAt(), scope);
        return key;
    }

    void revokeKey(Account account, String id) {
        int removed = jdbc.update("DELETE FROM inbox_auth_api_keys WHERE id = ? AND account_id = ?", id, account.id());
        if (removed == 0) {
            throw new EngineException(404, "key_not_found", "API key not found", Map.of());
        }
    }

    record AuthenticatedKey(Account account, String scope) {}

    record KeyInfo(String id, String name, String createdAt, String scope) {}

    record NewKey(String id, String name, String createdAt, String scope, String token) {
        @Override
        public String toString() {
            return "NewKey[id=" + id + "]";
        }
    }
}
