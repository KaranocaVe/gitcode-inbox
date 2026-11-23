package io.github.karanocave.inbox.auth;

import io.github.karanocave.inbox.engine.Account;
import io.github.karanocave.inbox.engine.EngineException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.util.WebUtils;

import java.util.Map;
import java.util.Set;

@Component
public final class RequestAuth {
    private static final String ID = RequestAuth.class.getName() + ".id";
    private static final String CSRF = RequestAuth.class.getName() + ".csrf";
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    private final AuthSettings settings;
    private final IdentityStore store;

    RequestAuth(AuthSettings settings, IdentityStore store) {
        this.settings = settings;
        this.store = store;
    }

    public Account requireInbox(HttpServletRequest request) {
        requireTransport(request);
        if (fixedAccountMode()) {
            return fixedAccount();
        }
        if (request.getHeader(HttpHeaders.AUTHORIZATION) == null) {
            return requireSession(request);
        }
        return requireKey(request).account();
    }

    public record McpIdentity(Account account, boolean admin) {}

    public McpIdentity requireMcp(HttpServletRequest request) {
        requireTransport(request);
        if (fixedAccountMode()) {
            return new McpIdentity(fixedAccount(), true);
        }
        requireOrigin(request);
        var key = requireKey(request);
        return new McpIdentity(key.account(), IdentityStore.MCP_ADMIN_SCOPE.equals(key.scope())
                && isAdmin(key.account()));
    }

    private IdentityStore.AuthenticatedKey requireKey(HttpServletRequest request) {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization != null && authorization.length() > 7
                && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            var key = store.authenticateKey(authorization.substring(7));
            if (key != null && settings.allowsAccount(key.account().id())) {
                return key;
            }
        }
        throw new EngineException(401, "invalid_api_key", "A valid inbox API key is required", Map.of());
    }

    public Account requireSession(HttpServletRequest request) {
        requireInteractiveSession(request);
        Account account = sessionAccount(request);
        if (account == null) {
            throw new EngineException(401, "sign_in_required", "Sign in to GitCode to continue", Map.of());
        }
        if (!SAFE_METHODS.contains(request.getMethod())) {
            verifyCsrf(request);
        }
        return account;
    }

    public Account requireAdmin(HttpServletRequest request) {
        Account account = fixedAccountMode() ? sessionAccount(request) : requireSession(request);
        if (!isAdmin(account)) {
            throw new EngineException(403, "admin_required", "An administrator account is required", Map.of());
        }
        return account;
    }

    public boolean isAdmin(Account account) {
        return account != null && settings.isAdmin(account);
    }

    public boolean demo() {
        return settings.demo;
    }

    public boolean fixedAccountMode() {
        return settings.fixedAccountMode;
    }

    boolean oauthLogin() {
        return settings.oauthLogin();
    }

    boolean passwordLogin() {
        return settings.passwordLogin;
    }

    public boolean allowsAccount(String id) {
        return settings.allowsAccount(id);
    }

    void requireTransport(HttpServletRequest request) {
        if (!demo() && !request.isSecure()) {
            throw new EngineException(403, "https_required", "HTTPS is required", Map.of());
        }
        if (fixedAccountMode()) {
            requireOrigin(request);
        }
    }

    void requireInteractiveSession(HttpServletRequest request) {
        if (fixedAccountMode()) {
            requireTransport(request);
            throw new EngineException(404, "fixed_account_mode", "固定账号模式无需登录会话。", Map.of());
        }
    }

    Account sessionAccount(HttpServletRequest request) {
        if (fixedAccountMode()) {
            requireTransport(request);
            return fixedAccount();
        }
        requireBrowser(request);
        if (demo()) {
            return AuthSettings.DEMO_ACCOUNT;
        }
        HttpSession session = request.getSession(false);
        String id = session == null ? null : (String) session.getAttribute(ID);
        return id == null || !settings.allowsAccount(id) ? null : store.findAccount(id);
    }

    private Account fixedAccount() {
        Account account = store.findAccount(settings.fixedAccountId);
        if (account == null) {
            throw new EngineException(503, "fixed_account_unavailable", "收件箱的固定账号不可用。", Map.of());
        }
        return account;
    }

    private void requireOrigin(HttpServletRequest request) {
        var origins = request.getHeaders(HttpHeaders.ORIGIN);
        if (origins.hasMoreElements()
                && (!origins.nextElement().equals(settings.publicOrigin) || origins.hasMoreElements())) {
            throw new EngineException(403, "invalid_origin", "Origin is not allowed", Map.of());
        }
    }

    void requireBrowser(HttpServletRequest request) {
        requireTransport(request);
        if (request.getHeader(HttpHeaders.AUTHORIZATION) != null) {
            throw new EngineException(403, "session_required", "This endpoint requires a browser session", Map.of());
        }
    }

    void verifyCsrf(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        String expected = session == null ? null : (String) session.getAttribute(CSRF);
        if (!SecretValues.matches(expected, request.getHeader("X-CSRF-Token"))) {
            throw new EngineException(403, "invalid_csrf_token", "Refresh the page and try again", Map.of());
        }
    }

    String csrfToken(HttpServletRequest request) {
        HttpSession session = request.getSession();
        synchronized (WebUtils.getSessionMutex(session)) {
            String token = (String) session.getAttribute(CSRF);
            if (token == null) {
                token = SecretValues.generate();
                session.setAttribute(CSRF, token);
            }
            return token;
        }
    }

    void signIn(HttpServletRequest request, Account account) {
        store.saveAccount(account);
        HttpSession session = request.getSession(false);
        request.changeSessionId();
        session.setAttribute(ID, account.id());
        session.setAttribute(CSRF, SecretValues.generate());
    }
}
