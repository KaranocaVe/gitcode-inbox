package io.github.karanocave.inbox.ops;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.karanocave.inbox.engine.Account;
import io.github.karanocave.inbox.engine.EngineException;
import io.github.karanocave.inbox.engine.InboxEngine;
import io.github.karanocave.inbox.upstream.WebSessions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class InboxService {
    private final InboxEngine engine;
    private final InboxOperations operations;
    private final WebSessions sessions;
    private final OpsMetrics metrics;
    private final ObjectMapper json;
    private final boolean demo;
    private final boolean dryRun;

    public InboxService(InboxEngine engine, InboxOperations operations, WebSessions sessions, OpsMetrics metrics,
                        ObjectMapper json, @Value("${INBOX_MODE:production}") String mode,
                        @Value("${INBOX_DRY_RUN:false}") boolean dryRun) {
        this.engine = engine;
        this.operations = operations;
        this.sessions = sessions;
        this.metrics = metrics;
        this.json = json;
        this.demo = mode.equals("demo");
        this.dryRun = dryRun;
    }

    public Map<String, Object> status(Account account) {
        var state = json.convertValue(engine.status(account), new TypeReference<Map<String, Object>>() {});
        state.put("demo", demo);
        state.put("dry_run", dryRun);
        state.put("linked", linked(account));
        return state;
    }

    public InboxEngine.SyncResult sync(Account account, boolean full) {
        requireLink(account);
        return operations.sync(account, full);
    }

    public InboxEngine.ReadResult read(Account account, List<String> groups, boolean preview) {
        requireLink(account);
        return operations.read(account, groups, dryRun || preview);
    }

    public Map<String, Object> metrics(Account account, int hours) {
        if (hours < 1 || hours > 720) {
            throw new EngineException(400, "invalid_window", "统计窗口应为 1 到 720 小时。", Map.of());
        }
        var report = new LinkedHashMap<>(metrics.view(hours));
        var snapshot = engine.status(account);
        report.put("current", Map.of("unread_total", snapshot.unreadTotal(), "unassigned_count", snapshot.unassignedCount()));
        return report;
    }

    private boolean linked(Account account) { return demo || sessions.linked(account); }

    private void requireLink(Account account) {
        if (!linked(account)) {
            throw new EngineException(409, "web_session_required", "请先连接 GitCode Web 会话。", Map.of());
        }
    }
}
