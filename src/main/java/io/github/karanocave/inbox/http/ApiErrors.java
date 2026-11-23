package io.github.karanocave.inbox.http;

import io.github.karanocave.inbox.engine.EngineException;
import io.github.karanocave.inbox.engine.UpstreamException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler(EngineException.class)
    ResponseEntity<Map<String, Object>> engine(EngineException error) {
        int status = "upstream_rate_limited".equals(error.code()) ? 429 : error.status();
        return body(status, error.code(), error.getMessage(), error.details());
    }

    @ExceptionHandler(UpstreamException.class)
    ResponseEntity<Map<String, Object>> upstream(UpstreamException error) {
        int status = switch (error.code()) {
            case "upstream_rate_limited" -> 429;
            case "session_expired" -> 422;
            default -> 502;
        };
        var extra = error.retryAfterSeconds() > 0 ? Map.of("retry_after_seconds", error.retryAfterSeconds()) : Map.<String, Long>of();
        return body(status, error.code(), error.getMessage(), extra);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<Map<String, Object>> input() {
        return body(400, "invalid_request", "请求参数或 JSON 格式有误。", Map.of());
    }

    private ResponseEntity<Map<String, Object>> body(int status, String code, String message, Map<String, ?> extra) {
        var response = new LinkedHashMap<String, Object>();
        response.put("error", code);
        response.put("message", message);
        response.putAll(extra);
        var builder = ResponseEntity.status(status);
        if ("invalid_api_key".equals(code)) {
            builder.header(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"gitcode-inbox\"");
        }
        if (extra.get("retry_after_seconds") instanceof Number seconds) {
            builder.header(HttpHeaders.RETRY_AFTER, String.valueOf(seconds.longValue()));
        }
        return builder.body(response);
    }
}
