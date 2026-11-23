package io.github.karanocave.inbox.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

class InboxEngineTest {
    private static final Account ALICE = new Account("alice-123", "alice");
    private static final Account BOB = new Account("bob-456", "bob");
    private static final String PULL = "example/repo/pr/42";
    private static final String ISSUE = "example/repo/issue/42";
    private static final Instant START = Instant.parse("2026-11-23T00:00:00Z");

    @TempDir Path temp;
    private Source source;
    private InboxEngine inbox;
    private JdbcTemplate jdbc;
    private TestClock clock;

    @BeforeEach
    void setUp() {
        SQLiteConfig config = new SQLiteConfig();
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        config.setBusyTimeout(3000);
        SQLiteDataSource dataSource = new SQLiteDataSource(config);
        dataSource.setUrl("jdbc:sqlite:" + temp.resolve("inbox.sqlite3"));
        jdbc = new JdbcTemplate(dataSource);
        clock = new TestClock();
        source = new Source();
        source.set(ALICE, notices(0, 240));
        inbox = new InboxEngine(jdbc, source, clock);
    }

    @Test
    void incrementallyScansThroughTimestampBoundaryAndFallsBackForTailDeletion() {
        assertThat(sync(ALICE).mode()).isEqualTo("full");
        assertThat(sync(ALICE).listRequests()).isEqualTo(1);
        source.set(ALICE, notices(0, 310));
        InboxEngine.SyncResult increment = sync(ALICE);
        assertThat(increment.mode()).isEqualTo("incremental");
        assertThat(increment.listRequests()).isEqualTo(3);
        assertThat(increment.upserted()).isEqualTo(70);
        source.set(ALICE, notices(1, 310));
        InboxEngine.SyncResult fallback = sync(ALICE);
        assertThat(fallback.mode()).isEqualTo("full");
        assertThat(fallback.removed()).isEqualTo(1);
        assertThat(inbox.status(ALICE).unreadTotal()).isEqualTo(309);
    }

    @Test
    void preservesTiesAndSameVersionAcrossIdenticalSnapshots() {
        source.set(ALICE, IntStream.range(0, 240).mapToObj(i -> notice(i, i / 3, PULL)).toList());
        long version = sync(ALICE).version();
        InboxEngine.SyncResult second = sync(ALICE);
        assertThat(second.mode()).isEqualTo("incremental");
        assertThat(second.version()).isEqualTo(version);
        assertThat(second.upserted()).isZero();
        assertThat(second.removed()).isZero();
        assertThat(inbox.status(ALICE).unreadTotal()).isEqualTo(240);
    }

    @Test
    void debouncesFreshSyncUnlessForcedOrFullReconciliationIsDue() {
        InboxEngine.SyncResult initial = inbox.sync(ALICE, false, 30, 60);
        source.set(ALICE, notices(0, 241));
        assertThat(inbox.sync(ALICE, false, 30, 60).mode()).isEqualTo("debounced");
        assertThat(inbox.status(ALICE).version()).isEqualTo(initial.version());
        assertThat(inbox.sync(ALICE, true, 30, 60).upserted()).isEqualTo(1);
        source.set(ALICE, notices(0, 242));
        clock.now = START.plusSeconds(30);
        InboxEngine.SyncResult periodic = inbox.sync(ALICE, false, 30, 60);
        assertThat(periodic.mode()).isEqualTo("full");
        assertThat(periodic.upserted()).isEqualTo(1);
    }

    @Test
    void preservesSnapshotWhenRepeatedPagesOverlap() {
        long version = sync(ALICE).version();
        source.set(ALICE, notices(0, 241));
        source.pageOverride = (account, number) -> {
            Page original = source.normalPage(account, number);
            if (number != 2) {
                return original;
            }
            List<Notice> overlapping = new ArrayList<>(original.messages());
            overlapping.set(0, source.normalPage(account, 1).messages().getLast());
            return new Page(number, original.size(), original.pages(), original.total(), overlapping);
        };
        EngineException error = assertThrows(EngineException.class, () -> inbox.sync(ALICE, true, 900, 0));
        assertThat(error.code()).isEqualTo("inbox_changed");
        assertThat(inbox.status(ALICE).version()).isEqualTo(version);
        assertThat(inbox.status(ALICE).unreadTotal()).isEqualTo(240);
        assertThat(inbox.status(ALICE).lastError()).isEqualTo(error.getMessage());
    }

