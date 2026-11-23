package io.github.karanocave.inbox.ops;

import io.github.karanocave.inbox.engine.Account;
import io.github.karanocave.inbox.engine.EngineException;
import io.github.karanocave.inbox.engine.UpstreamException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.time.*;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OperationsTest {
    @TempDir Path temporary;
    private JdbcTemplate jdbc;
    private RuntimeSettings settings;
    private OpsMetrics metrics;
    private TrafficGate gate;
    private final MutableClock clock = new MutableClock();

    @BeforeEach
    void initialize() {
        var sqlite = new SQLiteDataSource();
        sqlite.setUrl("jdbc:sqlite:" + temporary.resolve("ops.sqlite"));
        jdbc = new JdbcTemplate(sqlite);
        settings = new RuntimeSettings(jdbc);
        settings.init();
        metrics = new OpsMetrics(jdbc, clock);
        metrics.init();
        gate = new TrafficGate(settings, metrics, clock);
    }

    @Test
    void liveQuotaChangesAndRollingWindowAreAccountScoped() {
        var alice = new Account("first", "alice");
        var bob = new Account("second", "bob");
        settings.replace(new RuntimeSettings.Values(15, 60, 1, 60, 10));
        for (int i = 0; i < 10; i++) gate.api(alice);
        var rejected = assertThrows(EngineException.class, () -> gate.api(alice));
        assertEquals(429, rejected.status());
        assertEquals(60L, rejected.details().get("retry_after_seconds"));
        gate.api(bob);
        clock.now = clock.now.plusSeconds(59);
        assertEquals(1L, assertThrows(EngineException.class, () -> gate.api(alice))
                .details().get("retry_after_seconds"));
        clock.now = clock.now.plusSeconds(1);
        gate.api(alice);
        var reloaded = new RuntimeSettings(jdbc);
        reloaded.init();
        assertEquals(10, reloaded.get().apiRequestsPerMinute());
        assertThrows(EngineException.class, () -> reloaded.replace(new RuntimeSettings.Values(14, 60, 1, 60, 10)));
    }

    @Test
    void upstreamHonorsRetryAfterAndMetricsAreDurable() {
        gate.beforeUpstream();
        assertEquals(12L, gate.pauseAfter429("12"));
        assertEquals(12L, assertThrows(UpstreamException.class, gate::beforeUpstream).retryAfterSeconds());
        clock.now = clock.now.plusSeconds(12);
        gate.beforeUpstream();
        assertEquals(60L, gate.pauseAfter429("invalid"));
        metrics.latency(60);
        metrics.latency(2200);
        metrics.flush();

        var restarted = new OpsMetrics(jdbc, clock);
        restarted.init();
        var report = restarted.view(2);
        var totals = (Map<?, ?>) report.get("totals");
        assertEquals(2L, totals.get("upstream_requests"));
        assertEquals(2L, totals.get("upstream_rate_limited"));
        assertEquals(Map.of("p50", 100, "p95", 3000), report.get("sync_latency_ms"));
        assertEquals(2, ((List<?>) report.get("series")).size());
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-11-23T12:30:00Z");

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
        @Override public Instant instant() { return now; }
    }
}
