package io.github.karanocave.inbox.auth;

import io.github.karanocave.inbox.engine.Account;
import io.github.karanocave.inbox.engine.EngineException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.WebUtils;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;

@RestController
class LoginController {
    private static final String PENDING_LOGIN = LoginController.class.getName() + ".pending";
    private static final String LOGIN_ERROR = LoginController.class.getName() + ".error";
    private static final Duration STATE_LIFETIME = Duration.ofMinutes(10);
    private static final URI HOME = URI.create("/");

    private final RequestAuth auth;
    private final GitCodeOAuth oauth;

    LoginController(RequestAuth auth, GitCodeOAuth oauth) {
        this.auth = auth;
        this.oauth = oauth;
    }

    @GetMapping("/oauth/login")
    ResponseEntity<Void> login(HttpServletRequest request) {
        auth.requireTransport(request);
        if (auth.demo()) {
            return redirect(HOME);
        }
        HttpSession session = request.getSession();
        String state = SecretValues.generate();
        synchronized (WebUtils.getSessionMutex(session)) {
            session.removeAttribute(LOGIN_ERROR);
            session.setAttribute(PENDING_LOGIN, new PendingLogin(state, Instant.now().plus(STATE_LIFETIME)));
        }
        return redirect(oauth.authorizationUri(state));
    }

    @GetMapping("/oauth/callback")
    ResponseEntity<Void> callback(
            HttpServletRequest request,
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String error
    ) {
        auth.requireTransport(request);
        if (auth.demo()) {
            return redirect(HOME);
        }
        HttpSession session = request.getSession();
        if (!consumeState(session, state)) {
            session.setAttribute(LOGIN_ERROR, "The GitCode sign-in request expired or could not be verified. Try again.");
            return redirect(HOME);
        }
        if (error != null || code == null || code.isBlank()) {
            session.setAttribute(LOGIN_ERROR, "GitCode sign-in was not completed. Try again.");
            return redirect(HOME);
        }
        Account account;
        try {
            account = oauth.exchange(code);
        } catch (EngineException exception) {
            session.setAttribute(LOGIN_ERROR, exception.getMessage());
            return redirect(HOME);
        }
        auth.signIn(request, account);
        session.removeAttribute(LOGIN_ERROR);
        return redirect(HOME);
    }

    static String takeError(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return null;
        }
        synchronized (WebUtils.getSessionMutex(session)) {
            String error = (String) session.getAttribute(LOGIN_ERROR);
            session.removeAttribute(LOGIN_ERROR);
            return error;
        }
    }

    private static boolean consumeState(HttpSession session, String state) {
        synchronized (WebUtils.getSessionMutex(session)) {
            var pending = (PendingLogin) session.getAttribute(PENDING_LOGIN);
            if (pending == null || !SecretValues.matches(pending.state(), state)) {
                return false;
            }
            session.removeAttribute(PENDING_LOGIN);
            return Instant.now().isBefore(pending.expiresAt());
        }
    }

    private static ResponseEntity<Void> redirect(URI target) {
        return ResponseEntity.status(HttpStatus.SEE_OTHER)
                .cacheControl(CacheControl.noStore())
                .header("Referrer-Policy", "no-referrer")
                .location(target)
                .build();
    }

    private record PendingLogin(String state, Instant expiresAt) {}
}
