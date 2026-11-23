package io.github.karanocave.inbox.ops;

import io.github.karanocave.inbox.engine.EngineException;
import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.function.UnaryOperator;

@Service
public class RuntimeSettings {
    public record Values(Integer pollSeconds, Integer fullIntervalSeconds, Integer syncDebounceSeconds,
                         Integer upstreamRequestsPerMinute, Integer apiRequestsPerMinute) {
        public static final Values DEFAULT = new Values(45, 900, 5, 180, 120);

        void validate() {
            if (!(Integer.valueOf(0).equals(pollSeconds) || range(pollSeconds, 15, 3600))
                    || !range(fullIntervalSeconds, 60, 86400) || !range(syncDebounceSeconds, 1, 300)
                    || !range(upstreamRequestsPerMinute, 10, 600) || !range(apiRequestsPerMinute, 10, 1200)) {
                throw new EngineException(400, "invalid_settings", "请核对各项设置的取值范围。", Map.of());
            }
        }

        private static boolean range(Integer value, int min, int max) {
            return value != null && value >= min && value <= max;
        }
    }

    private final JdbcTemplate jdbc;
    private volatile Values current = Values.DEFAULT;

    public RuntimeSettings(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    void init() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS runtime_settings (
              id INTEGER PRIMARY KEY CHECK (id = 1), poll_seconds INTEGER NOT NULL,
              full_interval_seconds INTEGER NOT NULL, sync_debounce_seconds INTEGER NOT NULL,
              upstream_requests_per_minute INTEGER NOT NULL, api_requests_per_minute INTEGER NOT NULL
            )
            """);
        var values = jdbc.query("SELECT * FROM runtime_settings WHERE id = 1", (row, n) -> new Values(
                row.getInt("poll_seconds"), row.getInt("full_interval_seconds"),
                row.getInt("sync_debounce_seconds"), row.getInt("upstream_requests_per_minute"),
                row.getInt("api_requests_per_minute")));
        if (!values.isEmpty()) {
            values.getFirst().validate();
            current = values.getFirst();
        }
    }

    public Values get() {
        return current;
    }

    public synchronized Values patch(UnaryOperator<Values> changes) {
        return replace(changes.apply(current));
    }

    public synchronized Values replace(Values values) {
        values.validate();
        jdbc.update("""
            INSERT INTO runtime_settings VALUES (1, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET poll_seconds=excluded.poll_seconds,
              full_interval_seconds=excluded.full_interval_seconds, sync_debounce_seconds=excluded.sync_debounce_seconds,
              upstream_requests_per_minute=excluded.upstream_requests_per_minute,
              api_requests_per_minute=excluded.api_requests_per_minute
            """, values.pollSeconds(), values.fullIntervalSeconds(), values.syncDebounceSeconds(),
                values.upstreamRequestsPerMinute(), values.apiRequestsPerMinute());
        current = values;
        return values;
    }
}
