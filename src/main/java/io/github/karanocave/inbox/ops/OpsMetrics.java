package io.github.karanocave.inbox.ops;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.*;

@Service
public class OpsMetrics {
    public enum Event {
        SYNC_SUCCESS, SYNC_FAILURE, SYNC_DEBOUNCED, UPSTREAM_REQUESTS, UPSTREAM_RATE_LIMITED,
        LOCAL_RATE_LIMITED, READ_CONFIRMED, READ_FAILURE;

        String key() { return name().toLowerCase(Locale.ROOT); }
    }

    private static final long HOUR = 3600;
    private static final long RETENTION = 31 * 24 * HOUR;
    private static final int[] LATENCY_LIMITS = {25, 50, 100, 200, 400, 750, 1500, 3000, 6000, 12000,
            30000, 60000, 120000, 300000, 900000, 3600000};
    private record Slot(long hour, String event) {}

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final TransactionTemplate transactions;
    private final Map<Slot, Long> pending = new HashMap<>();

    public OpsMetrics(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.transactions = new TransactionTemplate(new JdbcTransactionManager(jdbc.getDataSource()));
    }

    @PostConstruct
    void init() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS ops_metrics (
              hour INTEGER NOT NULL, event TEXT NOT NULL, value INTEGER NOT NULL,
              PRIMARY KEY (hour, event)
            )
            """);
    }

    public void record(Event event) { record(event, 1); }

    public synchronized void record(Event event, long count) {
        if (count > 0) pending.merge(new Slot(hour(), event.key()), count, Long::sum);
    }

    public synchronized void latency(long millis) {
        int band = 0;
        while (band + 1 < LATENCY_LIMITS.length && LATENCY_LIMITS[band] < millis) band++;
        pending.merge(new Slot(hour(), "latency_" + band), 1L, Long::sum);
    }

    private long hour() { return clock.instant().getEpochSecond() / HOUR * HOUR; }

    @Scheduled(fixedDelay = 5000)
    @PreDestroy
    public synchronized void flush() {
        if (pending.isEmpty()) return;
        transactions.executeWithoutResult(status -> jdbc.batchUpdate("""
            INSERT INTO ops_metrics VALUES (?, ?, ?)
            ON CONFLICT(hour, event) DO UPDATE SET value=value + excluded.value
            """, pending.entrySet().stream().map(entry -> new Object[] {
                entry.getKey().hour(), entry.getKey().event(), entry.getValue()
        }).toList()));
        pending.clear();
    }

    @Scheduled(initialDelay = 180000, fixedDelay = 3600000)
    void prune() {
        jdbc.update("DELETE FROM ops_metrics WHERE hour < ?", hour() - RETENTION);
    }

    public synchronized Map<String, Object> view(int hours) {
        flush();
        long firstHour = hour() - (hours - 1L) * HOUR;
        var series = new ArrayList<Map<String, Object>>(hours);
        var totals = new LinkedHashMap<String, Long>();
        for (var event : Event.values()) totals.put(event.key(), 0L);
        for (int index = 0; index < hours; index++) {
            var point = new LinkedHashMap<String, Object>();
            point.put("bucket", Instant.ofEpochSecond(firstHour + index * HOUR));
            point.putAll(totals);
            series.add(point);
        }
        long[] latency = new long[LATENCY_LIMITS.length];
        jdbc.query("SELECT hour, event, value FROM ops_metrics WHERE hour BETWEEN ? AND ?", row -> {
            var event = row.getString("event");
            long count = row.getLong("value");
            if (event.startsWith("latency_")) {
                latency[Integer.parseInt(event.substring(8))] += count;
            } else if (totals.containsKey(event)) {
                var point = series.get((int) ((row.getLong("hour") - firstHour) / HOUR));
                point.put(event, count);
                totals.merge(event, count, Long::sum);
            }
        }, firstHour, firstHour + (hours - 1L) * HOUR);
        var percentiles = new LinkedHashMap<String, Integer>();
        percentiles.put("p50", percentile(latency, .50));
        percentiles.put("p95", percentile(latency, .95));
        return Map.of("hours", hours, "totals", totals, "series", series, "sync_latency_ms", percentiles);
    }

    private Integer percentile(long[] latency, double percentile) {
        long count = Arrays.stream(latency).sum();
        if (count == 0) return null;
        long rank = (long) Math.ceil(count * percentile);
        for (int index = 0; index < latency.length; index++) {
            rank -= latency[index];
            if (rank <= 0) return LATENCY_LIMITS[index];
        }
        throw new IllegalStateException("latency histogram");
    }
}
