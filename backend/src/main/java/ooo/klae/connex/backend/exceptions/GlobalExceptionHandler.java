package ooo.klae.connex.backend.exceptions;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<String> notFound(ResourceNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ex.getMessage());
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

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, String>> dataIntegrity(DataIntegrityViolationException ex) {
        String message = ex.getMostSpecificCause().getMessage();

        if (message != null && message.contains("app_user.email")) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("email", "Email is already registered"));
        }

        if (message != null && message.contains("app_user.username")) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("username", "Username is already taken"));
        }

        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", "This record conflicts with existing data"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> internalError(Exception ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("An unexpected error occurred: " + ex.getMessage());
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<String> authenticationError(AuthenticationException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Authentication failed: " + ex.getMessage());
    }
}