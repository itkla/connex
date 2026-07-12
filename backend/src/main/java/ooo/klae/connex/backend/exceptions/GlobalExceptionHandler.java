package ooo.klae.connex.backend.exceptions;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import tools.jackson.core.exc.StreamConstraintsException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<String> notFound(ResourceNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ex.getMessage());
    }

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<String> badRequest(BadRequestException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ex.getMessage());
    }

    @ExceptionHandler(PasskeyEnrollmentRequiredException.class)
    public ResponseEntity<Map<String, String>> passkeyEnrollmentRequired(
            PasskeyEnrollmentRequiredException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(Map.of("code", ex.getCode(), "message", ex.getMessage()));
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<String> forbidden(ForbiddenException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ex.getMessage());
    }

    @ExceptionHandler(RecentAuthenticationRequiredException.class)
    public ResponseEntity<Map<String, String>> recentAuthenticationRequired(
            RecentAuthenticationRequiredException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body(Map.of("code", ex.getCode(), "message", ex.getMessage()));
    }

    @ExceptionHandler(SsoEnforcedException.class)
    public ResponseEntity<Map<String, String>> ssoEnforced(SsoEnforcedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body(Map.of("code", ex.getCode(), "message", ex.getMessage()));
    }

    @ExceptionHandler(DuplicateResourceException.class)
    public ResponseEntity<Map<String, String>> duplicate(DuplicateResourceException ex) {
        if (ex.getField() != null) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(ex.getField(), ex.getMessage()));
        }

        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> validation(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new LinkedHashMap<>(); // using LinkedHashMap to preserve order of errors
        ex.getBindingResult().getFieldErrors().forEach(err ->
            errors.put(err.getField(), err.getDefaultMessage())
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errors);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<String> illegalState(IllegalStateException ex) {
        log.warn("Illegal state", ex);
        return ResponseEntity.status(HttpStatus.CONFLICT).body("The request conflicts with the current state");
    }

    @ExceptionHandler(TooManyRequestsException.class)
    public ResponseEntity<String> tooManyRequests(TooManyRequestsException ex) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(ex.getMessage());
    }

    @ExceptionHandler(SecretUnavailableException.class)
    public ResponseEntity<String> secretUnavailable(SecretUnavailableException ex) {
        log.warn("Encrypted secret unavailable", ex);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body("Encrypted secret is unavailable");
    }

    @ExceptionHandler(ServiceUnavailableException.class)
    public ResponseEntity<String> serviceUnavailable(ServiceUnavailableException ex) {
        log.warn("Refusing request this deployment cannot serve safely", ex);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body("This deployment cannot serve the request");
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<Map<String, String>> conflict(ConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", ex.getMessage()));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, String>> dataIntegrity(DataIntegrityViolationException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", "This record conflicts with existing data"));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<String> typeMismatch(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Invalid value for parameter: " + ex.getName());
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<String> missingParameter(MissingServletRequestParameterException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body("Missing required parameter: " + ex.getParameterName());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<String> methodNotSupported(HttpRequestMethodNotSupportedException ex) {
        return ResponseEntity.status(ex.getStatusCode())
                .headers(ex.getHeaders())
                .body("Request method is not supported");
    }

    @ExceptionHandler(RequestBodyTooLargeException.class)
    public ResponseEntity<String> requestBodyTooLarge(RequestBodyTooLargeException ex) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body("Request body is too large");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<String> unreadableMessage(HttpMessageNotReadableException ex) {
        if (hasCause(ex, RequestBodyTooLargeException.class) || hasCause(ex, StreamConstraintsException.class)) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body("Request body is too large");
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Malformed request body");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> internalError(Exception ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("An unexpected error occurred");
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<String> authenticationError(AuthenticationException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Authentication failed");
    }

    private static boolean hasCause(Throwable throwable, Class<? extends Throwable> type) {
        Throwable current = throwable;
        while (current != null) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
