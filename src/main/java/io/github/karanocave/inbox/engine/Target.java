package io.github.karanocave.inbox.engine;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.regex.Pattern;

public record Target(String repository, String kind, long number) {
    private static final String ORIGIN = "https://gitcode.com";
    private static final Pattern REPOSITORY_PART = Pattern.compile("[\\p{L}\\p{M}\\p{N}_.-]{1,200}");
    private static final Pattern NUMBER = Pattern.compile("[0-9]{1,18}");

    public String id() {
        return repository + "/" + kind + "/" + number;
    }

    public String url() {
        String route = kind.equals("pr") ? "pull" : "issues";
        return URI.create(ORIGIN + "/" + repository + "/" + route + "/" + number).toASCIIString();
    }

    public record Destination(Target target, String url) {}

    public static Target fromUrl(String value) {
        return destination(value).target();
    }

    public static Destination destination(String value) {
        if (value == null || value.isEmpty()
                || value.chars().anyMatch(c -> c < 32 || c == '\\')) {
            return new Destination(null, "");
        }
        try {
            String absolute = value;
            if (value.startsWith("//")) {
                absolute = "https:" + value;
            } else if (value.startsWith("/")) {
                absolute = ORIGIN + value;
            }
            URI uri = new URI(absolute);
            String scheme = uri.getScheme();
            String authority = uri.getRawAuthority();
            if (scheme == null || !(scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("http"))
                    || authority == null || !(authority.equalsIgnoreCase("gitcode.com")
                    || authority.equalsIgnoreCase("www.gitcode.com"))) {
                return new Destination(null, "");
            }
            String safeUrl = ORIGIN + uri.getRawPath();
            if (uri.getRawQuery() != null) {
                safeUrl += "?" + uri.getRawQuery();
            }
            if (uri.getRawFragment() != null) {
                safeUrl += "#" + uri.getRawFragment();
            }
            return new Destination(parsePath(uri.getPath(), false), safeUrl);
        } catch (URISyntaxException ex) {
            return new Destination(null, "");
        }
    }

    public static Target parseGroupId(String value) {
        if (value == null || value.length() > 512) {
            return null;
        }
        Target target = parsePath(value, true);
        return target != null && value.equals(target.id()) ? target : null;
    }

    private static Target parsePath(String path, boolean groupId) {
        String[] parts = path.replaceFirst("^/+", "").split("/");
        if (parts.length < 4 || groupId && parts.length != 4
                || !validRepositoryPart(parts[0]) || !validRepositoryPart(parts[1])
                || !NUMBER.matcher(parts[3]).matches()) {
            return null;
        }
        long number = Long.parseLong(parts[3]);
        String kind = switch (parts[2]) {
            case "pull", "merge_requests" -> groupId ? null : "pr";
            case "issues" -> groupId ? null : "issue";
            case "pr", "issue" -> groupId ? parts[2] : null;
            default -> null;
        };
        if (kind == null || number == 0) {
            return null;
        }
        String repository = (parts[0] + "/" + parts[1]).toLowerCase(Locale.ROOT);
        return new Target(repository, kind, number);
    }

    private static boolean validRepositoryPart(String part) {
        return !part.equals(".") && !part.equals("..") && REPOSITORY_PART.matcher(part).matches();
    }
}