    @Test
    void retriesWhenRecheckedHeadChangesWithoutATotalChange() {
        int[] headRequests = {0};
        source.pageOverride = (account, number) -> {
            Page original = source.normalPage(account, number);
            if (number != 1 || ++headRequests[0] != 2) {
                return original;
            }
            List<Notice> changed = new ArrayList<>(original.messages());
            changed.set(0, notice(400, 500, ISSUE));
            return new Page(number, original.size(), original.pages(), original.total(), changed);
        };
        InboxEngine.SyncResult sync = sync(ALICE);
        assertThat(sync.attempts()).isEqualTo(2);
        assertThat(sync.listRequests()).isEqualTo(12);
        assertThat(inbox.messages(ALICE, ISSUE, 20, 0, sync.version()).total()).isZero();
    }

    @Test
    void groupsByCanonicalUrlAndFiltersWithoutDroppingRelatedUnreadMessages() {
        source.set(ALICE, List.of(
                new Notice("base", ISSUE, START, "Original title", "Author", "open",
                        "merge_requests", "http://www.gitcode.com/Example/Repo/pull/0042"),
                new Notice("mention", null, START.plusSeconds(5), "Review needed", "Reviewer", "mention",
                        "mention", "/example/repo/merge_requests/42?did=123#reply"),
                notice(20, 2, ISSUE),
                new Notice("external", PULL, START.plusSeconds(10), "Other", "", "",
                        "issue", "https://gitcode.com.evil.test/example/repo/pull/42")
        ));
        long version = sync(ALICE).version();
        InboxEngine.Groups groups = inbox.groups(ALICE, 1, 0, "pr", "EXAMPLE/REPO", "reviewer", version);
        assertThat(groups.total()).isEqualTo(1);
        assertThat(groups.items()).singleElement().satisfies(group -> {
            assertThat(group.id()).isEqualTo(PULL);
            assertThat(group.url()).isEqualTo("https://gitcode.com/example/repo/pull/42");
            assertThat(group.unreadCount()).isEqualTo(2);
            assertThat(group.title()).isEqualTo("Original title");
            assertThat(group.latestSender()).isEqualTo("Reviewer");
        });
        assertThat(inbox.groups(ALICE, 1, 1, "", "", "", version).items()).singleElement()
                .extracting(InboxEngine.Group::id).isEqualTo(ISSUE);
        assertThat(inbox.messages(ALICE, PULL, 1, 1, version).items()).singleElement()
                .extracting(Notice::id).isEqualTo("base");
        assertThat(inbox.messages(ALICE, null, 10, 0, version).items()).singleElement()
                .satisfies(notice -> assertThat(notice.url()).isEmpty());
        assertThat(groups.status().unassignedCount()).isEqualTo(1);
    }

    @Test
    void rejectsPagesFromStaleVersionAndPersistsAccountsAcrossRestart() {
        long old = sync(ALICE).version();
        source.set(BOB, List.of(notice(239, 500, ISSUE)));
        sync(BOB);
        source.set(ALICE, notices(0, 241));
        long current = sync(ALICE).version();
        InboxEngine restarted = new InboxEngine(jdbc, source, clock);
        assertThat(restarted.status(ALICE).unreadTotal()).isEqualTo(241);
        assertThat(restarted.status(BOB).unreadTotal()).isEqualTo(1);
        assertThat(restarted.messages(BOB, ISSUE, 10, 0, null).items()).singleElement()
                .extracting(Notice::id).isEqualTo("239");
        EngineException groups = assertThrows(EngineException.class,
                () -> restarted.groups(ALICE, 10, 0, "", "", "", old));
        EngineException messages = assertThrows(EngineException.class,
                () -> restarted.messages(ALICE, PULL, 10, 0, old));
        assertThat(groups.code()).isEqualTo("snapshot_changed");
        assertThat(messages.code()).isEqualTo("snapshot_changed");
        assertThat(groups.details().get("version")).isEqualTo(current);
    }

