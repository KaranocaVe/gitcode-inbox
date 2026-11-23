package io.github.karanocave.inbox.engine;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

@Service
public class InboxEngine {
    private static final int PAGE_SIZE = 50;
    private static final int BATCH_SIZE = 200;
    private static final int MAX_PAGES = 10_000;
    private static final int MAX_ATTEMPTS = 3;
    private static final String CACHE_UPDATE_ERROR = "已发送的已读操作与本地缓存未能完成校准。";

    public record Status(long version, String account, Instant lastSuccess, Instant lastFull, String lastError,
                         int unreadTotal, int unassignedCount, boolean busy) {}

    public record Group(String id, String repository, String kind, long number, String url, String title,
                        int unreadCount, Instant latestAt, String latestSender, String latestAction) {}

    public record Groups(List<Group> items, int total, int limit, int offset, Status status) {}

    public record Messages(List<Notice> items, int total, int limit, int offset, long version) {}

    public record SyncResult(int upserted, int removed, long version, Instant syncedAt, String mode,
                             int listRequests, int attempts, int fetched, int unreadTotal) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ReadResult(List<String> groupIds, int matched, int confirmed, int batches, long version,
                             boolean dryRun, List<String> messageIds, List<String> confirmedIds) {}

    private final SnapshotStore store;
    private final NotificationSource source;
    private final Clock clock;
    private final Set<String> busy = ConcurrentHashMap.newKeySet();

    @Autowired
    public InboxEngine(JdbcTemplate jdbc, NotificationSource source) {
        this(jdbc, source, Clock.systemUTC());
    }

    InboxEngine(JdbcTemplate jdbc, NotificationSource source, Clock clock) {
        this.store = new SnapshotStore(jdbc);
        this.source = source;
        this.clock = clock;
    }

    public Status status(Account account) {
        return store.status(account, busy.contains(account.id()));
    }

    public Groups groups(Account account, int limit, int offset, String kind, String repo, String q, Long version) {
        return store.groups(account, limit, offset, Objects.requireNonNullElse(kind, ""),
                Objects.requireNonNullElse(repo, "").strip().toLowerCase(Locale.ROOT),
                Objects.requireNonNullElse(q, "").strip(), version, busy.contains(account.id()));
    }

    public Messages messages(Account account, String groupId, int limit, int offset, Long version) {
        return store.messages(account, groupId, limit, offset, version);
    }

    public SyncResult sync(Account account, boolean force, int fullIntervalSeconds, int debounceSeconds) {
        return exclusive(account, () -> syncSnapshot(account, force, fullIntervalSeconds, debounceSeconds));
    }

    public ReadResult read(Account account, List<String> groupIds, boolean dryRun) {
        if (groupIds == null || groupIds.isEmpty() || groupIds.size() > BATCH_SIZE
                || groupIds.stream().anyMatch(id -> Target.parseGroupId(id) == null)) {
            throw new EngineException(400, "invalid_group", "请指定 1–200 个列表返回的 group_id。", Map.of());
        }
        List<String> selected = List.copyOf(new LinkedHashSet<>(groupIds));
        return exclusive(account, () -> readGroups(account, selected, dryRun));
    }

    private ReadResult readGroups(Account account, List<String> groups, boolean dryRun) {
        SyncResult synced = syncSnapshot(account, true, 0, 0);
        List<String> ids = store.idsFor(account, groups);
        long version = synced.version();
        if (dryRun || ids.isEmpty()) {
            return new ReadResult(groups, ids.size(), 0, 0, version, dryRun,
                    dryRun ? List.copyOf(ids) : null, List.of());
        }

        // Persist this before the first PUT so a process restart also forces a full reconciliation.
        store.setPending(account, true);
        int batches = 0;
        List<String> confirmed = new ArrayList<>();
        for (int start = 0; start < ids.size(); start += BATCH_SIZE) {
            int end = Math.min(start + BATCH_SIZE, ids.size());
            List<String> batch = List.copyOf(ids.subList(start, end));
            try {
                source.markRead(account, batch);
            } catch (UpstreamException error) {
                recordError(account, error);
                ReadResult result = readResult(groups, ids.size(), version, batches, confirmed);
                Map<String, Object> details = partialDetails(result, batch, ids.subList(end, ids.size()),
                        error.uncertain());
                throw upstreamError(error, details);
            }

            confirmed.addAll(batch);
            batches++;
            try {
                version = store.remove(account, batch);
            } catch (DataAccessException | TransactionException error) {
                ReadResult result = readResult(groups, ids.size(), version, batches, confirmed);
                throw cacheError(result, ids.subList(end, ids.size()), error);
            }
        }
        ReadResult result = readResult(groups, ids.size(), version, batches, confirmed);
        try {
            store.setPending(account, false);
        } catch (DataAccessException error) {
            throw cacheError(result, List.of(), error);
        }
        return result;
    }

