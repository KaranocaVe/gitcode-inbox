package io.github.karanocave.inbox.ops;

import io.github.karanocave.inbox.engine.*;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

import static io.github.karanocave.inbox.ops.OpsMetrics.Event.*;

@Service
public class InboxOperations {
    private final InboxEngine engine;
    private final RuntimeSettings settings;
    private final OpsMetrics metrics;

    public InboxOperations(InboxEngine engine, RuntimeSettings settings, OpsMetrics metrics) {
        this.engine = engine;
        this.settings = settings;
        this.metrics = metrics;
    }

    public InboxEngine.SyncResult sync(Account account, boolean full) {
        var options = settings.get();
        long start = System.nanoTime();
        try {
            var result = engine.sync(account, full, options.fullIntervalSeconds(), options.syncDebounceSeconds());
            if (result.mode().equals("debounced")) {
                metrics.record(SYNC_DEBOUNCED);
            } else {
                metrics.record(SYNC_SUCCESS);
                metrics.latency(Duration.ofNanos(System.nanoTime() - start).toMillis());
            }
            return result;
        } catch (EngineException error) {
            if (error.code().equals("busy")) metrics.record(SYNC_DEBOUNCED);
            else metrics.record(SYNC_FAILURE);
            throw error;
        }
    }

    public InboxEngine.ReadResult read(Account account, List<String> ids, boolean dryRun) {
        try {
            var result = engine.read(account, ids, dryRun);
            metrics.record(READ_CONFIRMED, result.confirmed());
            return result;
        } catch (EngineException error) {
            if (error.details().get("result") instanceof InboxEngine.ReadResult result) {
                metrics.record(READ_CONFIRMED, result.confirmed());
            }
            if (error.status() >= 500) metrics.record(READ_FAILURE);
            throw error;
        }
    }
}
