package io.github.karanocave.inbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.karanocave.inbox.ops.RuntimeSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"INBOX_MODE=demo", "INBOX_ACCESS_PREFIX=/a8K2/7mQp"})
@AutoConfigureMockMvc
@DirtiesContext
class McpIntegrationTest {
    private static final String PREFIX = "/a8K2/7mQp";
    @TempDir static Path database;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("INBOX_DB", () -> database.resolve("mcp.sqlite").toString());
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired RuntimeSettings settings;

    @Test
    void negotiatesProtocolAndRequiresAValidKeyForEveryRequest() throws Exception {
        var key = createKey("inbox");
        String initialize = request("initialize", Map.of("protocolVersion", "2025-11-25",
                "capabilities", Map.of(), "clientInfo", Map.of("name", "test", "version", "1")));
        mvc.perform(rpc(initialize)).andExpect(status().isUnauthorized());
        mvc.perform(key.authorize(rpc(initialize)).header("Origin", "https://unexpected.test"))
                .andExpect(status().isForbidden());
        mvc.perform(key.authorize(rpc(initialize)).header("MCP-Protocol-Version", "unknown"))
                .andExpect(status().isBadRequest());
        mvc.perform(key.authorize(rpc(initialize))).andExpect(status().isOk())
                .andExpect(header().doesNotExist("Mcp-Session-Id"))
                .andExpect(jsonPath("$.id").value("request-id"))
                .andExpect(jsonPath("$.result.protocolVersion").value("2025-11-25"))
                .andExpect(jsonPath("$.result.capabilities.tools").isMap());
        mvc.perform(key.authorize(rpc("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}")))
                .andExpect(status().isAccepted()).andExpect(content().string(""));
        mvc.perform(key.authorize(get(PREFIX + "/mcp").contextPath(PREFIX)))
                .andExpect(status().isMethodNotAllowed()).andExpect(header().string("Allow", "POST"));
        mvc.perform(key.authorize(rpc("{"))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(-32700));
        mvc.perform(key.authorize(rpc(initialize + " {}"))).andExpect(status().isBadRequest());
        mvc.perform(key.authorize(rpc(request("unsupported", Map.of())))).andExpect(status().isOk())
                .andExpect(jsonPath("$.error.code").value(-32601));
        mvc.perform(key.authorize(rpc(request("tools/list", Map.of())))).andExpect(status().isOk())
                .andExpect(jsonPath("$.result.tools.length()").value(6));
        mvc.perform(key.browser().authorize(delete(PREFIX + "/api/v1/session/keys/" + key.id()).contextPath(PREFIX)))
                .andExpect(status().isNoContent());
        mvc.perform(key.authorize(rpc(initialize))).andExpect(status().isUnauthorized());
    }

    @Test
    void listsMessagesAndRequiresExplicitOptInToMarkRead() throws Exception {
        var key = createKey("inbox");
        call(key, "inbox_sync", Map.of("mode", "full")).andExpect(jsonPath("$.result.isError").value(false));
        var page = read(call(key, "inbox_list_groups", Map.of("kind", "pr", "limit", 1,
                "repo", "nebula/runtime")));
        var group = page.path("result").path("structuredContent").path("items").get(0);
        assertThat(group.path("id").asText()).isEqualTo("nebula/runtime/pr/418");
        long version = page.path("result").path("structuredContent").path("status").path("version").asLong();
        call(key, "inbox_list_messages", Map.of("group_id", group.path("id").asText(), "version", version))
                .andExpect(jsonPath("$.result.structuredContent.total").value(8));
        call(key, "inbox_list_unassigned", Map.of())
                .andExpect(jsonPath("$.result.structuredContent.total").value(1));
        call(key, "inbox_list_groups", Map.of("limit", "1"))
                .andExpect(jsonPath("$.result.structuredContent.error").value("invalid_arguments"));
        var inputs = Map.<String, Object>of("group_ids", List.of(group.path("id").asText()));
        mvc.perform(key.authorize(rpc(json.writeValueAsString(Map.of("jsonrpc", "2.0", "method", "tools/call",
                        "params", Map.of("name", "inbox_mark_read", "arguments", Map.of(
                                "group_ids", inputs.get("group_ids"), "dry_run", false)))))))
                .andExpect(status().isAccepted());
        call(key, "inbox_mark_read", inputs).andExpect(jsonPath("$.result.structuredContent.matched").value(8))
                .andExpect(jsonPath("$.result.structuredContent.confirmed").value(0))
                .andExpect(jsonPath("$.result.structuredContent.dry_run").value(true));
        call(key, "inbox_mark_read", Map.of("group_ids", inputs.get("group_ids"), "dry_run", false))
                .andExpect(jsonPath("$.result.structuredContent.confirmed").value(8));
        call(key, "inbox_list_groups", Map.of("version", version))
                .andExpect(jsonPath("$.result.isError").value(true))
                .andExpect(jsonPath("$.result.structuredContent.error").value("snapshot_changed"));
    }

