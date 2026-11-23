package io.github.karanocave.inbox.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.karanocave.inbox.auth.RequestAuth.McpIdentity;
import io.github.karanocave.inbox.engine.Account;
import io.github.karanocave.inbox.engine.EngineException;
import io.github.karanocave.inbox.engine.InboxEngine;
import io.github.karanocave.inbox.ops.InboxService;
import io.github.karanocave.inbox.ops.RuntimeSettings;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

@Component
class InboxTools {
    private static final Map<String, Object> GROUP_ID = Map.of("type", "string",
            "description", "Group ID returned by inbox_list_groups, e.g. owner/repo/pr/42", "maxLength", 512);
    private static final Map<String, Object> PAGE = Map.of(
            "limit", integer(1, 200, "Maximum items, default 50"),
            "offset", integer(0, 1_000_000, "Number of items to skip, default 0"),
            "version", Map.of("type", "integer", "minimum", 0, "description", "Pin snapshot version for pagination"));
    private static final Map<String, Object> SETTINGS = Map.of(
            "poll_seconds", Map.of("anyOf", List.of(Map.of("const", 0), integer(15, 3600, "Poll interval in seconds")),
                    "description", "Set 0 to pause automatic sync"),
            "full_interval_seconds", integer(60, 86400, "Full reconciliation interval in seconds"),
            "sync_debounce_seconds", integer(1, 300, "Deduplication window in seconds"),
            "upstream_requests_per_minute", integer(10, 600, "GitCode request quota"),
            "api_requests_per_minute", integer(10, 1200, "Per-account REST and MCP request quota"));

    private record Tool(Map<String, Object> descriptor, Map<String, Object> properties, List<String> required,
                        boolean admin, BiFunction<Account, ObjectNode, Object> call) {}

    private final Map<String, Tool> tools = new LinkedHashMap<>();

    InboxTools(InboxEngine engine, InboxService service, RuntimeSettings settings) {
        register("inbox_status", "Get snapshot status, unread counts, link status and dry-run mode.",
                Map.of(), List.of(), false, true, false, (account, args) -> service.status(account));
        register("inbox_list_groups", "List unread issue and PR groups. Use status.version for subsequent pages.",
                with(PAGE, Map.of("kind", choice("issue", "pr"), "repo", string("Exact owner/repo filter"),
                        "q", string("Search repository, title, or author"))),
                List.of(), false, true, false, (account, args) -> engine.groups(account, number(args, "limit", 50),
                        number(args, "offset", 0), text(args, "kind", ""), text(args, "repo", ""),
                        text(args, "q", ""), version(args)));
        register("inbox_list_messages", "List the unread notifications in a single issue or PR group.",
                with(PAGE, Map.of("group_id", GROUP_ID)), List.of("group_id"), false, true, false,
                (account, args) -> engine.messages(account, text(args, "group_id", ""), number(args, "limit", 50),
                        number(args, "offset", 0), version(args)));
        register("inbox_list_unassigned", "List unread notifications outside issue and PR groups.",
                PAGE, List.of(), false, true, false, (account, args) -> engine.messages(account, null,
                        number(args, "limit", 50), number(args, "offset", 0), version(args)));
        register("inbox_sync", "Refresh the unread snapshot from GitCode. Full mode reconciles all pages.",
                Map.of("mode", choice("incremental", "full")), List.of(), false, false, false,
                (account, args) -> service.sync(account, "full".equals(text(args, "mode", "incremental"))));
        register("inbox_mark_read", "Preview or mark selected groups read in GitCode. Defaults to dry_run=true. "
                        + "Use dry_run=false only to apply a requested change.",
                Map.of("group_ids", Map.of("type", "array", "items", GROUP_ID, "minItems", 1, "maxItems", 200),
                        "dry_run", Map.of("type", "boolean", "default", true)),
                List.of("group_ids"), false, false, true,
                (account, args) -> service.read(account, groupIds(args), args.path("dry_run").asBoolean(true)));
        register("inbox_get_metrics", "Get hourly operational statistics. The current unread count is for this account.",
                Map.of("hours", integer(1, 720, "Lookback window in hours, default 24")), List.of(), true, true, false,
                (account, args) -> service.metrics(account, number(args, "hours", 24)));
        register("inbox_get_settings", "Read current instance-wide polling, debounce and rate-limit settings.",
                Map.of(), List.of(), true, true, false, (account, args) -> settings.get());
        register("inbox_update_settings", "Update one or more instance-wide settings.",
                SETTINGS, List.of(), true, false, true, (account, args) -> {
                    if (args.isEmpty()) throw invalid("Specify at least one setting.");
                    return settings.patch(current -> new RuntimeSettings.Values(
                            number(args, "poll_seconds", current.pollSeconds()),
                            number(args, "full_interval_seconds", current.fullIntervalSeconds()),
                            number(args, "sync_debounce_seconds", current.syncDebounceSeconds()),
                            number(args, "upstream_requests_per_minute", current.upstreamRequestsPerMinute()),
                            number(args, "api_requests_per_minute", current.apiRequestsPerMinute())));
                });
    }

