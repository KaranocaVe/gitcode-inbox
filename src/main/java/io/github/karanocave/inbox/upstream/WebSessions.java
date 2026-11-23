package io.github.karanocave.inbox.upstream;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.karanocave.inbox.engine.*;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.IntStream;

@Service
public class WebSessions {
    private record Secret(String access, String refresh) {
        @Override public String toString() { return "[GitCode credentials]"; }
    }
    private record Entry(String id, String cipher) {}
    private static final SecureRandom RANDOM = new SecureRandom();
    private final ReentrantLock[] locks = IntStream.range(0, 64).mapToObj(i -> new ReentrantLock()).toArray(ReentrantLock[]::new);
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final GitCodeWeb gitcode;
    private final SecretKeySpec key;

    public WebSessions(JdbcTemplate jdbc, ObjectMapper json, GitCodeWeb gitcode,
                       @Value("${INBOX_TOKEN_KEY:}") String value,
                       @Value("${INBOX_MODE:production}") String mode) {
        this.jdbc = jdbc;
        this.json = json;
        this.gitcode = gitcode;
        byte[] bytes;
        try {
            bytes = "demo".equals(mode) && value.isBlank() ? random(32) : Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("INBOX_TOKEN_KEY must encode 32 random bytes as base64.");
        }
        if (bytes.length != 32) throw new IllegalArgumentException("INBOX_TOKEN_KEY must encode 32 random bytes as base64.");
        key = new SecretKeySpec(bytes, "AES");
    }

    @PostConstruct
    void init() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS linked_web_sessions (
              account_id TEXT PRIMARY KEY, username TEXT NOT NULL, web_id TEXT NOT NULL, credentials TEXT NOT NULL
            )
            """);
    }

    public boolean linked(Account account) {
        return jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM linked_web_sessions WHERE account_id=? AND credentials <> '')",
                Integer.class, account.id()) != 0;
    }

    public List<Account> accounts() {
        return jdbc.query("SELECT account_id, username FROM linked_web_sessions WHERE credentials <> '' ORDER BY account_id",
                (row, index) -> new Account(row.getString(1), row.getString(2)));
    }

    public void link(Account account, String access, String refresh) {
        var credentials = new GitCodeWeb.Refreshed(access, refresh);
        validate(credentials);
        linkVerified(account, gitcode.identity(access), credentials);
    }

    public void linkVerified(Account account, GitCodeWeb.Identity identity, GitCodeWeb.Refreshed secret) {
        validate(secret);
        if (!identity.username().equalsIgnoreCase(account.username())) throw mismatch();
        var lock = lock(account);
        lock.lock();
        try {
            int changed = jdbc.update("""
                INSERT INTO linked_web_sessions VALUES (?, ?, ?, ?)
                ON CONFLICT(account_id) DO UPDATE SET username=excluded.username, credentials=excluded.credentials
                WHERE linked_web_sessions.web_id=excluded.web_id
                """, account.id(), account.username(), identity.id(),
                    encrypt(account, new Secret(secret.access(), secret.refresh())));
            if (changed == 0) throw mismatch();
        } finally {
            lock.unlock();
        }
    }

    private static void validate(GitCodeWeb.Refreshed credentials) {
        String access = credentials.access();
        String refresh = credentials.refresh();
        if (!GitCodeWeb.validToken(access) || refresh != null && !refresh.isEmpty() && !GitCodeWeb.validToken(refresh)) {
            throw new EngineException(400, "invalid_credentials", "请提供有效的 GitCode Web 令牌。", Map.of());
        }
    }

    public void unlink(Account account) {
        var lock = lock(account);
        lock.lock();
        try {
            jdbc.update("UPDATE linked_web_sessions SET credentials='' WHERE account_id=?", account.id());
        } finally {
            lock.unlock();
        }
    }

    public Page page(Account account, int number) {
        var lock = lock(account);
        lock.lock();
        try {
            Entry entry = entry(account);
            Secret secret = decrypt(account, entry.cipher());
            try {
                return gitcode.page(secret.access(), entry.id(), number);
            } catch (UpstreamException e) {
                if (!e.code().equals("session_expired") || !GitCodeWeb.validToken(secret.refresh())) throw e;
            }
            var rotated = gitcode.refresh(secret.access(), secret.refresh());
            var newIdentity = gitcode.identity(rotated.access());
            if (!entry.id().equals(newIdentity.id())) {
                throw new UpstreamException("account_changed", "刷新后的 GitCode 账户与已连接账户不同。", false, 0);
            }
            jdbc.update("UPDATE linked_web_sessions SET credentials=? WHERE account_id=?",
                    encrypt(account, new Secret(rotated.access(), rotated.refresh())), account.id());
            return gitcode.page(rotated.access(), entry.id(), number);
        } finally {
            lock.unlock();
        }
    }

    public void markRead(Account account, List<String> ids) {
        var lock = lock(account);
        lock.lock();
        try {
            Entry entry = entry(account);
            gitcode.markRead(decrypt(account, entry.cipher()).access(), entry.id(), ids);
        } finally {
            lock.unlock();
        }
    }

    private Entry entry(Account account) {
        return jdbc.query("SELECT web_id, credentials FROM linked_web_sessions WHERE account_id=? AND credentials <> ''",
                        (row, i) -> new Entry(row.getString(1), row.getString(2)), account.id())
                .stream().findFirst().orElseThrow(() -> new UpstreamException("web_session_required",
                        "请先在收件箱连接 GitCode Web 会话。", false, 0));
    }

    private ReentrantLock lock(Account account) { return locks[Math.floorMod(account.id().hashCode(), locks.length)]; }

    private String encrypt(Account account, Secret secret) {
        try {
            byte[] nonce = random(12);
            byte[] sealed = cipher(Cipher.ENCRYPT_MODE, account, nonce).doFinal(json.writeValueAsBytes(secret));
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(nonce.length + sealed.length)
                    .put(nonce).put(sealed).array());
        } catch (Exception e) {
            throw new IllegalStateException("Cannot encrypt linked GitCode session.");
        }
    }

    private Secret decrypt(Account account, String text) {
        try {
            var bytes = ByteBuffer.wrap(Base64.getDecoder().decode(text));
            byte[] nonce = new byte[12];
            bytes.get(nonce);
            byte[] sealed = new byte[bytes.remaining()];
            bytes.get(sealed);
            return json.readValue(cipher(Cipher.DECRYPT_MODE, account, nonce).doFinal(sealed), Secret.class);
        } catch (Exception e) {
            throw new UpstreamException("invalid_session_storage", "无法解密收件箱会话，请重新连接。", false, 0);
        }
    }

    private Cipher cipher(int mode, Account account, byte[] nonce) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, key, new GCMParameterSpec(128, nonce));
        cipher.updateAAD(account.id().getBytes(StandardCharsets.UTF_8));
        return cipher;
    }

    private static byte[] random(int length) {
        byte[] bytes = new byte[length];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    private static EngineException mismatch() {
        return new EngineException(409, "account_mismatch", "Web 会话与当前 GitCode 账户不同。", Map.of());
    }
}
