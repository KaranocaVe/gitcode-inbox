package io.github.karanocave.inbox.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.karanocave.inbox.engine.EngineException;
import io.github.karanocave.inbox.ops.TrafficGate;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;

@Component
public class ProtocolFilter extends OncePerRequestFilter {
    private static final String CSP = "default-src 'self'; script-src 'self'; style-src 'self'; "
            + "object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self' https://gitcode.com";
    private static final int MAX_BODY = 64 * 1024;
    private final ObjectMapper json;
    private final TrafficGate gate;

    public ProtocolFilter(ObjectMapper json, TrafficGate gate) {
        this.json = json;
        this.gate = gate;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        response.setHeader("Content-Security-Policy", CSP);
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("X-Frame-Options", "DENY");
        if (request.isSecure()) response.setHeader("Strict-Transport-Security", "max-age=31536000");
        boolean mcp = path.equals("/mcp");
        if (mcp || path.startsWith("/api/") || path.startsWith("/oauth/")) response.setHeader("Cache-Control", "no-store");
        boolean api = path.startsWith("/api/");
        if (((mcp || api) && request.getContentLengthLong() > MAX_BODY)
                || (api && request.getHeader("Transfer-Encoding") != null)) {
            error(response, 413, "request_too_large", "请求体须小于 64 KiB。");
            return;
        }
        if (path.startsWith("/oauth/") || path.equals("/api/v1/session")) {
            try {
                gate.login(request.getRemoteAddr());
            } catch (EngineException failure) {
                response.setHeader(HttpHeaders.RETRY_AFTER, failure.details().get("retry_after_seconds").toString());
                error(response, failure.status(), failure.code(), failure.getMessage());
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private void error(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        json.writeValue(response.getOutputStream(), Map.of("error", code, "message", message));
    }
}
