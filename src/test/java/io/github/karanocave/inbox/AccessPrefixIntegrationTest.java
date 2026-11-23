package io.github.karanocave.inbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"INBOX_MODE=demo", "INBOX_ACCESS_PREFIX=/a8K2/7mQp"})
@DirtiesContext
class AccessPrefixIntegrationTest {
    private static final String PREFIX = "/a8K2/7mQp";

    @TempDir static Path temp;
    @LocalServerPort int port;
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) {
        registry.add("INBOX_DB", () -> temp.resolve("inbox.sqlite3").toString());
    }

    @Test
    void onlyTheConfiguredPrefixServesTheApplication() throws Exception {
        for (String path : new String[] {"/", "/app.js", "/oauth/login", "/actuator/health",
                "/api/v1/status", "/a8K2/wRoN/api/v1/status", PREFIX + "extra/api/v1/status"}) {
            var response = get(path);
            assertThat(response.statusCode()).as(path).isEqualTo(404);
            assertThat(response.headers().firstValue("Location")).isEmpty();
        }

        var home = get(PREFIX + "/");
        assertThat(home.statusCode()).isEqualTo(200);
        assertThat(home.body()).contains("src=\"./app.js\"", "href=\"./style.css\"");
        assertThat(home.headers().firstValue("Referrer-Policy")).contains("no-referrer");
        assertThat(get(PREFIX + "/app.js").statusCode()).isEqualTo(200);
        assertThat(get(PREFIX + "/style.css").statusCode()).isEqualTo(200);
        assertThat(get(PREFIX + "/api/v1/status").statusCode()).isEqualTo(200);
        assertThat(get(PREFIX + "/actuator/health").statusCode()).isEqualTo(200);
        var login = get(PREFIX + "/oauth/login");
        assertThat(login.statusCode()).isEqualTo(303);
        assertThat(login.headers().firstValue("Location")).contains(PREFIX + "/");
        var session = get(PREFIX + "/api/v1/session");
        assertThat(session.statusCode()).isEqualTo(200);
        assertThat(session.headers().firstValue("Set-Cookie").orElseThrow()).contains("Path=" + PREFIX);
    }

    private HttpResponse<String> get(String path) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