    @Test
    void limitsAdminToolsToScopedKeysAndSharesSettingsWithRest() throws Exception {
        var previous = settings.get();
        try {
            var normal = createKey("inbox");
            var admin = createKey("mcp_admin");
            call(normal, "inbox_get_settings", Map.of())
                    .andExpect(jsonPath("$.result.structuredContent.error").value("admin_required"));
            mvc.perform(admin.authorize(rpc(request("tools/list", Map.of())))).andExpect(status().isOk())
                    .andExpect(jsonPath("$.result.tools.length()").value(9));
            call(admin, "inbox_get_metrics", Map.of("hours", 6))
                    .andExpect(jsonPath("$.result.structuredContent.series.length()").value(6));
            call(admin, "inbox_update_settings", Map.of("poll_seconds", 0, "sync_debounce_seconds", 12))
                    .andExpect(jsonPath("$.result.structuredContent.poll_seconds").value(0));
            mvc.perform(get(PREFIX + "/api/v1/admin/settings").contextPath(PREFIX).session(admin.browser().session()))
                    .andExpect(jsonPath("$.sync_debounce_seconds").value(12));
            call(admin, "inbox_update_settings", Map.of("poll_seconds", 1))
                    .andExpect(jsonPath("$.result.isError").value(true));
            call(admin, "inbox_get_settings", Map.of())
                    .andExpect(jsonPath("$.result.structuredContent.poll_seconds").value(0));
            assertThat(settings.get().fullIntervalSeconds()).isEqualTo(previous.fullIntervalSeconds());
        } finally {
            settings.replace(previous);
        }
    }

    private ResultActions call(Key key, String name, Map<String, Object> args) throws Exception {
        return mvc.perform(key.authorize(rpc(request("tools/call", Map.of("name", name, "arguments", args)))))
                .andExpect(status().isOk());
    }

    private String request(String method, Map<String, Object> params) throws Exception {
        return json.writeValueAsString(Map.of("jsonrpc", "2.0", "id", "request-id", "method", method, "params", params));
    }

    private MockHttpServletRequestBuilder rpc(String payload) {
        return post(PREFIX + "/mcp").contextPath(PREFIX).contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM).content(payload);
    }

    private Key createKey(String scope) throws Exception {
        var session = new MockHttpSession();
        var status = read(mvc.perform(get(PREFIX + "/api/v1/session").contextPath(PREFIX).session(session))
                .andExpect(status().isOk()));
        var browser = new Browser(session, status.path("csrf_token").asText());
        var key = read(mvc.perform(browser.authorize(post(PREFIX + "/api/v1/session/keys").contextPath(PREFIX))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", "MCP test", "scope", scope))))
                .andExpect(status().isCreated()));
        return new Key(key.path("id").asText(), key.path("token").asText(), browser);
    }

    private JsonNode read(ResultActions response) throws Exception {
        return json.readTree(response.andReturn().getResponse().getContentAsByteArray());
    }

    private record Browser(MockHttpSession session, String csrf) {
        MockHttpServletRequestBuilder authorize(MockHttpServletRequestBuilder request) {
            return request.session(session).header("X-CSRF-Token", csrf);
        }
    }

    private record Key(String id, String token, Browser browser) {
        MockHttpServletRequestBuilder authorize(MockHttpServletRequestBuilder request) {
            return request.header("Authorization", "Bearer " + token);
        }
    }
}
