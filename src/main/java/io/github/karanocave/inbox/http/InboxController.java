package io.github.karanocave.inbox.http;

import io.github.karanocave.inbox.auth.RequestAuth;
import io.github.karanocave.inbox.engine.*;
import io.github.karanocave.inbox.ops.*;
import io.github.karanocave.inbox.upstream.WebSessions;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class InboxController {
    private final RequestAuth auth;
    private final TrafficGate gate;
    private final InboxEngine engine;
    private final InboxService service;
    private final WebSessions sessions;
    private final Poller poller;
    private final RuntimeSettings settings;

    public InboxController(RequestAuth auth, TrafficGate gate, InboxEngine engine, InboxService service,
                           WebSessions sessions, Poller poller, RuntimeSettings settings) {
        this.auth = auth;
        this.gate = gate;
        this.engine = engine;
        this.service = service;
        this.sessions = sessions;
        this.poller = poller;
        this.settings = settings;
    }

    @GetMapping("/status")
    Map<String, Object> status(HttpServletRequest request) {
        return service.status(inboxAccount(request));
    }

    @GetMapping("/groups")
    InboxEngine.Groups groups(HttpServletRequest request,
                              @RequestParam(defaultValue = "50") int limit,
                              @RequestParam(defaultValue = "0") int offset,
                              @RequestParam(defaultValue = "") String kind,
                              @RequestParam(defaultValue = "") String repo,
                              @RequestParam(defaultValue = "") String q,
                              @RequestParam(required = false) Long version) {
        return engine.groups(inboxAccount(request), limit, offset, kind, repo, q, version);
    }

    @GetMapping("/groups/{owner}/{repo}/{kind}/{number}/messages")
    InboxEngine.Messages messages(HttpServletRequest request, @PathVariable String owner, @PathVariable String repo,
                                  @PathVariable String kind, @PathVariable String number,
                                  @RequestParam(defaultValue = "50") int limit,
                                  @RequestParam(defaultValue = "0") int offset,
                                  @RequestParam(required = false) Long version) {
        Account account = inboxAccount(request);
        return engine.messages(account, String.join("/", owner, repo, kind, number), limit, offset, version);
    }

    @GetMapping("/unassigned")
    InboxEngine.Messages unassigned(HttpServletRequest request,
                                    @RequestParam(defaultValue = "50") int limit,
                                    @RequestParam(defaultValue = "0") int offset,
                                    @RequestParam(required = false) Long version) {
        return engine.messages(inboxAccount(request), null, limit, offset, version);
    }

    public record SyncRequest(String mode) {}

    @PostMapping("/sync")
    InboxEngine.SyncResult sync(HttpServletRequest request, @RequestBody(required = false) SyncRequest update) {
        Account account = inboxAccount(request);
        String mode = update == null || update.mode() == null ? "incremental" : update.mode();
        if (!mode.equals("incremental") && !mode.equals("full")) {
            throw error(400, "invalid_mode", "同步模式应为 incremental 或 full。");
        }
        return service.sync(account, mode.equals("full"));
    }

    public record ReadRequest(List<String> groupIds, Boolean read, Boolean dryRun) {}

    @PutMapping("/groups/read-state")
    InboxEngine.ReadResult read(HttpServletRequest request, @RequestBody ReadRequest update) {
        Account account = inboxAccount(request);
        if (!Boolean.TRUE.equals(update.read())) throw error(400, "invalid_state", "已读状态应设为 true。");
        return service.read(account, update.groupIds(), Boolean.TRUE.equals(update.dryRun()));
    }

    public record WebCredentials(String accessToken, String refreshToken) {
        @Override public String toString() { return "[GitCode credentials]"; }
    }

    @PutMapping("/session/web")
    Map<String, Boolean> connect(HttpServletRequest request, @RequestBody WebCredentials credentials) {
        Account account = auth.requireSession(request);
        gate.api(account);
        if (auth.demo()) throw error(400, "demo_mode", "演示模式使用示例通知。");
        sessions.link(account, credentials.accessToken(), credentials.refreshToken());
        poller.reset(account);
        return Map.of("linked", true);
    }

    @DeleteMapping("/session/web")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void disconnect(HttpServletRequest request) {
        Account account = auth.requireSession(request);
        gate.api(account);
        sessions.unlink(account);
        poller.reset(account);
    }

    @GetMapping("/admin/settings")
    RuntimeSettings.Values settings(HttpServletRequest request) {
        adminAccount(request);
        return settings.get();
    }

    @PutMapping("/admin/settings")
    RuntimeSettings.Values saveSettings(HttpServletRequest request, @RequestBody RuntimeSettings.Values values) {
        adminAccount(request);
        return settings.replace(values);
    }

    @GetMapping("/admin/metrics")
    Map<String, Object> metrics(HttpServletRequest request, @RequestParam(defaultValue = "24") int hours) {
        return service.metrics(adminAccount(request), hours);
    }

    private Account inboxAccount(HttpServletRequest request) {
        Account account = auth.requireInbox(request);
        gate.api(account);
        return account;
    }

    private Account adminAccount(HttpServletRequest request) {
        Account account = auth.requireAdmin(request);
        gate.api(account);
        return account;
    }

    private static EngineException error(int status, String code, String message) {
        return new EngineException(status, code, message, Map.of());
    }
}
