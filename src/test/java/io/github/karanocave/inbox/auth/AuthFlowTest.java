package io.github.karanocave.inbox.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import io.github.karanocave.inbox.engine.Account;
import io.github.karanocave.inbox.engine.EngineException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthFlowTest {
    private static final Account ALICE = new Account("alice-id", "alice");
    private static final Account BOB = new Account("bob-id", "bob");
    private final ObjectMapper json = new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    private SingleConnectionDataSource database;
    private JdbcTemplate jdbc;
    private IdentityStore store;
    private RequestAuth auth;
    private MockRestServiceServer gitcode;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        initialize("production");
    }

    @AfterEach
    void tearDown() {
        database.destroy();
    }

    @Test
    void oauthStateIsSessionBoundAndConsumedAndTheSessionIdRotates() throws Exception {
        mvc.perform(get("/api/v1/session").secure(true))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(false))
                .andExpect(jsonPath("$.login_url").value("/oauth/login"));
        var login = beginLogin();
        String anonymousSessionId = login.session().getId();

        mvc.perform(get("/oauth/callback").secure(true).session(new MockHttpSession())
                        .param("code", "other-session-code").param("state", login.state()))
                .andExpect(status().isSeeOther());
        mvc.perform(get("/oauth/callback").secure(true).session(login.session())
                        .param("code", "other-state-code").param("state", login.state() + "x"))
                .andExpect(status().isSeeOther());
        mvc.perform(get("/api/v1/session").secure(true).session(login.session()))
                .andExpect(jsonPath("$.authenticated").value(false))
                .andExpect(jsonPath("$.error").isString());

        expectOAuth(ALICE);
        completeLogin(login);
        assertThat(login.session().getId()).isNotEqualTo(anonymousSessionId);
        mvc.perform(get("/api/v1/session").secure(true).session(login.session()))
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.admin").value(true))
                .andExpect(jsonPath("$.linked").value(true))
                .andExpect(jsonPath("$.account.id").value(ALICE.id()))
                .andExpect(jsonPath("$.csrf_token").isString());

        mvc.perform(get("/oauth/callback").secure(true).session(login.session())
                        .param("code", "replayed-code").param("state", login.state()))
                .andExpect(status().isSeeOther());
        gitcode.verify();
        assertThat(store.findAccount(ALICE.id())).isEqualTo(ALICE);
    }

    @Test
    void keysBelongToTheirAccountAndOnlyTheirDigestIsPersisted() throws Exception {
        MockHttpSession alice = signIn(ALICE);
        MockHttpSession bob = signIn(BOB);
        String aliceCsrf = csrfToken(alice, true);
        String bobCsrf = csrfToken(bob, true);
        mvc.perform(post("/api/v1/session/keys").secure(true).session(alice)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"CLI\"}")
                        .header("X-CSRF-Token", bobCsrf))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("invalid_csrf_token"));
        MvcResult issued = mvc.perform(post("/api/v1/session/keys").secure(true).session(alice)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"CLI\"}")
                        .header("X-CSRF-Token", aliceCsrf))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.created_at").isString())
                .andReturn();
        JsonNode key = json.readTree(issued.getResponse().getContentAsByteArray());
        String id = key.path("id").asText();
        String token = key.path("token").asText();
        assertThat(jdbc.queryForObject("SELECT digest FROM inbox_auth_api_keys", String.class))
                .isEqualTo(SecretValues.digest(token))
                .isNotEqualTo(token);
        mvc.perform(get("/api/v1/session/keys").secure(true).session(alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].name").value("CLI"))
                .andExpect(jsonPath("$.items[0].token").doesNotExist());
        mvc.perform(get("/api/v1/session/keys").secure(true).session(bob))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
        mvc.perform(delete("/api/v1/session/keys/{id}", id).secure(true).session(bob)
                        .header("X-CSRF-Token", bobCsrf))
                .andExpect(status().isNotFound());

        var bearer = new MockHttpServletRequest("POST", "/api/v1/sync");
        bearer.setSecure(true);
        bearer.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        assertThat(auth.requireInbox(bearer)).isEqualTo(ALICE);
        assertThatThrownBy(() -> auth.requireAdmin(bearer)).isInstanceOfSatisfying(EngineException.class,
                exception -> assertThat(exception.code()).isEqualTo("session_required"));
        mvc.perform(get("/api/v1/session/keys").secure(true).session(alice)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden());

        mvc.perform(delete("/api/v1/session/keys/{id}", id).secure(true).session(alice)
                        .header("X-CSRF-Token", aliceCsrf))
                .andExpect(status().isNoContent());
        assertThatThrownBy(() -> auth.requireInbox(bearer)).isInstanceOfSatisfying(EngineException.class,
                exception -> assertThat(exception.code()).isEqualTo("invalid_api_key"));
        gitcode.verify();
    }

    @Test
    void productionRequiresHttpsAndLogoutRequiresCsrf() throws Exception {
        mvc.perform(get("/oauth/login")).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/session")).andExpect(status().isForbidden());
        MockHttpSession alice = signIn(ALICE);
        mvc.perform(post("/api/v1/session/logout").secure(true).session(alice))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/session/logout").secure(true).session(alice)
                        .header("X-CSRF-Token", csrfToken(alice, true)))
                .andExpect(status().isNoContent());
        assertThat(alice.isInvalid()).isTrue();
        mvc.perform(get("/api/v1/session").secure(true))
                .andExpect(jsonPath("$.authenticated").value(false));
        gitcode.verify();
    }

    @Test
    void demoCanCreateKeysOnlyAfterObtainingItsCsrfToken() throws Exception {
        database.destroy();
        initialize("demo");
        var session = new MockHttpSession();
        mvc.perform(get("/api/v1/session").session(session))
                .andExpect(jsonPath("$.admin").value(true))
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.demo").value(true))
                .andExpect(jsonPath("$.account.id").value("demo"));
        mvc.perform(post("/api/v1/session/keys").session(session)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"CLI\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/session/keys").session(session)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"CLI\"}")
                        .header("X-CSRF-Token", csrfToken(session, false)))
                .andExpect(status().isCreated());
    }

    @Test
    void configuredAdminUsersCanUseStableIdentityOrUsername() {
        assertThat(auth.isAdmin(new Account("alice-id", "renamed-alice"))).isTrue();
        assertThat(auth.isAdmin(new Account("some-id", "NAmED-ADMIN"))).isTrue();
        assertThat(auth.isAdmin(new Account("different-id", "alice"))).isFalse();
    }

    private void initialize(String mode) {
        var settings = new AuthSettings(mode, "https://inbox.example.test/", "client-test",
                "secret-test", "id:alice-id, named-admin");
        database = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        jdbc = new JdbcTemplate(database);
        store = new IdentityStore(jdbc, settings);
        store.initialize();
        auth = new RequestAuth(settings, store);
        RestClient.Builder builder = RestClient.builder();
        gitcode = MockRestServiceServer.bindTo(builder).build();
        mvc = MockMvcBuilders.standaloneSetup(new LoginController(auth, new GitCodeOAuth(settings, builder.build())),
                        new SessionController(auth, store, account -> account.id().equals(ALICE.id())))
                .setControllerAdvice(new Errors())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(json))
                .build();
    }

    private MockHttpSession signIn(Account account) throws Exception {
        var login = beginLogin();
        expectOAuth(account);
        completeLogin(login);
        gitcode.verify();
        gitcode.reset();
        return login.session();
    }

    private Login beginLogin() throws Exception {
        MvcResult result = mvc.perform(get("/oauth/login").secure(true))
                .andExpect(status().isSeeOther()).andReturn();
        String location = result.getResponse().getHeader(HttpHeaders.LOCATION);
        var redirect = UriComponentsBuilder.fromUriString(location).build();
        assertThat(redirect.getScheme()).isEqualTo("https");
        assertThat(redirect.getHost()).isEqualTo("gitcode.com");
        assertThat(redirect.getPath()).isEqualTo("/oauth/authorize");
        assertThat(URLDecoder.decode(redirect.getQueryParams().getFirst("redirect_uri"), StandardCharsets.UTF_8))
                .isEqualTo("https://inbox.example.test/oauth/callback");
        assertThat(redirect.getQueryParams().getFirst("scope")).isEqualTo("all_user");
        return new Login((MockHttpSession) result.getRequest().getSession(false),
                redirect.getQueryParams().getFirst("state"));
    }

    private void expectOAuth(Account account) {
        gitcode.expect(requestTo("https://gitcode.com/oauth/token?grant_type=authorization_code&code=code%2Btest&client_id=client-test"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.CONTENT_TYPE, containsString(MediaType.MULTIPART_FORM_DATA_VALUE)))
                .andExpect(content().string(containsString("name=\"client_secret\"")))
                .andExpect(content().string(containsString("secret-test")))
                .andRespond(withSuccess("{\"access_token\":\"oauth-test\",\"refresh_token\":\"unused\"}",
                        MediaType.APPLICATION_JSON));
        gitcode.expect(requestTo("https://api.gitcode.com/api/v5/user"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer oauth-test"))
                .andRespond(withSuccess("""
                        {"id":"%s","login":"%s"}
                        """.formatted(account.id(), account.username()), MediaType.APPLICATION_JSON));
    }

    private void completeLogin(Login login) throws Exception {
        mvc.perform(get("/oauth/callback").secure(true).session(login.session())
                        .param("code", "code+test").param("state", login.state()))
                .andExpect(status().isSeeOther())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl("/"));
    }

    private String csrfToken(MockHttpSession session, boolean secure) throws Exception {
        var response = mvc.perform(get("/api/v1/session").secure(secure).session(session))
                .andExpect(status().isOk()).andReturn().getResponse();
        return json.readTree(response.getContentAsByteArray()).path("csrf_token").asText();
    }

    private record Login(MockHttpSession session, String state) {}

    @RestControllerAdvice
    static class Errors {
        @ExceptionHandler(EngineException.class)
        ResponseEntity<Map<String, String>> handle(EngineException exception) {
            return ResponseEntity.status(exception.status()).body(Map.of("code", exception.code()));
        }
    }
}
