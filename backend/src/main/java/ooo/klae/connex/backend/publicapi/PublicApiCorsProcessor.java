package ooo.klae.connex.backend.publicapi;

import java.io.IOException;

import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.web.cors.DefaultCorsProcessor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import ooo.klae.connex.backend.config.SecurityResponseHeaders;
import tools.jackson.databind.ObjectMapper;

/** Emits the public error contract when a v1 CORS request is refused before authentication. */
public class PublicApiCorsProcessor extends DefaultCorsProcessor {
    private final ObjectMapper objectMapper;
    private final ThreadLocal<HttpServletRequest> publicRequest = new ThreadLocal<>();

    /** Creates the processor with the application's configured JSON mapper. */
    public PublicApiCorsProcessor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean processRequest(
            org.springframework.web.cors.CorsConfiguration configuration,
            HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        boolean isPublicRequest = PublicApiPaths.isPublicRequest(request);
        if (isPublicRequest) {
            publicRequest.set(request);
            SecurityResponseHeaders.apply(request, response);
        }
        try {
            return super.processRequest(configuration, request, response);
        } finally {
            publicRequest.remove();
        }
    }

    @Override
    protected void rejectRequest(ServerHttpResponse response) throws IOException {
        HttpServletRequest request = publicRequest.get();
        if (request != null
                && response instanceof ServletServerHttpResponse servletResponse) {
            PublicApiErrorAdvice.write(
                objectMapper,
                request,
                servletResponse.getServletResponse(),
                HttpStatus.FORBIDDEN,
                "invalid_cors_request",
                "CORS request is not allowed");
            return;
        }
        super.rejectRequest(response);
    }
}
