package io.github.karanocave.inbox.auth;

import io.github.karanocave.inbox.engine.Account;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "INBOX_MODE=production",
        "INBOX_AUTH_MODE=fixed",
        "INBOX_FIXED_ACCOUNT_ID=fixed-id",
        "INBOX_ACCESS_PREFIX=/a8K2/7mQp",
        "INBOX_PUBLIC_URL=https://inbox.example.test",
        "INBOX_TOKEN_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "GITCODE_CLIENT_ID=",
        "GITCODE_CLIENT_SECRET="
})
@AutoConfigureMockMvc
@DirtiesContext
class FixedAccountIntegrationTest {
    private static final String PREFIX = "/a8K2/7mQp";
    private static final String ORIGIN = "https://inbox.example.test";
    private static final Account FIXED = new Account("fixed-id", "fixed-user");
    private static final Account OTHER = new Account("other-id", "other-user");

    @TempDir static Path database;
    @Autowired private MockMvc mvc;
    @Autowired private IdentityStore accounts;
    @Autowired private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("INBOX_DB", () -> database.resolve("fixed.sqlite").toString());
    }

    @BeforeEach
    void seedAccounts() {
        accounts.saveAccount(FIXED);
        accounts.saveAccount(OTHER);
    }

    @Test
    void exposesThePersistedAccountAndAdminOperationsWithoutBrowserSession() throws Exception {
        var response = mvc.perform(get(PREFIX + "/api/v1/session").contextPath(PREFIX).secure(true))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.fixed_account").value(true))
                .andExpect(jsonPath("$.password_login").value(false))
                .andExpect(jsonPath("$.admin").value(true))
                .andExpect(jsonPath("$.account.id").value(FIXED.id()))
                .andExpect(jsonPath("$.account.username").value(FIXED.username()))
                .andExpect(jsonPath("$.csrf_token").doesNotExist())
                .andExpect(jsonPath("$.login_url").doesNotExist())
                .andReturn();
        assertThat(response.getRequest().getSession(false)).isNull();

        String oldKey = accounts.createKey(OTHER, "Legacy key", "inbox").token();
        mvc.perform(get(PREFIX + "/api/v1/status").contextPath(PREFIX).secure(true)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + oldKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.account").value(FIXED.username()));
        String proposed = """
                {"poll_seconds":0,"full_interval_seconds":600,"sync_debounce_seconds":9,
                 "upstream_requests_per_minute":200,"api_requests_per_minute":140}
                """;
        mvc.perform(put(PREFIX + "/api/v1/admin/settings").contextPath(PREFIX).secure(true)
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON).content(proposed))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sync_debounce_seconds").value(9));
        mvc.perform(rpc()).andExpect(status().isOk())
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
                .andExpect(jsonPath("$.result.tools.length()").value(9));
    }

    @Test
    void rejectsForeignOriginsAndInsecureAccess() throws Exception {
        mvc.perform(get(PREFIX + "/api/v1/session").contextPath(PREFIX).secure(true)
                        .header(HttpHeaders.ORIGIN, "https://external.test"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("invalid_origin"));
        mvc.perform(post(PREFIX + "/api/v1/sync").contextPath(PREFIX).secure(true)
                        .header(HttpHeaders.ORIGIN, "https://external.test"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("invalid_origin"));
        mvc.perform(rpc().header(HttpHeaders.ORIGIN, "null"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("invalid_origin"));
        mvc.perform(rpc().header(HttpHeaders.ORIGIN, ORIGIN, "https://external.test"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("invalid_origin"));
        mvc.perform(get(PREFIX + "/api/v1/status").contextPath(PREFIX))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("https_required"));
    }

    @Test
    void disablesInteractiveLoginLogoutAndKeyManagement() throws Exception {
        var key = accounts.createKey(FIXED, "Existing key", "inbox");
        for (var request : new MockHttpServletRequestBuilder[] {
                get(PREFIX + "/oauth/login"),
                get(PREFIX + "/oauth/callback"),
                get(PREFIX + "/api/v1/session/keys"),
                post(PREFIX + "/api/v1/session/logout"),
                delete(PREFIX + "/api/v1/session/keys/" + key.id()),
                post(PREFIX + "/api/v1/session/keys").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"new\"}"),
                post(PREFIX + "/api/v1/session/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"unused\",\"password\":\"unused\"}")
        }) {
            mvc.perform(request.contextPath(PREFIX).secure(true))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("fixed_account_mode"))
                    .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
        }
        assertThat(accounts.listKeys(FIXED)).extracting(IdentityStore.KeyInfo::id).containsExactly(key.id());
        accounts.revokeKey(FIXED, key.id());
    }

    @Test
    void missingFixedAccountFailsClosedAcrossRestAndMcp() throws Exception {
        jdbc.update("DELETE FROM inbox_auth_accounts WHERE id = ?", FIXED.id());
        mvc.perform(get(PREFIX + "/api/v1/session").contextPath(PREFIX).secure(true))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("fixed_account_unavailable"));
        mvc.perform(get(PREFIX + "/api/v1/admin/settings").contextPath(PREFIX).secure(true))
                .andExpect(status().isServiceUnavailable());
        mvc.perform(rpc()).andExpect(status().isServiceUnavailable());
        assertThat(accounts.findAccount(OTHER.id())).isEqualTo(OTHER);
    }

    private MockHttpServletRequestBuilder rpc() {
        return post(PREFIX + "/mcp").contextPath(PREFIX).secure(true).contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                .content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}");
    }
}