    List<Map<String, Object>> list(McpIdentity identity) {
        return tools.values().stream()
                .filter(tool -> !tool.admin() || identity.admin())
                .map(Tool::descriptor).toList();
    }

    Object call(McpIdentity identity, String name, ObjectNode args) {
        Tool tool = tools.get(name);
        if (tool == null) throw invalid("Unknown tool.");
        if (tool.admin() && !identity.admin()) {
            throw new EngineException(403, "admin_required", "This tool requires an administrator MCP key.", Map.of());
        }
        for (String key : tool.required()) {
            if (!args.has(key)) throw invalid("Missing argument: " + key);
        }
        args.properties().forEach(field -> {
            var schema = tool.properties().get(field.getKey());
            if (schema == null) throw invalid("Unknown argument.");
            @SuppressWarnings("unchecked")
            var rule = (Map<String, Object>) schema;
            if (!matches(rule, field.getValue())) throw invalid("Invalid argument: " + field.getKey());
        });
        return tool.call().apply(identity.account(), args);
    }

    private void register(String name, String description, Map<String, Object> props, List<String> required,
                          boolean admin, boolean readOnly, boolean destructive,
                          BiFunction<Account, ObjectNode, Object> operation) {
        var descriptor = Map.<String, Object>of("name", name, "description", description,
                "inputSchema", Map.of("type", "object", "properties", props, "required", required,
                        "additionalProperties", false),
                "annotations", Map.of("readOnlyHint", readOnly, "destructiveHint", destructive,
                        "openWorldHint", name.equals("inbox_sync") || name.equals("inbox_mark_read")));
        tools.put(name, new Tool(descriptor, props, required, admin, operation));
    }

    @SuppressWarnings("unchecked")
    private static boolean matches(Map<String, Object> schema, JsonNode value) {
        if (schema.containsKey("const")) return value.isIntegralNumber() && value.canConvertToInt() && value.intValue() == (int) schema.get("const");
        if (schema.get("anyOf") instanceof List<?> alternatives) {
            return alternatives.stream().anyMatch(rule -> matches((Map<String, Object>) rule, value));
        }
        return switch ((String) schema.get("type")) {
            case "integer" -> value.isIntegralNumber() && value.canConvertToLong()
                    && value.longValue() >= (int) schema.get("minimum")
                    && value.longValue() <= ((Number) schema.getOrDefault("maximum", Long.MAX_VALUE)).longValue();
            case "string" -> value.isTextual() && value.textValue().length() <= (int) schema.getOrDefault("maxLength", 512)
                    && (!schema.containsKey("enum") || ((List<?>) schema.get("enum")).contains(value.textValue()));
            case "boolean" -> value.isBoolean();
            case "array" -> value.isArray() && value.size() >= (int) schema.get("minItems")
                    && value.size() <= (int) schema.get("maxItems")
                    && java.util.stream.StreamSupport.stream(value.spliterator(), false)
                            .allMatch(item -> matches((Map<String, Object>) schema.get("items"), item));
            default -> false;
        };
    }

    private static Map<String, Object> integer(int min, int max, String description) {
        return Map.of("type", "integer", "minimum", min, "maximum", max, "description", description);
    }

    private static Map<String, Object> choice(String... choices) {
        return Map.of("type", "string", "enum", List.of(choices));
    }

    private static Map<String, Object> string(String description) {
        return Map.of("type", "string", "description", description, "maxLength", 512);
    }

    private static Map<String, Object> with(Map<String, Object> base, Map<String, Object> extra) {
        var copy = new LinkedHashMap<>(base);
        copy.putAll(extra);
        return copy;
    }

    private static int number(ObjectNode args, String key, int fallback) { return args.path(key).asInt(fallback); }
    private static String text(ObjectNode args, String key, String fallback) { return args.path(key).asText(fallback); }
    private static Long version(ObjectNode args) { return args.has("version") ? args.get("version").longValue() : null; }

    private static List<String> groupIds(ObjectNode args) {
        var ids = new ArrayList<String>();
        args.get("group_ids").forEach(item -> ids.add(item.textValue()));
        return ids;
    }

    private static EngineException invalid(String message) {
        return new EngineException(400, "invalid_arguments", message, Map.of());
    }
}
