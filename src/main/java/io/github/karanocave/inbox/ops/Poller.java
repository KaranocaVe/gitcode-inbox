package io.github.karanocave.inbox.ops;

import io.github.karanocave.inbox.engine.*;
import io.github.karanocave.inbox.upstream.NotificationClient;
import io.github.karanocave.inbox.upstream.WebSessions;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class Poller {
    private static final Logger LOG = LoggerFactory.getLogger(Poller.class);
    private static final class Plan {
        private volatile Instant lastStarted = Instant.EPOCH;
        private volatile Instant pausedUntil = Instant.EPOCH;
        private volatile boolean active;
    }

    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(8), Thread.ofPlatform().daemon().name("inbox-poll-", 0).factory());
    private final ConcurrentMap<String, Plan> plans = new ConcurrentHashMap<>();
    private final WebSessions sessions;
    private final NotificationClient source;
    private final RuntimeSettings settings;
    private final InboxOperations operations;
    private final Clock clock;

    public Poller(WebSessions sessions, NotificationClient source, RuntimeSettings settings,
                  InboxOperations operations, Clock clock) {
        this.sessions = sessions;
        this.source = source;
        this.settings = settings;
        this.operations = operations;
        this.clock = clock;
    }

    @Scheduled(initialDelay = 200, fixedDelay = 2000)
    void schedule() {
        int interval = settings.get().pollSeconds();
        if (interval == 0) return;
        var accounts = source.demo() ? List.of(new Account("demo", "demo")) : sessions.accounts();
        var byId = accounts.stream().collect(Collectors.toMap(Account::id, Function.identity()));
        plans.keySet().removeIf(id -> !byId.containsKey(id));
        Instant now = clock.instant();
        for (var account : accounts) {
            Plan plan = plans.computeIfAbsent(account.id(), unused -> new Plan());
            if (plan.active || plan.pausedUntil.isAfter(now) || plan.lastStarted.plusSeconds(interval).isAfter(now)) continue;
            plan.active = true;
            try {
                workers.execute(() -> tick(account, plan));
            } catch (RejectedExecutionException fullQueue) {
                plan.active = false;
                return;
            }
        }
    }

    private void tick(Account account, Plan plan) {
        plan.lastStarted = clock.instant();
        try {
            if (settings.get().pollSeconds() != 0) operations.sync(account, false);
        } catch (EngineException error) {
            long pause = error.details().get("retry_after_seconds") instanceof Number n ? n.longValue() : 0;
            if (error.code().equals("session_expired")) pause = Math.max(300, pause);
            if (pause > 0) plan.pausedUntil = clock.instant().plusSeconds(pause);
        } catch (RuntimeException error) {
            LOG.warn("Inbox polling failed.", error);
        } finally {
            plan.active = false;
        }
    }

    public void reset(Account account) { plans.remove(account.id()); }

    @PreDestroy
    void close() { workers.shutdownNow(); }
}
