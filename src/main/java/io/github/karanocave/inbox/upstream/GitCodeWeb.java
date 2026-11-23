package io.github.karanocave.inbox.upstream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import io.github.karanocave.inbox.engine.*;
import io.github.karanocave.inbox.ops.TrafficGate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class GitCodeWeb {
    private static final String API = "https://web-api.gitcode.com/uc/api/v1/user";
    private static final int MAX_BODY = 8 * 1024 * 1024;
    private static final int PAGE_SIZE = 50;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final ObjectMapper json;
    private final TrafficGate gate;

    public record Identity(String id, String username) {}
    public record Refreshed(String access, String refresh) {
        @Override public String toString() { return "[GitCode credentials]"; }
    }

    public GitCodeWeb(ObjectMapper json, TrafficGate gate) {
        this.json = json;
        this.gate = gate;
    }

    public Identity identity(String token) {
        var body = request("GET", "/oauth/userInfo", token, null, null);
        String id = body.path("id").asText("");
        String username = body.path("username").asText("");
        if (id.isBlank() || username.isBlank()) throw malformed("GitCode 身份响应无效。");
        return new Identity(id, username);
    }

    public Refreshed refresh(String token, String refresh) {
        var body = request("POST", "/token/refresh", token, "refresh_token=" + encode(refresh),
                "application/x-www-form-urlencoded");
        String access = body.path("access_token").asText("");
        if (!validToken(access)) {
            throw new UpstreamException("session_expired", "GitCode Web 会话刷新失败。", false, 0);
        }
        String rotated = body.path("refresh_token").asText("");
        return new Refreshed(access, validToken(rotated) ? rotated : refresh);
    }

    public Page page(String secret, String webUserId, int page) {
        var body = request("GET", "/notify/list/remind?curr_user_id=" + encode(webUserId)
                + "&read_state=0&content=&page_num=" + page + "&page_size=" + PAGE_SIZE, secret, null, null);
        try {
            var rows = body.path("records");
            if (!(rows.isArray() || rows.isNull() && intField(body, "total") == 0)) {
                throw new IllegalArgumentException("records");
            }
            var messages = new ArrayList<Notice>();
            for (var row : rows) {
                var id = row.path("id");
                if (!(id.isTextual() || id.isIntegralNumber()) || id.asText().isBlank()
                        || id.asText().length() > 256 || intField(row, "read_state") != 0) {
                    throw new IllegalArgumentException("notice");
                }
                var destination = Target.destination(row.path("url").asText(""));
                messages.add(new Notice(id.asText(), destination.target() == null ? null : destination.target().id(),
                        OffsetDateTime.parse(row.path("created_date").asText()).toInstant(),
                        row.path("content").asText(""), row.path("sender_name").asText(""),
                        row.path("act").asText(""), row.path("target_type").asText(""), destination.url()));
            }
            return new Page(intField(body, "current"), intField(body, "size"), intField(body, "pages"),
                    intField(body, "total"), messages);
        } catch (RuntimeException failure) {
            throw malformed("GitCode 返回了无法解析的通知或分页数据。");
        }
    }

    public void markRead(String secret, String webUserId, List<String> ids) {
        if (ids.isEmpty() || ids.size() > 200) throw new IllegalArgumentException("batch size");
        try {
            String body = json.writeValueAsString(Map.of("ids", ids, "curr_user_id", webUserId));
            var response = request("PUT", "/notify/remind/read", secret, body, "application/json");
            if (response.isBoolean() && !response.booleanValue()
                    || response.path("success").isBoolean() && !response.path("success").booleanValue()) {
                throw new UpstreamException("upstream_rejected", "GitCode 拒绝更新消息。", false, 0);
            }
        } catch (IOException e) {
            throw malformed("已读请求编码失败。");
        }
    }

    private JsonNode request(String method, String path, String token, String body, String contentType) {
        if (!validToken(token)) throw new UpstreamException("session_expired", "GitCode Web 会话无效。", false, 0);
        var builder = HttpRequest.newBuilder(URI.create(API + path)).timeout(Duration.ofSeconds(18))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json")
                .header("Origin", "https://gitcode.com")
                .header("Referer", "https://gitcode.com/notice")
                .header("User-Agent", "gitcode-inbox/1.0");
        if (body != null) builder.header("Content-Type", contentType);
        var request = builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build();
        boolean mutation = method.equals("PUT");
        gate.beforeUpstream();
        try {
            var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (var stream = response.body()) {
                int status = response.statusCode();
                if (status == 429) {
                    long seconds = gate.pauseAfter429(response.headers().firstValue("Retry-After").orElse(null));
                    throw new UpstreamException("upstream_rate_limited", "GitCode 请求过于频繁。", false, seconds);
                }
                if (status == 401) {
                    throw new UpstreamException("session_expired", "GitCode Web 会话过期，请重新连接。", false, 0);
                }
                if (status < 200 || status >= 300) {
                    throw new UpstreamException("upstream_error", "GitCode 返回 HTTP " + status + "。",
                            mutation && status >= 500, 0);
                }
                byte[] bytes = stream.readNBytes(MAX_BODY + 1);
                if (bytes.length > MAX_BODY) throw new IOException("response limit");
                if (bytes.length == 0) return NullNode.instance;
                var envelope = json.readTree(bytes);
                if (envelope == null || envelope.isMissingNode()) throw new IOException("empty response");
                String code = envelope.path("error_code").asText("");
                String error = envelope.path("error_code_name").asText("");
                if ("425".equals(code) || "TOKEN_INVALID_ERROR".equals(error)) {
                    throw new UpstreamException("session_expired", "GitCode Web 会话过期，请重新连接。", false, 0);
                }
                if ((!code.isEmpty() && !"0".equals(code)) || !error.isEmpty() || envelope.path("error").asBoolean()
                        || envelope.path("success").isBoolean() && !envelope.path("success").booleanValue()) {
                    throw new UpstreamException("upstream_rejected", "GitCode 拒绝了请求。", false, 0);
                }
                return envelope.hasNonNull("data") ? envelope.get("data") : envelope;
            }
        } catch (IOException e) {
            throw new UpstreamException("upstream_io", "GitCode 请求中断或响应无效。", mutation, 0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UpstreamException("interrupted", "GitCode 请求被取消。", mutation, 0);
        }
    }

    static boolean validToken(String token) {
        return token != null && !token.isBlank() && token.length() <= 12000
                && token.chars().allMatch(c -> c >= 33 && c <= 126);
    }

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }

    private static int intField(JsonNode record, String key) {
        var value = record.path(key);
        if (!value.isIntegralNumber() || !value.canConvertToInt()) throw new IllegalArgumentException(key);
        return value.intValue();
    }

    private static UpstreamException malformed(String message) {
        return new UpstreamException("invalid_upstream_response", message, false, 0);
    }
}
