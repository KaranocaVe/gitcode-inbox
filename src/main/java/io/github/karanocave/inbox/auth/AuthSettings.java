package io.github.karanocave.inbox.auth;

import io.github.karanocave.inbox.engine.Account;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
final class AuthSettings {
    static final Account DEMO_ACCOUNT = new Account("demo", "demo");
    private static final Pattern ACCESS_PREFIX = Pattern.compile("/[A-Za-z0-9]{4,32}/[A-Za-z0-9]{4,32}");
    private static final Pattern FIXED_ACCESS_PREFIX = Pattern.compile("/[A-Za-z0-9]{4}/[A-Za-z0-9]{4}");

    final boolean demo;
    final boolean passwordLogin;
    final boolean fixedAccountMode;
    final String fixedAccountId;
    final String clientId;
    final String clientSecret;
    final String callbackUrl;
    final String publicOrigin;
    private final String passwordAccountId;
    private final Set<String> adminUsers;

    AuthSettings(
            @Value("${INBOX_MODE:production}") String mode,
            @Value("${INBOX_PUBLIC_URL:}") String publicUrl,
            @Value("${GITCODE_CLIENT_ID:}") String clientId,
            @Value("${GITCODE_CLIENT_SECRET:}") String clientSecret,
            @Value("${INBOX_ADMIN_USERS:}") String adminUsers,
            @Value("${server.servlet.context-path:}") String accessPrefix,
            @Value("${INBOX_AUTH_MODE:oauth}") String authMode,
            @Value("${INBOX_PASSWORD_ACCOUNT:}") String passwordAccountId,
            @Value("${INBOX_FIXED_ACCOUNT_ID:}") String fixedAccountId
    ) {
        if (!mode.equals("production") && !mode.equals("demo")) {
            throw new IllegalArgumentException("INBOX_MODE must be production or demo");
        }
        demo = mode.equals("demo");
        if (!authMode.equals("oauth") && !authMode.equals("password") && !authMode.equals("fixed")) {
            throw new IllegalArgumentException("INBOX_AUTH_MODE must be oauth, password, or fixed");
        }
        passwordLogin = !demo && authMode.equals("password");
        fixedAccountMode = !demo && authMode.equals("fixed");
        this.fixedAccountId = fixedAccountId.strip();
        this.passwordAccountId = passwordAccountId.strip();
        if (passwordLogin && (this.passwordAccountId.isEmpty() || this.passwordAccountId.equals(DEMO_ACCOUNT.id()))) {
            throw new IllegalArgumentException("INBOX_PASSWORD_ACCOUNT must be a GitCode Web account ID in password mode");
        }
        if (oauthLogin() && (clientId.isBlank() || clientSecret.isBlank())) {
            throw new IllegalArgumentException("GITCODE_CLIENT_ID and GITCODE_CLIENT_SECRET are required in production");
        }

        if (fixedAccountMode) {
            if (!FIXED_ACCESS_PREFIX.matcher(accessPrefix).matches()) {
                throw new IllegalArgumentException("INBOX_ACCESS_PREFIX must have two four-character alphanumeric segments in fixed mode");
            }
            if (this.fixedAccountId.isEmpty() || this.fixedAccountId.equals(DEMO_ACCOUNT.id())) {
                throw new IllegalArgumentException("INBOX_FIXED_ACCOUNT_ID must identify a persisted GitCode account in fixed mode");
            }
        }
        if (!accessPrefix.isEmpty() && !ACCESS_PREFIX.matcher(accessPrefix).matches()) {
            throw new IllegalArgumentException("INBOX_ACCESS_PREFIX must have two alphanumeric segments of 4–32 characters");
        }
        publicOrigin = demo ? null : publicOrigin(publicUrl);
        callbackUrl = oauthLogin() ? publicOrigin + accessPrefix + "/oauth/callback" : null;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.adminUsers = Arrays.stream(adminUsers.split(","))
                .map(String::strip)
                .filter(value -> !value.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    boolean oauthLogin() {
        return !demo && !passwordLogin && !fixedAccountMode;
    }

    boolean allowsAccount(String id) {
        if (demo) {
            return DEMO_ACCOUNT.id().equals(id);
        }
        if (fixedAccountMode) {
            return fixedAccountId.equals(id);
        }
        return passwordLogin ? passwordAccountId.equals(id) : !DEMO_ACCOUNT.id().equals(id);
    }

    boolean isAdmin(Account account) {
        if (demo) {
            return account.equals(DEMO_ACCOUNT);
        }
        if (fixedAccountMode) {
            return fixedAccountId.equals(account.id());
        }
        return adminUsers.stream().anyMatch(value -> value.startsWith("id:")
                ? value.substring(3).equals(account.id())
                : value.toLowerCase(Locale.ROOT).equals(account.username().toLowerCase(Locale.ROOT)));
    }

    private static String publicOrigin(String value) {
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException exception) {
            throw invalidOrigin();
        }
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null
                || (!uri.getRawPath().isEmpty() && !uri.getRawPath().equals("/"))) {
            throw invalidOrigin();
        }
        return "https://" + uri.getRawAuthority();
    }

    private static IllegalArgumentException invalidOrigin() {
        return new IllegalArgumentException("INBOX_PUBLIC_URL must be an HTTPS origin");
    }
}
