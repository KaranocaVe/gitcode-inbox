package io.github.karanocave.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest(properties = "INBOX_MODE=demo")
@AutoConfigureMockMvc
@DirtiesContext
class InboxIntegrationTest {
    @TempDir
    static Path temp;

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) {
        registry.add("INBOX_DB", () -> temp.resolve("inbox.sqlite3").toString());
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Test
    void syncsPagesAndReconcilesReadStateThroughThePublicApi() throws Exception {
        var browser = browser();
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/api/v1/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.account").value("demo"));

        var firstSync = body(mvc.perform(browser.authorize(post("/api/v1/sync"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"full\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("full"))
                .andReturn());
        String version = firstSync.path("version").asText();
        mvc.perform(browser.authorize(post("/api/v1/sync")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("debounced"))
                .andExpect(jsonPath("$.list_requests").value(0));

        var firstPage = body(mvc.perform(get("/api/v1/groups").param("limit", "1").param("version", version))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.status.version").value(Long.parseLong(version)))
                .andReturn());
        assertThat(firstPage.path("total").asInt()).isGreaterThan(1);
        String groupId = firstPage.path("items").get(0).path("id").asText();
        int groupCount = firstPage.path("items").get(0).path("unread_count").asInt();
        var secondPage = body(mvc.perform(get("/api/v1/groups").param("limit", "1").param("offset", "1")
                        .param("version", version))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andReturn());
        assertThat(secondPage.path("items").get(0).path("id").asText()).isNotEqualTo(groupId);
        String messagePath = "/api/v1/groups/" + groupId + "/messages";
        mvc.perform(get(messagePath).param("limit", "1").param("version", version))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].group_id").value(groupId))
                .andExpect(jsonPath("$.total").value(groupCount));
        mvc.perform(get("/api/v1/unassigned").param("version", version))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(firstPage.path("status").path("unassigned_count").asInt()));

        mvc.perform(browser.authorize(put("/api/v1/groups/read-state"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("group_ids", new String[] {groupId},
                                "read", true, "dry_run", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matched").value(groupCount))
                .andExpect(jsonPath("$.confirmed").value(0))
                .andExpect(jsonPath("$.message_ids.length()").value(groupCount));
        mvc.perform(browser.authorize(put("/api/v1/groups/read-state"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("group_ids", new String[] {groupId}, "read", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.confirmed").value(groupCount))
                .andExpect(jsonPath("$.confirmed_ids.length()").value(groupCount));
        mvc.perform(get(messagePath).param("version", version))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("snapshot_changed"));
        mvc.perform(get(messagePath))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/api/v1/admin/metrics").param("hours", "24"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hours").value(24))
                .andExpect(jsonPath("$.series.length()").value(24))
                .andExpect(jsonPath("$.totals.read_confirmed").value(groupCount))
                .andExpect(jsonPath("$.sync_latency_ms.p50").isNumber());
    }

    @Test
    void validatesAndStoresAdminSettings() throws Exception {
        var browser = browser();
        String settingsRoute = "/api/v1/admin/settings";
        byte[] previous = mvc.perform(get(settingsRoute))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        String proposed = """
                {"poll_seconds":0,"full_interval_seconds":600,"sync_debounce_seconds":9,
                 "upstream_requests_per_minute":200,"api_requests_per_minute":140}
                """;
        try {
            mvc.perform(browser.authorize(put(settingsRoute)).contentType(MediaType.APPLICATION_JSON).content(proposed))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.poll_seconds").value(0));
            mvc.perform(get(settingsRoute))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.full_interval_seconds").value(600))
                    .andExpect(jsonPath("$.sync_debounce_seconds").value(9));
            String invalid = proposed.replace("\"sync_debounce_seconds\":9", "\"sync_debounce_seconds\":0");
            mvc.perform(browser.authorize(put(settingsRoute)).contentType(MediaType.APPLICATION_JSON).content(invalid))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("invalid_settings"));
            mvc.perform(get(settingsRoute))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.sync_debounce_seconds").value(9));
        } finally {
            mvc.perform(browser.authorize(put(settingsRoute)).contentType(MediaType.APPLICATION_JSON).content(previous))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void enforcesCsrfAndScopesBearerKeysToInboxApi() throws Exception {
        var browser = browser();
        mvc.perform(post("/api/v1/sync").session(browser.session()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("invalid_csrf_token"));
        mvc.perform(get("/api/v1/status").header(HttpHeaders.AUTHORIZATION, "Bearer fixture-invalid"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_api_key"));

        var key = body(mvc.perform(browser.authorize(post("/api/v1/session/keys"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"integration test\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andReturn());
        String auth = "Bearer " + key.path("token").asText();
        String revokeRoute = "/api/v1/session/keys/" + key.path("id").asText();
        mvc.perform(get("/api/v1/status").header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/settings").header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("session_required"));
        mvc.perform(browser.authorize(delete(revokeRoute))).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/status").header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isUnauthorized());
    }

    private Browser browser() throws Exception {
        MvcResult response = mvc.perform(get("/api/v1/session"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.demo").value(true))
                .andExpect(jsonPath("$.admin").value(true))
                .andExpect(jsonPath("$.csrf_token").isString())
                .andReturn();
        return new Browser((MockHttpSession) response.getRequest().getSession(false),
                body(response).path("csrf_token").asText());
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsByteArray());
    }

    private record Browser(MockHttpSession session, String csrf) {
        MockHttpServletRequestBuilder authorize(MockHttpServletRequestBuilder request) {
            return request.session(session).header("X-CSRF-Token", csrf);
        }
    }
}