    @Test
    void emptyAccountDoesNotReadOrWriteAnotherAccountsOverlappingGroupAndNoticeId() {
        sync(ALICE);
        assertThat(inbox.read(BOB, List.of(PULL), false).confirmed()).isZero();
        assertThat(source.writes).isEmpty();
        source.set(BOB, List.of(notice(239, 250, PULL)));
        assertThat(inbox.read(BOB, List.of(PULL), false).confirmedIds()).containsExactly("239");
        assertThat(source.writes).extracting(Write::account).containsExactly(BOB);
        assertThat(inbox.status(ALICE).unreadTotal()).isEqualTo(240);
    }

    @Test
    void previewNeverWritesAndConfirmedAndUncertainIdsSurvivePartialFailure() {
        source.set(ALICE, notices(0, 460));
        InboxEngine.ReadResult preview = inbox.read(ALICE, List.of(PULL, PULL), true);
        assertThat(preview.groupIds()).containsExactly(PULL);
        assertThat(preview.messageIds()).hasSize(460);
        assertThat(preview.confirmed()).isZero();
        assertThat(source.writes).isEmpty();

        source.failOnWrite = 2;
        source.uncertain = true;
        EngineException error = assertThrows(EngineException.class, () -> inbox.read(ALICE, List.of(PULL), false));
        InboxEngine.ReadResult partial = (InboxEngine.ReadResult) error.details().get("result");
        assertThat(partial.confirmed()).isEqualTo(200);
        assertThat(partial.batches()).isEqualTo(1);
        assertThat(error.details().get("confirmed_ids")).isEqualTo(preview.messageIds().subList(0, 200));
        assertThat(error.details().get("uncertain_ids")).isEqualTo(preview.messageIds().subList(200, 400));
        assertThat(error.details().get("failed_ids")).isEqualTo(List.of());
        assertThat(error.details().get("remaining_ids")).isEqualTo(preview.messageIds().subList(400, 460));
        assertThat(inbox.status(ALICE).unreadTotal()).isEqualTo(260);

        InboxEngine restarted = new InboxEngine(jdbc, source, clock);
        InboxEngine.SyncResult reconciliation = restarted.sync(ALICE, false, 900, 60);
        assertThat(reconciliation.mode()).isEqualTo("full");
        assertThat(reconciliation.removed()).isEqualTo(200);
        assertThat(restarted.read(ALICE, List.of(PULL), false).confirmedIds())
                .containsExactlyElementsOf(preview.messageIds().subList(400, 460));
        assertThat(restarted.status(ALICE).unreadTotal()).isZero();
    }

    @Test
    void rejectedWritesIdentifyFailedIdsAndKeepThemUnread() {
        source.failOnWrite = 2;
        EngineException error = assertThrows(EngineException.class, () -> inbox.read(ALICE, List.of(PULL), false));
        assertThat(error.details().get("uncertain_ids")).isEqualTo(List.of());
        assertThat(error.details().get("failed_ids")).isEqualTo(source.writes.getLast().ids());
        assertThat(inbox.status(ALICE).unreadTotal()).isEqualTo(40);
        assertThat(inbox.sync(ALICE, false, 900, 5).mode()).isEqualTo("full");
        assertThat(inbox.status(ALICE).unreadTotal()).isEqualTo(40);
    }

