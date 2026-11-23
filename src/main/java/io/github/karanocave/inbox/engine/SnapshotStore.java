package io.github.karanocave.inbox.engine;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

final class SnapshotStore {
    private static final int SQL_BATCH_SIZE = 400;
    private static final DateTimeFormatter TIMESTAMP = new DateTimeFormatterBuilder().appendInstant(9).toFormatter();
    private static final String COLUMNS = "id, group_id, created_at, content, sender, action, target_type, url";
    private static final String UPSERT = """
            INSERT INTO unread_notice (account_id, id, group_id, created_at, content, sender, action, target_type, url)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(account_id, id) DO UPDATE SET
                group_id = excluded.group_id, created_at = excluded.created_at, content = excluded.content,
                sender = excluded.sender, action = excluded.action, target_type = excluded.target_type, url = excluded.url
            WHERE (unread_notice.group_id, unread_notice.created_at, unread_notice.content, unread_notice.sender,
                   unread_notice.action, unread_notice.target_type, unread_notice.url)
               IS NOT (excluded.group_id, excluded.created_at, excluded.content, excluded.sender,
                       excluded.action, excluded.target_type, excluded.url)
            """;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    SnapshotStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        transaction = new TransactionTemplate(new JdbcTransactionManager(Objects.requireNonNull(jdbc.getDataSource())));
        this.jdbc.execute("""
                CREATE TABLE IF NOT EXISTS inbox_snapshot (
                    account_id TEXT PRIMARY KEY, version INTEGER NOT NULL DEFAULT 0,
                    last_success TEXT, last_full TEXT, last_error TEXT,
                    reconciliation_pending INTEGER NOT NULL DEFAULT 0
                )
                """);
        this.jdbc.execute("""
                CREATE TABLE IF NOT EXISTS unread_notice (
                    account_id TEXT NOT NULL, id TEXT NOT NULL, group_id TEXT, created_at TEXT NOT NULL,
                    content TEXT NOT NULL, sender TEXT NOT NULL, action TEXT NOT NULL,
                    target_type TEXT NOT NULL, url TEXT NOT NULL,
                    PRIMARY KEY (account_id, id)
                ) WITHOUT ROWID
                """);
        this.jdbc.execute("""
                CREATE INDEX IF NOT EXISTS unread_notice_time
                ON unread_notice (account_id, created_at DESC, id DESC)
                """);
        this.jdbc.execute("""
                CREATE INDEX IF NOT EXISTS unread_notice_group
                ON unread_notice (account_id, group_id, created_at DESC, id DESC)
                """);
    }

    record Metadata(long version, Instant lastSuccess, Instant lastFull, String lastError, boolean pending) {}

    record View(Metadata metadata, Instant latest, int unreadTotal) {}

    View view(Account account) {
        return transaction.execute(status -> {
            Metadata metadata = metadata(account);
            return jdbc.queryForObject("""
                    SELECT max(created_at) AS latest, count(*) AS total
                    FROM unread_notice WHERE account_id = ?
                    """, (row, index) -> new View(metadata, parseTime(row.getString("latest")),
                    row.getInt("total")), account.id());
        });
    }

    InboxEngine.Status status(Account account, boolean busy) {
        return transaction.execute(tx -> statusInTransaction(account, busy));
    }

    private InboxEngine.Status statusInTransaction(Account account, boolean busy) {
        Metadata metadata = metadata(account);
        return jdbc.queryForObject("""
                SELECT count(*) AS total, count(*) FILTER (WHERE group_id IS NULL) AS unassigned
                FROM unread_notice WHERE account_id = ?
                """, (row, index) -> new InboxEngine.Status(metadata.version(), account.username(),
                metadata.lastSuccess(), metadata.lastFull(), metadata.lastError(), row.getInt("total"),
                row.getInt("unassigned"), busy), account.id());
    }

    int candidateCount(Account account, Collection<String> fetched, Instant boundary) {
        String timestamp = TIMESTAMP.format(boundary);
        return transaction.execute(status -> {
            int tail = count("SELECT count(*) FROM unread_notice WHERE account_id = ? AND created_at <= ?",
                    account.id(), timestamp);
            List<String> fetchedIds = new ArrayList<>(fetched);
            for (int start = 0; start < fetchedIds.size(); start += SQL_BATCH_SIZE) {
                List<String> ids = fetchedIds.subList(start, Math.min(start + SQL_BATCH_SIZE, fetchedIds.size()));
                List<Object> args = new ArrayList<>();
                Collections.addAll(args, account.id(), timestamp);
                args.addAll(ids);
                tail -= count("""
                        SELECT count(*) FROM unread_notice WHERE account_id = ? AND created_at <= ?
                        AND id IN (%s)
                        """.formatted(placeholders(ids.size())), args.toArray());
            }
            return fetched.size() + tail;
        });
    }

    record Applied(int upserted, int removed, long version, Instant syncedAt) {}

    Applied apply(Account account, Collection<Notice> messages, Instant boundary, Instant now) {
        return transaction.execute(tx -> {
            ensureAccount(account);
            Metadata metadata = metadata(account);
            jdbc.execute("CREATE TEMP TABLE IF NOT EXISTS inbox_fetched (id TEXT PRIMARY KEY) WITHOUT ROWID");
            jdbc.batchUpdate("INSERT INTO inbox_fetched (id) VALUES (?)", messages, SQL_BATCH_SIZE,
                    (statement, message) -> statement.setString(1, message.id()));
            long before = totalChanges();
            jdbc.batchUpdate(UPSERT, messages, SQL_BATCH_SIZE, (statement, message) -> {
                statement.setString(1, account.id());
                statement.setString(2, message.id());
                statement.setString(3, message.groupId());
                statement.setString(4, TIMESTAMP.format(message.createdAt()));
                statement.setString(5, message.content());
                statement.setString(6, message.sender());
                statement.setString(7, message.action());
                statement.setString(8, message.targetType());
                statement.setString(9, message.url());
            });
            int upserted = (int) (totalChanges() - before);
            String cutoff = boundary == null ? null : TIMESTAMP.format(boundary);
            int removed = jdbc.update("""
                    DELETE FROM unread_notice AS n
                    WHERE account_id = ? AND (? IS NULL OR created_at > ?)
                    AND NOT EXISTS (SELECT 1 FROM inbox_fetched AS f WHERE f.id = n.id)
                    """, account.id(), cutoff, cutoff);
            jdbc.update("DELETE FROM inbox_fetched");
            long version = metadata.version();
            if (upserted > 0 || removed > 0 || metadata.lastSuccess() == null) {
                version++;
            }
            String syncedAt = TIMESTAMP.format(now);
            if (boundary == null) {
                jdbc.update("""
                        UPDATE inbox_snapshot SET version = ?, last_success = ?, last_full = ?, last_error = NULL,
                            reconciliation_pending = 0 WHERE account_id = ?
                        """, version, syncedAt, syncedAt, account.id());
            } else {
                jdbc.update("""
                        UPDATE inbox_snapshot SET version = ?, last_success = ?, last_error = NULL WHERE account_id = ?
                        """, version, syncedAt, account.id());
            }
            return new Applied(upserted, removed, version, now);
        });
    }

    void recordError(Account account, String message) {
        jdbc.update("""
                INSERT INTO inbox_snapshot (account_id, last_error) VALUES (?, ?)
                ON CONFLICT(account_id) DO UPDATE SET last_error = excluded.last_error
                """, account.id(), message);
    }

    void setPending(Account account, boolean pending) {
        jdbc.update("UPDATE inbox_snapshot SET reconciliation_pending = ? WHERE account_id = ?",
                pending ? 1 : 0, account.id());
    }

    List<String> idsFor(Account account, List<String> groupIds) {
        List<Object> args = new ArrayList<>();
        args.add(account.id());
        args.addAll(groupIds);
        return jdbc.queryForList("""
                SELECT id FROM unread_notice WHERE account_id = ? AND group_id IN (%s)
                ORDER BY created_at DESC, id DESC
                """.formatted(placeholders(groupIds.size())), String.class, args.toArray());
    }

    long remove(Account account, List<String> ids) {
        return transaction.execute(tx -> {
            List<Object> args = new ArrayList<>();
            args.add(account.id());
            args.addAll(ids);
            int removed = jdbc.update("""
                    DELETE FROM unread_notice WHERE account_id = ? AND id IN (%s)
                    """.formatted(placeholders(ids.size())), args.toArray());
            if (removed > 0) {
                jdbc.update("UPDATE inbox_snapshot SET version = version + 1 WHERE account_id = ?", account.id());
            }
            return metadata(account).version();
        });
    }

    InboxEngine.Groups groups(Account account, int limit, int offset, String kind, String repo,
                              String search, Long version, boolean busy) {
        List<String> conditions = new ArrayList<>(List.of("n.account_id = ?", "n.group_id IS NOT NULL"));
        List<Object> filters = new ArrayList<>(List.of(account.id()));
        if (!kind.isEmpty()) {
            conditions.add("n.group_id GLOB ?");
            filters.add("*/*/" + kind + "/*");
        }
        if (!repo.isEmpty()) {
            conditions.add("substr(n.group_id, 1, length(?) + 1) = ? || '/'");
            filters.add(repo);
            filters.add(repo);
        }
        if (!search.isEmpty()) {
            conditions.add("""
                    (instr(lower(n.group_id), ?) > 0 OR instr(lower(n.content), ?) > 0
                        OR instr(lower(n.sender), ?) > 0)
                    """);
            filters.addAll(Collections.nCopies(3, search.toLowerCase(Locale.ROOT)));
        }
        String where = String.join(" AND ", conditions);
        return transaction.execute(tx -> {
            InboxEngine.Status status = statusInTransaction(account, busy);
            assertVersion(status.version(), version);
            int total = count("""
                    SELECT count(DISTINCT n.group_id) FROM unread_notice AS n WHERE %s
                    """.formatted(where), filters.toArray());
            List<Object> args = new ArrayList<>(filters);
            Collections.addAll(args, account.id(), limit, offset);
            List<InboxEngine.Group> items = jdbc.query("""
                    WITH matches AS (
                        SELECT DISTINCT n.group_id FROM unread_notice AS n WHERE %s
                    ), groups AS (
                        SELECT n.account_id, n.group_id, count(*) AS unread_count, max(n.created_at) AS latest_at
                        FROM matches JOIN unread_notice AS n ON n.group_id = matches.group_id
                        WHERE n.account_id = ?
                        GROUP BY n.group_id ORDER BY latest_at DESC, n.group_id LIMIT ? OFFSET ?
                    )
                    SELECT groups.*,
                        coalesce((
                            SELECT content FROM unread_notice
                            WHERE account_id = groups.account_id AND group_id = groups.group_id AND content != ''
                              AND target_type IN ('issue', 'merge_requests')
                            ORDER BY created_at DESC, id DESC LIMIT 1
                        ), latest.content) AS title, latest.sender AS latest_sender, latest.action AS latest_action
                    FROM groups JOIN unread_notice AS latest ON latest.account_id = groups.account_id AND latest.id = (
                        SELECT id FROM unread_notice WHERE account_id = groups.account_id AND group_id = groups.group_id
                        ORDER BY created_at DESC, id DESC LIMIT 1
                    )
                    ORDER BY groups.latest_at DESC, groups.group_id
                    """.formatted(where), (row, index) -> {
                Target target = Objects.requireNonNull(Target.parseGroupId(row.getString("group_id")));
                return new InboxEngine.Group(target.id(), target.repository(), target.kind(), target.number(),
                        target.url(), row.getString("title"), row.getInt("unread_count"),
                        parseTime(row.getString("latest_at")), row.getString("latest_sender"),
                        row.getString("latest_action"));
            }, args.toArray());
            return new InboxEngine.Groups(items, total, limit, offset, status);
        });
    }

    InboxEngine.Messages messages(Account account, String groupId, int limit, int offset, Long version) {
        return transaction.execute(tx -> {
            long current = metadata(account).version();
            assertVersion(current, version);
            int total = count("SELECT count(*) FROM unread_notice WHERE account_id = ? AND group_id IS ?",
                    account.id(), groupId);
            List<Notice> items = jdbc.query("""
                    SELECT %s FROM unread_notice WHERE account_id = ? AND group_id IS ?
                    ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?
                    """.formatted(COLUMNS), (row, index) -> new Notice(row.getString("id"),
                    row.getString("group_id"), parseTime(row.getString("created_at")), row.getString("content"),
                    row.getString("sender"), row.getString("action"), row.getString("target_type"),
                    row.getString("url")), account.id(), groupId, limit, offset);
            return new InboxEngine.Messages(items, total, limit, offset, current);
        });
    }

    private Metadata metadata(Account account) {
        return jdbc.query("""
                SELECT version, last_success, last_full, last_error, reconciliation_pending
                FROM inbox_snapshot WHERE account_id = ?
                """, (row, index) -> new Metadata(row.getLong("version"), parseTime(row.getString("last_success")),
                parseTime(row.getString("last_full")), row.getString("last_error"),
                row.getInt("reconciliation_pending") != 0), account.id()).stream().findFirst()
                .orElse(new Metadata(0, null, null, null, false));
    }

    private void ensureAccount(Account account) {
        jdbc.update("INSERT INTO inbox_snapshot (account_id) VALUES (?) ON CONFLICT DO NOTHING", account.id());
    }

    private int count(String sql, Object... args) {
        return Objects.requireNonNull(jdbc.queryForObject(sql, Integer.class, args));
    }

    private long totalChanges() {
        return Objects.requireNonNull(jdbc.queryForObject("SELECT total_changes()", Long.class));
    }

    private static Instant parseTime(String value) {
        return value == null ? null : Instant.parse(value);
    }

    private static String placeholders(int count) {
        return String.join(", ", Collections.nCopies(count, "?"));
    }

    private static void assertVersion(long current, Long requested) {
        if (requested != null && current != requested) {
            throw new EngineException(409, "snapshot_changed", "未读快照已更新，请从第一页重新读取。",
                    Map.of("version", current));
        }
    }
}
