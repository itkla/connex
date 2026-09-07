package ooo.klae.connex.backend.publicapi;

import java.io.IOException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolationException;
import ooo.klae.connex.backend.config.SecurityResponseHeaders;
import ooo.klae.connex.backend.controllers.v1.PublicApiIdentityController;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.exceptions.ServiceUnavailableException;
import ooo.klae.connex.backend.observability.CorrelationIds;
import tools.jackson.databind.ObjectMapper;

/** Maps failures on public v1 controllers to the stable public error envelope. */
@RestControllerAdvice(basePackageClasses = PublicApiIdentityController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PublicApiErrorAdvice {
    private static final Logger log = LoggerFactory.getLogger(PublicApiErrorAdvice.class);
    private static final Set<String> RESET_SURVIVING_HEADERS = Set.of(
        CorrelationIds.HEADER_NAME.toLowerCase(Locale.ROOT),
        HttpHeaders.VARY.toLowerCase(Locale.ROOT),
        HttpHeaders.RETRY_AFTER.toLowerCase(Locale.ROOT),
        "strict-transport-security");

    /** Maps public request validation failures. */
    @ExceptionHandler({
        BadRequestException.class,
        BindException.class,
        ConstraintViolationException.class,
        HttpMessageNotReadableException.class,
        IllegalArgumentException.class
    })
    public ResponseEntity<ErrorEnvelope> invalidRequest(Exception exception) {
        String message = exception instanceof BadRequestException
            ? Objects.requireNonNullElse(exception.getMessage(), "Invalid request")
            : "Invalid request";
        return response(HttpStatus.BAD_REQUEST, "invalid_request", message);
    }

    /** Maps a missing public resource without exposing tenant existence. */
    @ExceptionHandler({ResourceNotFoundException.class, NoResourceFoundException.class})
    public ResponseEntity<ErrorEnvelope> notFound(Exception exception) {
        return response(HttpStatus.NOT_FOUND, "not_found", "Resource not found");
    }

    /** Maps live RBAC or tenant-scope refusals. */
    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ErrorEnvelope> forbidden(ForbiddenException exception) {
        return response(HttpStatus.FORBIDDEN, "insufficient_scope", "The credential cannot access this resource");
    }

    /** Maps disabled or unroutable public API deployments. */
    @ExceptionHandler(ServiceUnavailableException.class)
    public ResponseEntity<ErrorEnvelope> unavailable(ServiceUnavailableException exception) {
        return response(HttpStatus.SERVICE_UNAVAILABLE, "public_api_unavailable", "Public API is unavailable");
    }

    /** Maps unsupported methods inside the public namespace. */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorEnvelope> methodNotAllowed(HttpRequestMethodNotSupportedException exception) {
        return response(HttpStatus.METHOD_NOT_ALLOWED, "method_not_allowed", "Request method is not supported");
    }

    /** Maps unexpected public failures without returning exception details. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorEnvelope> internalError(Exception exception) {
        log.error("Public API request failed: exception={}", exception.getClass().getName());
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "An unexpected error occurred");
    }

    /** Writes the same envelope for failures produced inside the security filter chain. */
    public static void write(
            ObjectMapper objectMapper,
            HttpServletRequest request,
            HttpServletResponse response,
            HttpStatus status,
            String code,
            String message) throws IOException {
        write(objectMapper, request, response, status, code, message, null, Map.of());
    }

    /** Writes an error envelope after replacing every earlier representation header and byte. */
    public static void write(
            ObjectMapper objectMapper,
            HttpServletRequest request,
            HttpServletResponse response,
            HttpStatus status,
            String code,
            String message,
            Map<String, String> headers) throws IOException {
        write(objectMapper, request, response, status, code, message, null, headers);
    }

    static void write(
            ObjectMapper objectMapper,
            HttpServletRequest request,
            HttpServletResponse response,
            HttpStatus status,
            String code,
            String message,
            long credentialId) throws IOException {
        write(
            objectMapper,
            request,
            response,
            status,
            code,
            message,
            Long.valueOf(credentialId),
            Map.of());
    }

    static void write(
            ObjectMapper objectMapper,
            HttpServletRequest request,
            HttpServletResponse response,
            HttpStatus status,
            String code,
            String message,
            long credentialId,
            Map<String, String> headers) throws IOException {
        write(
            objectMapper,
            request,
            response,
            status,
            code,
            message,
            Long.valueOf(credentialId),
            headers);
    }

    private static void write(
            ObjectMapper objectMapper,
            HttpServletRequest request,
            HttpServletResponse response,
            HttpStatus status,
            String code,
            String message,
            Long credentialId,
            Map<String, String> headers) throws IOException {
        ErrorEnvelope errorEnvelope = envelope(code, message);
        if (response.isCommitted()) {
            if (credentialId == null) {
                log.warn(
                    "Skipped public API error envelope because the response is committed requestId={}",
                    errorEnvelope.error().requestId());
            } else {
                log.warn(
                    "Skipped public API error envelope because the response is committed credentialId={} requestId={}",
                    credentialId,
                    errorEnvelope.error().requestId());
            }
            return;
        }
        Map<String, List<String>> survivingHeaders = snapshotSurvivingHeaders(response);
        response.reset();
        SecurityResponseHeaders.apply(request, response);
        restoreHeaders(response, survivingHeaders);
        headers.forEach(response::setHeader);
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(
            response.getOutputStream(),
            errorEnvelope);
    }

    private static Map<String, List<String>> snapshotSurvivingHeaders(
            HttpServletResponse response) {
        Collection<String> headerNames = response.getHeaderNames();
        if (headerNames == null || headerNames.isEmpty()) {
            return Map.of();
        }
        Map<String, List<String>> headers = new LinkedHashMap<>();
        for (String headerName : headerNames) {
            Collection<String> values = headerName == null
                ? List.of()
                : response.getHeaders(headerName);
            if (headerName != null && survivesReset(headerName)
                    && values != null && !values.isEmpty()) {
                headers.put(headerName, List.copyOf(values));
            }
        }
        return headers;
    }

    private static boolean survivesReset(String headerName) {
        String normalized = headerName.toLowerCase(Locale.ROOT);
        return RESET_SURVIVING_HEADERS.contains(normalized)
            || normalized.startsWith("access-control-")
            || normalized.startsWith("x-ratelimit-");
    }

    private static void restoreHeaders(
            HttpServletResponse response, Map<String, List<String>> headers) {
        headers.forEach((headerName, values) -> {
            if (!values.isEmpty()) {
                response.setHeader(headerName, values.getFirst());
                values.stream().skip(1).forEach(value -> response.addHeader(headerName, value));
            }
        });
    }

    private static ResponseEntity<ErrorEnvelope> response(
            HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(envelope(code, message));
    }

    /** Creates a public envelope and maps internal error identifiers to lower snake case. */
    public static ErrorEnvelope envelope(String code, String message) {
        return new ErrorEnvelope(new ErrorBody(
            Objects.requireNonNull(code).toLowerCase(Locale.ROOT),
            message,
            CorrelationIds.current()));
    }

    /** Top-level public API error envelope. */
    public record ErrorEnvelope(ErrorBody error) {
    }

    /** Stable public API error fields. */
    public record ErrorBody(
            String code,
            String message,
            @JsonProperty("request_id") String requestId) {
    }
}
