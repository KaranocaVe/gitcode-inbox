package io.github.karanocave.inbox.auth;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.karanocave.inbox.engine.Account;
import io.github.karanocave.inbox.engine.EngineException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

@Component
class GitCodeOAuth {
    private static final String AUTHORIZE_URL = "https://gitcode.com/oauth/authorize";
    private static final String TOKEN_URL = "https://gitcode.com/oauth/token";
    private static final String USER_URL = "https://api.gitcode.com/api/v5/user";

    private final AuthSettings settings;
    private final RestClient http;

    @Autowired
    GitCodeOAuth(AuthSettings settings) {
        this(settings, buildHttpClient());
    }

    GitCodeOAuth(AuthSettings settings, RestClient http) {
        this.settings = settings;
        this.http = http;
    }

    URI authorizationUri(String state) {
        return UriComponentsBuilder.fromUriString(AUTHORIZE_URL)
                .queryParam("client_id", "{clientId}")
                .queryParam("redirect_uri", "{callback}")
                .queryParam("response_type", "code")
                .queryParam("scope", "all_user")
                .queryParam("state", "{state}")
                .encode()
                .buildAndExpand(settings.clientId, settings.callbackUrl, state)
                .toUri();
    }

    Account exchange(String code) {
        URI exchangeUri = UriComponentsBuilder.fromUriString(TOKEN_URL)
                .queryParam("grant_type", "authorization_code")
                .queryParam("code", "{code}")
                .queryParam("client_id", "{clientId}")
                .encode()
                .buildAndExpand(code, settings.clientId)
                .toUri();
        var form = new LinkedMultiValueMap<String, String>();
        form.add("client_secret", settings.clientSecret);
        try {
            JsonNode response = http.post().uri(exchangeUri)
                    .accept(MediaType.APPLICATION_JSON)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(form)
                    .retrieve()
                    .body(JsonNode.class);
            String accessToken = field(response, "access_token");
            if (accessToken.isBlank()) {
                throw failed();
            }
            JsonNode profile = http.get().uri(USER_URL)
                    .accept(MediaType.APPLICATION_JSON)
                    .headers(headers -> headers.setBearerAuth(accessToken))
                    .retrieve()
                    .body(JsonNode.class);
            String id = field(profile, "id");
            String username = field(profile, "login");
            if (id.isBlank() || id.equals(AuthSettings.DEMO_ACCOUNT.id()) || username.isBlank()) {
                throw failed();
            }
            return new Account(id, username);
        } catch (RestClientException | IllegalArgumentException exception) {
            throw failed();
        }
    }

    private static String field(JsonNode response, String name) {
        return response == null ? "" : response.path(name).asText("");
    }

    private static EngineException failed() {
        return new EngineException(502, "oauth_exchange_failed", "GitCode could not confirm your identity", Map.of());
    }

    private static RestClient buildHttpClient() {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(client);
        requests.setReadTimeout(Duration.ofSeconds(20));
        return RestClient.builder().requestFactory(requests).build();
    }
}
