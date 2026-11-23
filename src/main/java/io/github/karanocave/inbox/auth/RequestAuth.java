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
        if (request.getHeader(HttpHeaders.AUTHORIZATION) == null) {
            return requireSession(request);
        }
        return requireKey(request).account();
    }

    public record McpIdentity(Account account, boolean admin) {}

    public McpIdentity requireMcp(HttpServletRequest request) {
        requireTransport(request);
        String origin = request.getHeader(HttpHeaders.ORIGIN);
        if (origin != null && !origin.equals(settings.publicOrigin)) {
            throw new EngineException(403, "invalid_origin", "Origin is not allowed", Map.of());
        }
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
        Account account = requireSession(request);
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

    boolean oauthLogin() {
        return settings.oauthLogin();
    }

    boolean passwordLogin() {
        return settings.passwordLogin;
    }

    boolean allowsAccount(String id) {
        return settings.allowsAccount(id);
    }

    void requireTransport(HttpServletRequest request) {
        if (!demo() && !request.isSecure()) {
            throw new EngineException(403, "https_required", "HTTPS is required", Map.of());
        }
    }

    Account sessionAccount(HttpServletRequest request) {
        requireBrowser(request);
        if (demo()) {
            return AuthSettings.DEMO_ACCOUNT;
        }
        HttpSession session = request.getSession(false);
        String id = session == null ? null : (String) session.getAttribute(ID);
        return id == null || !settings.allowsAccount(id) ? null : store.findAccount(id);
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
