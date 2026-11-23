package io.github.karanocave.inbox.ops;

import io.github.karanocave.inbox.engine.Account;
import io.github.karanocave.inbox.engine.EngineException;
import io.github.karanocave.inbox.engine.UpstreamException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

import static io.github.karanocave.inbox.ops.OpsMetrics.Event.*;

@Component
public class TrafficGate {
    private static final long WINDOW = 60_000;
    private static final long MAX_RETRY = 24 * 3600;

    private static class Window {
        private final ArrayDeque<Long> accepted = new ArrayDeque<>();

        void expire(long now) {
            while (!accepted.isEmpty() && now - accepted.getFirst() >= WINDOW) accepted.removeFirst();
        }

        long accept(long now, int limit) {
            expire(now);
            if (accepted.size() < limit) {
                accepted.addLast(now);
                return 0;
            }
            return Math.max(1, accepted.getFirst() + WINDOW - now);
        }
    }

    private final Window upstream = new Window();
    private final Map<String, Window> apiWindows = new HashMap<>();
    private final RuntimeSettings settings;
    private final OpsMetrics metrics;
    private final Clock clock;
    private Instant upstreamPause = Instant.EPOCH;

    public TrafficGate(RuntimeSettings settings, OpsMetrics metrics, Clock clock) {
        this.settings = settings;
        this.metrics = metrics;
        this.clock = clock;
    }

    public void api(Account account) {
        local("account:" + account.id(), settings.get().apiRequestsPerMinute());
    }

    public void login(String ip) {
        local("login:" + ip, 20);
    }

    private void local(String key, int limit) {
        long wait;
        synchronized (apiWindows) {
            wait = apiWindows.computeIfAbsent(key, unused -> new Window()).accept(clock.millis(), limit);
        }
        if (wait > 0) {
            metrics.record(LOCAL_RATE_LIMITED);
            throw new EngineException(429, "rate_limited", "操作太频繁，请稍后重试。",
                    Map.of("retry_after_seconds", (wait + 999) / 1000));
        }
    }

    @Scheduled(fixedDelay = WINDOW)
    void prune() {
        long now = clock.millis();
        synchronized (apiWindows) {
            apiWindows.values().removeIf(window -> {
                window.expire(now);
                return window.accepted.isEmpty();
            });
        }
    }

    public void beforeUpstream() {
        // Pace requests across windows so a complete scan can exceed one minute's quota.
        long deadline = System.nanoTime() + Duration.ofSeconds(65).toNanos();
        while (true) {
            long wait;
            synchronized (upstream) {
                long paused = Duration.between(clock.instant(), upstreamPause).toMillis();
                if (paused > 0) {
                    throw new UpstreamException("upstream_rate_limited", "GitCode 正在限流，请稍后重试。",
                            false, (paused + 999) / 1000);
                }
                wait = upstream.accept(clock.millis(), settings.get().upstreamRequestsPerMinute());
            }
            if (wait == 0) break;
            if (System.nanoTime() >= deadline) {
                throw new UpstreamException("upstream_rate_limited", "上游请求配额暂时耗尽。", false,
                        (wait + 999) / 1000);
            }
            try {
                Thread.sleep(Math.min(wait, 1000));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new UpstreamException("interrupted", "同步已中止。", false, 0);
            }
        }
        metrics.record(UPSTREAM_REQUESTS);
    }

    public long pauseAfter429(String value) {
        long seconds = retryAfter(value);
        synchronized (upstream) {
            Instant proposed = clock.instant().plusSeconds(seconds);
            if (proposed.isAfter(upstreamPause)) upstreamPause = proposed;
        }
        metrics.record(UPSTREAM_RATE_LIMITED);
        return seconds;
    }

    private long retryAfter(String value) {
        if (value != null && !value.isBlank()) {
            try {
                return Math.clamp(Long.parseLong(value.trim()), 1, MAX_RETRY);
            } catch (NumberFormatException ignored) {
                try {
                    return Math.clamp(Duration.between(clock.instant(),
                            ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).toSeconds(),
                            1, MAX_RETRY);
                } catch (RuntimeException invalidDate) {
                    return 60;
                }
            }
        }
        return 60;
    }
}
