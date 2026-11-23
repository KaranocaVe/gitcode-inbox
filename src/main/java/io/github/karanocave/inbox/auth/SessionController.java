package io.github.karanocave.inbox.auth;

import io.github.karanocave.inbox.engine.Account;
import io.github.karanocave.inbox.engine.EngineException;
import io.github.karanocave.inbox.ops.TrafficGate;
import io.github.karanocave.inbox.upstream.GitCodeWeb;
import io.github.karanocave.inbox.upstream.WebSessions;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/session")
class SessionController {
    private final RequestAuth auth;
    private final IdentityStore store;
    private final GitCodeWeb gitcode;
    private final WebSessions webSessions;
    private final TrafficGate gate;

    SessionController(RequestAuth auth, IdentityStore store, GitCodeWeb gitcode, WebSessions webSessions,
                      TrafficGate gate) {
        this.auth = auth;
        this.store = store;
        this.gitcode = gitcode;
        this.webSessions = webSessions;
        this.gate = gate;
    }

    @GetMapping
    ResponseEntity<SessionInfo> session(HttpServletRequest request) {
        Account account = auth.sessionAccount(request);
        boolean fixedAccount = auth.fixedAccountMode();
        String error = fixedAccount ? null : LoginController.takeError(request);
        boolean authenticated = account != null;
        var info = new SessionInfo(
                authenticated, account, auth.isAdmin(account), auth.demo(), auth.passwordLogin(), fixedAccount,
                authenticated && webSessions.linked(account),
                !fixedAccount && (authenticated || auth.passwordLogin()) ? auth.csrfToken(request) : null,
                auth.oauthLogin() ? request.getContextPath() + "/oauth/login" : null,
                error
        );
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(info);
    }

    @PostMapping(value = "/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Void> login(HttpServletRequest request, @RequestBody LoginRequest payload) {
        auth.requireInteractiveSession(request);
        auth.requireBrowser(request);
        if (!auth.passwordLogin()) {
            throw new EngineException(404, "password_login_disabled", "GitCode 密码登录未启用。", Map.of());
        }
        gate.login(request.getRemoteAddr());
        auth.verifyCsrf(request);
        if (payload.username() == null || payload.username().isBlank()
                || payload.password() == null || payload.password().isEmpty()) {
            throw new EngineException(400, "invalid_credentials", "请输入 GitCode 账号和密码。", Map.of());
        }
        var credentials = gitcode.passwordLogin(payload.username().strip(), payload.password());
        var identity = gitcode.identity(credentials.access());
        if (!auth.allowsAccount(identity.id())) {
            throw new EngineException(401, "gitcode_login_rejected", "GitCode 未接受此次登录。", Map.of());
        }
        var account = new Account(identity.id(), identity.username());
        webSessions.linkVerified(account, identity, credentials);
        auth.signIn(request, account);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(HttpServletRequest request) {
        auth.requireSession(request);
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @GetMapping("/keys")
    ResponseEntity<KeyList> keys(HttpServletRequest request) {
        Account account = auth.requireSession(request);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new KeyList(store.listKeys(account)));
    }

    @PostMapping("/keys")
    ResponseEntity<IdentityStore.NewKey> createKey(HttpServletRequest request, @RequestBody NewKeyRequest payload) {
        Account account = auth.requireSession(request);
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(store.createKey(account, payload.name(), payload.scope()));
    }

    @DeleteMapping("/keys/{id}")
    ResponseEntity<Void> revokeKey(HttpServletRequest request, @PathVariable String id) {
        Account account = auth.requireSession(request);
        store.revokeKey(account, id);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    record SessionInfo(boolean authenticated, Account account, boolean admin, boolean demo, boolean passwordLogin,
                       boolean fixedAccount, boolean linked, String csrfToken, String loginUrl, String error) {}

    record KeyList(List<IdentityStore.KeyInfo> items) {}

    record NewKeyRequest(String name, String scope) {}

    record LoginRequest(String username, String password) {
        @Override
        public String toString() {
            return "[GitCode login credentials]";
        }
    }
}