    @Test
    void overlappingOperationsConflictOnlyForTheSameAccount() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        source.pageOverride = (account, page) -> {
            if (account.equals(ALICE)) {
                started.countDown();
                await(release);
            }
            return source.normalPage(account, page);
        };
        try (var executor = Executors.newSingleThreadExecutor()) {
            var pending = executor.submit(() -> sync(ALICE));
            try {
                await(started);
                assertThat(inbox.status(ALICE).busy()).isTrue();
                EngineException busy = assertThrows(EngineException.class,
                        () -> inbox.read(ALICE, List.of(PULL), true));
                assertThat(busy.code()).isEqualTo("busy");
                assertThat(sync(BOB).unreadTotal()).isZero();
                assertThat(inbox.status(BOB).busy()).isFalse();
            } finally {
                release.countDown();
            }
            assertThat(pending.get(5, TimeUnit.SECONDS).unreadTotal()).isEqualTo(240);
        }
    }

    @Test
    void recognizesOnlyCanonicalGitCodeIssueAndPullRequests() {
        assertThat(Target.fromUrl("//gitcode.com/Example/Repo/pull/42/discussions/99").id()).isEqualTo(PULL);
        assertThat(Target.fromUrl("/example/repo/issues/42?filter=x#one").id()).isEqualTo(ISSUE);
        assertThat(Target.fromUrl("https://gitcode.com@elsewhere.test/example/repo/pull/42")).isNull();
        assertThat(Target.fromUrl("https://gitcode.com:443/example/repo/pull/42")).isNull();
        assertThat(Target.fromUrl("/example/%2e%2e/pull/42")).isNull();
        assertThat(Target.fromUrl("/dashboard/messages?user_id=abc")).isNull();
        assertThat(Target.parseGroupId("/" + PULL)).isNull();
        assertThat(Target.parseGroupId("example/repo/pr/042")).isNull();
    }

    private InboxEngine.SyncResult sync(Account account) {
        return inbox.sync(account, false, 900, 0);
    }

    private static Notice notice(int id, int seconds, String group) {
        Target target = Target.parseGroupId(group);
        return new Notice(Integer.toString(id), group, START.plusSeconds(seconds),
                "Message " + id, "author", "open", target.kind().equals("pr") ? "merge_requests" : "issue",
                target.url());
    }

    private static List<Notice> notices(int start, int end) {
        return IntStream.range(start, end).mapToObj(i -> notice(i, i, PULL)).toList();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("expected signal was not received");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private record Write(Account account, List<String> ids) {}

    private static final class Source implements NotificationSource {
        final Map<String, List<Notice>> unread = new ConcurrentHashMap<>();
        final List<Write> writes = new ArrayList<>();
        BiFunction<Account, Integer, Page> pageOverride;
        int failOnWrite;
        boolean uncertain;

        void set(Account account, List<Notice> notices) {
            unread.put(account.id(), notices.stream().sorted(Comparator.comparing(Notice::createdAt).reversed()).toList());
        }

        @Override
        public Page page(Account account, int number) {
            return pageOverride == null ? normalPage(account, number) : pageOverride.apply(account, number);
        }

        Page normalPage(Account account, int number) {
            List<Notice> notices = unread.getOrDefault(account.id(), List.of());
            int size = 50;
            int start = (number - 1) * size;
            return new Page(number, size, (notices.size() + size - 1) / size, notices.size(),
                    notices.subList(start, Math.min(start + size, notices.size())));
        }

        @Override
        public void markRead(Account account, List<String> ids) {
            writes.add(new Write(account, ids));
            boolean failing = failOnWrite == writes.size();
            if (!failing || uncertain) {
                Set<String> selected = Set.copyOf(ids);
                unread.computeIfPresent(account.id(),
                        (id, notices) -> notices.stream().filter(notice -> !selected.contains(notice.id())).toList());
            }
            if (failing) {
                throw new UpstreamException("upstream_error", "fixture mark-read failure", uncertain, 0);
            }
        }
    }

    private static final class TestClock extends Clock {
        Instant now = START;

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(now, zone);
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
