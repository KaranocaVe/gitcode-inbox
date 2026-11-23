package io.github.karanocave.inbox.auth;

import io.github.karanocave.inbox.engine.Account;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/session")
class SessionController {
    private final RequestAuth auth;
    private final IdentityStore store;
    private final AccountLinkStatus links;

    SessionController(RequestAuth auth, IdentityStore store, AccountLinkStatus links) {
        this.auth = auth;
        this.store = store;
        this.links = links;
    }

    @GetMapping
    ResponseEntity<SessionInfo> session(HttpServletRequest request) {
        Account account = auth.sessionAccount(request);
        String error = LoginController.takeError(request);
        boolean authenticated = account != null;
        var info = new SessionInfo(
                authenticated, account, auth.isAdmin(account), auth.demo(),
                authenticated && links.linked(account),
                authenticated ? auth.csrfToken(request) : null,
                auth.demo() ? null : request.getContextPath() + "/oauth/login",
                error
        );
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(info);
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
                .body(store.createKey(account, payload.name()));
    }

    @DeleteMapping("/keys/{id}")
    ResponseEntity<Void> revokeKey(HttpServletRequest request, @PathVariable String id) {
        Account account = auth.requireSession(request);
        store.revokeKey(account, id);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    record SessionInfo(boolean authenticated, Account account, boolean admin, boolean demo, boolean linked,
                       String csrfToken, String loginUrl, String error) {}

    record KeyList(List<IdentityStore.KeyInfo> items) {}

    record NewKeyRequest(String name) {}
}
