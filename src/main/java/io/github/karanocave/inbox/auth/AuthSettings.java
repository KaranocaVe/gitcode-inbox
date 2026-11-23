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

    final boolean demo;
    final String clientId;
    final String clientSecret;
    final String callbackUrl;
    final String publicOrigin;
    private final Set<String> adminUsers;

    AuthSettings(
            @Value("${INBOX_MODE:production}") String mode,
            @Value("${INBOX_PUBLIC_URL:}") String publicUrl,
            @Value("${GITCODE_CLIENT_ID:}") String clientId,
            @Value("${GITCODE_CLIENT_SECRET:}") String clientSecret,
            @Value("${INBOX_ADMIN_USERS:}") String adminUsers,
            @Value("${server.servlet.context-path:}") String accessPrefix
    ) {
        if (!mode.equals("production") && !mode.equals("demo")) {
            throw new IllegalArgumentException("INBOX_MODE must be production or demo");
        }
        demo = mode.equals("demo");
        if (!demo && (clientId.isBlank() || clientSecret.isBlank())) {
            throw new IllegalArgumentException("GITCODE_CLIENT_ID and GITCODE_CLIENT_SECRET are required in production");
        }

        if (!accessPrefix.isEmpty() && !ACCESS_PREFIX.matcher(accessPrefix).matches()) {
            throw new IllegalArgumentException("INBOX_ACCESS_PREFIX must have two alphanumeric segments of 4–32 characters");
        }
        publicOrigin = demo ? null : publicOrigin(publicUrl);
        callbackUrl = demo ? null : publicOrigin + accessPrefix + "/oauth/callback";
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.adminUsers = Arrays.stream(adminUsers.split(","))
                .map(String::strip)
                .filter(value -> !value.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    boolean isAdmin(Account account) {
        if (demo) {
            return account.equals(DEMO_ACCOUNT);
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