    private static ReadResult readResult(List<String> groups, int matched, long version, int batches,
                                         List<String> confirmed) {
        return new ReadResult(groups, matched, confirmed.size(), batches, version, false, null,
                List.copyOf(confirmed));
    }

    private static Map<String, Object> partialDetails(ReadResult result, List<String> batch, List<String> remaining,
                                                       boolean uncertain) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("result", result);
        details.put("confirmed_ids", result.confirmedIds());
        details.put("failed_ids", uncertain ? List.of() : batch);
        details.put("uncertain_ids", uncertain ? batch : List.of());
        details.put("remaining_ids", List.copyOf(remaining));
        details.put("retry_from_snapshot", true);
        return details;
    }

    private static EngineException cacheError(ReadResult result, List<String> remaining, RuntimeException cause) {
        EngineException error = new EngineException(500, "cache_update_failed", CACHE_UPDATE_ERROR,
                partialDetails(result, List.of(), remaining, false));
        error.initCause(cause);
        return error;
    }

    private SyncResult syncSnapshot(Account account, boolean force, int fullIntervalSeconds, int debounceSeconds) {
        SnapshotStore.View view = store.view(account);
        SnapshotStore.Metadata metadata = view.metadata();
        Instant startedAt = clock.instant();
        boolean full = force || metadata.pending() || metadata.lastFull() == null
                || expired(metadata.lastFull(), startedAt, fullIntervalSeconds);
        if (debounceSeconds > 0 && !full && metadata.lastError() == null && metadata.lastSuccess() != null
                && !expired(metadata.lastSuccess(), startedAt, debounceSeconds)) {
            return new SyncResult(0, 0, metadata.version(), metadata.lastSuccess(), "debounced",
                    0, 0, 0, view.unreadTotal());
        }

        Scan scanned;
        int attempt = 1;
        int requests = 0;
        while (true) {
            try {
                scanned = scan(account, view.latest(), full);
                break;
            } catch (MovedInbox error) {
                if (attempt == MAX_ATTEMPTS) {
                    throw error;
                }
                requests += error.requests;
                pauseBeforeRetry(attempt++);
            }
        }
        SnapshotStore.Applied applied = store.apply(account, scanned.messages(), scanned.boundary(), clock.instant());
        return new SyncResult(applied.upserted(), applied.removed(), applied.version(), applied.syncedAt(),
                scanned.boundary() == null ? "full" : "incremental", requests + scanned.requests(), attempt,
                scanned.messages().size(), scanned.total());
    }

    private Scan scan(Account account, Instant latest, boolean full) {
        Map<String, Notice> seen = new LinkedHashMap<>();
        Page head = null;
        Instant previous = null;
        Instant boundary = null;
        int number = 1;
        while (true) {
            Page page = source.page(account, number);
            if (!validPage(page, number) || head != null && !samePagination(page, head)) {
                throw new MovedInbox(number);
            }
            if (head == null) {
                head = page;
                if (page.pages() > MAX_PAGES) {
                    throw new UpstreamException("upstream_error", "GitCode 未读分页超过 " + MAX_PAGES + " 页。",
                            false, 0);
                }
            }
            for (Notice notice : page.messages()) {
                if (notice.createdAt() == null || notice.id() == null || notice.id().isEmpty()
                        || notice.id().length() > 256) {
                    throw new UpstreamException("upstream_error", "GitCode 未读消息缺少有效 ID 或时间。", false, 0);
                }
                if (seen.putIfAbsent(notice.id(), normalized(notice)) != null
                        || previous != null && notice.createdAt().isAfter(previous)) {
                    throw new MovedInbox(number);
                }
                previous = notice.createdAt();
            }
            if (number >= page.pages()) {
                if (seen.size() != page.total()) {
                    throw new MovedInbox(number);
                }
                break;
            }
            if (!full && latest != null && previous.isBefore(latest)) {
                // Keep the entire cutoff timestamp because an unread page can split equal timestamps.
                if (store.candidateCount(account, seen.keySet(), previous) == page.total()) {
                    boundary = previous;
                    break;
                }
                full = true;
            }
            number++;
        }
        int requests = number;
        if (number > 1) {
            Page headAgain = source.page(account, 1);
            requests++;
            if (headAgain.current() != 1 || !samePagination(headAgain, head)
                    || !headAgain.messages().equals(head.messages())) {
                throw new MovedInbox(requests);
            }
        }
        return new Scan(seen.values(), boundary, requests, head.total());
    }

    private static Notice normalized(Notice notice) {
        Target.Destination destination = Target.destination(notice.url());
        Target target = destination.target();
        if (notice.url() == null || notice.url().isEmpty()) {
            // The source can provide a previously canonicalized target for notices without a display URL.
            target = Target.parseGroupId(notice.groupId());
        }
        return new Notice(notice.id(), target == null ? null : target.id(), notice.createdAt(),
                Objects.requireNonNullElse(notice.content(), ""), Objects.requireNonNullElse(notice.sender(), ""),
                Objects.requireNonNullElse(notice.action(), ""), Objects.requireNonNullElse(notice.targetType(), ""),
                destination.url());
    }

    private static boolean validPage(Page page, int number) {
        if (page.current() != number || page.size() < 1 || page.size() > PAGE_SIZE || page.total() < 0) {
            return false;
        }
        int expectedPages = (int) ((page.total() + (long) page.size() - 1) / page.size());
        long expectedRows = Math.clamp(page.total() - ((long) number - 1) * page.size(), 0, page.size());
        return (page.pages() == expectedPages || page.total() == 0 && page.pages() == 1)
                && page.messages().size() == expectedRows;
    }

    private static boolean samePagination(Page actual, Page expected) {
        return actual.size() == expected.size() && actual.pages() == expected.pages()
                && actual.total() == expected.total();
    }

    private static boolean expired(Instant stamp, Instant now, int intervalSeconds) {
        return Duration.between(stamp, now).compareTo(Duration.ofSeconds(intervalSeconds)) >= 0;
    }

    private static void pauseBeforeRetry(int attempt) {
        try {
            Thread.sleep(Duration.ofMillis(250L * attempt));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new UpstreamException("sync_interrupted", "同步已中断，请重试。", false, 0);
        }
    }

    private <T> T exclusive(Account account, Supplier<T> work) {
        if (!busy.add(account.id())) {
            throw new EngineException(409, "busy", "同步或已读操作正在执行，请稍后重试。", Map.of());
        }
        try {
            return work.get();
        } catch (UpstreamException error) {
            recordError(account, error);
            throw upstreamError(error, new LinkedHashMap<>());
        } finally {
            busy.remove(account.id());
        }
    }

    private void recordError(Account account, UpstreamException error) {
        try {
            store.recordError(account, error.getMessage());
        } catch (DataAccessException storageError) {
            error.addSuppressed(storageError);
        }
    }

    private static EngineException upstreamError(UpstreamException error, Map<String, Object> details) {
        if (error.retryAfterSeconds() > 0) {
            details.put("retry_after_seconds", error.retryAfterSeconds());
        }
        EngineException mapped = new EngineException(502, error.code(), error.getMessage(), details);
        mapped.initCause(error);
        return mapped;
    }

    private record Scan(Collection<Notice> messages, Instant boundary, int requests, int total) {}

    private static final class MovedInbox extends UpstreamException {
        private final int requests;

        private MovedInbox(int requests) {
            super("inbox_changed", "同步期间 GitCode 分页发生变化，请重试。", false, 0);
            this.requests = requests;
        }
    }
}
