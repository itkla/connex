package ooo.klae.connex.backend.exceptions;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import org.springframework.context.i18n.LocaleContextHolder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import tools.jackson.core.exc.StreamConstraintsException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.connectedaccounts.nativeflow.NativeConnectException;
import ooo.klae.connex.backend.observability.CorrelationIds;
import ooo.klae.connex.backend.observability.ErrorReporter;
import ooo.klae.connex.backend.services.SupportBundleService;
import ooo.klae.connex.backend.observability.ReportedError;
import ooo.klae.connex.backend.observability.ReportedError.Source;
import ooo.klae.connex.backend.tenant.TenantContext;

/**
 * Maps domain and framework exceptions to sanitized HTTP responses.
 *
 * <p>Logging here is metadata-only: exception class names and stack frames may be emitted, never
 * throwable messages, because those can carry connection strings, key identifiers and other
 * deployment secrets. Log through {@link #stackDetail(Throwable)} rather than passing a throwable
 * to the logger.
 */
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final int MAX_STACK_DETAIL_LENGTH = 8_000;
    private static final int MAX_STACK_FRAMES = 32;
    private static final int MAX_CAUSE_DEPTH = 5;

    private final ErrorReporter errorReporter;
    private final TenantContext tenantContext;

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<Map<String, String>> notFound(ResourceNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(codedError(ex.getCode(), ex.getMessage()));
    }

    @ExceptionHandler(BusinessCardImportResultGoneException.class)
    public ResponseEntity<Map<String, String>> businessCardImportResultGone(
            BusinessCardImportResultGoneException ex) {
        return ResponseEntity.status(HttpStatus.GONE)
            .body(Map.of("code", "BUSINESS_CARD_IMPORT_RESULT_GONE", "message", ex.getMessage()));
    }

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<Map<String, String>> badRequest(BadRequestException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(codedError(ex.getCode(), ex.getMessage()));
    }

    @ExceptionHandler(NativeConnectException.class)
    public ResponseEntity<Map<String, String>> nativeConnect(NativeConnectException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(Map.of("error", ex.getCode()));
    }

    @ExceptionHandler(BreachedPasswordException.class)
    public ResponseEntity<Map<String, String>> breachedPassword(BreachedPasswordException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "code", BreachedPasswordException.CODE,
                "message", BreachedPasswordException.MESSAGE,
                ex.getField(), BreachedPasswordException.MESSAGE));
    }

    @ExceptionHandler(BreachedPasswordCheckUnavailableException.class)
    public ResponseEntity<Map<String, String>> breachedPasswordCheckUnavailable(
            BreachedPasswordCheckUnavailableException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                "code", BreachedPasswordCheckUnavailableException.CODE,
                "message", BreachedPasswordCheckUnavailableException.MESSAGE,
                ex.getField(), BreachedPasswordCheckUnavailableException.MESSAGE));
    }

    @ExceptionHandler(UnsupportedBusinessCardMediaTypeException.class)
    public ResponseEntity<String> unsupportedBusinessCardMediaType(
            UnsupportedBusinessCardMediaTypeException ex) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).body(ex.getMessage());
    }

    @ExceptionHandler(UnprocessableBusinessCardException.class)
    public ResponseEntity<String> unprocessableBusinessCard(UnprocessableBusinessCardException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(ex.getMessage());
    }

    @ExceptionHandler(MalwareDetectedException.class)
    public ResponseEntity<Map<String, String>> malwareDetected(MalwareDetectedException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
            .body(codedError(ex.getCode(), ex.getMessage()));
    }

    @ExceptionHandler(UnscannableUploadException.class)
    public ResponseEntity<Map<String, String>> unscannableUpload(UnscannableUploadException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
            .body(codedError(ex.getCode(), ex.getMessage()));
    }

    @ExceptionHandler(PasskeyEnrollmentRequiredException.class)
    public ResponseEntity<Map<String, String>> passkeyEnrollmentRequired(
            PasskeyEnrollmentRequiredException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(Map.of("code", ex.getCode(), "message", ex.getMessage()));
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<Map<String, String>> forbidden(ForbiddenException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body(codedError(ex.getCode(), ex.getMessage()));
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

    @ExceptionHandler(ApprovalImpactConfirmationRequiredException.class)
    public ResponseEntity<Map<String, String>> approvalImpactConfirmationRequired(
            ApprovalImpactConfirmationRequiredException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> validation(MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(err ->
            fieldErrors.put(err.getField(), Objects.requireNonNullElse(
                err.getDefaultMessage(), "Invalid value"))
        );
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "VALIDATION_FAILED");
        body.put("message", "Please fix the highlighted fields");
        body.put("fieldErrors", fieldErrors);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /**
     * Maps method-parameter constraint violations (annotated path variables and
     * request params under {@code @Validated}) to the same 400 shape as body
     * validation, instead of surfacing them as a 500.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, String>> parameterValidation(ConstraintViolationException ex) {
        Map<String, String> errors = new LinkedHashMap<>();
        ex.getConstraintViolations().forEach(violation ->
            errors.put(String.valueOf(violation.getPropertyPath()), violation.getMessage())
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errors);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<String> illegalState(IllegalStateException ex) {
        log.warn("Illegal state: exception={} detail={}", ex.getClass().getName(), stackDetail(ex));
        return ResponseEntity.status(HttpStatus.CONFLICT).body("The request conflicts with the current state");
    }

    @ExceptionHandler(TooManyRequestsException.class)
    public ResponseEntity<Map<String, String>> tooManyRequests(TooManyRequestsException ex) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
            .body(codedError(ex.getCode(), ex.getMessage()));
    }

    @ExceptionHandler(MailTransportUnavailableException.class)
    public ResponseEntity<Map<String, String>> mailTransportUnavailable(
            MailTransportUnavailableException ex) {
        log.warn("Refusing an email-dependent operation with no usable transport: detail={}",
                stackDetail(ex));
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(codedError(MailTransportUnavailableException.CODE, ex.getMessage()));
    }

    @ExceptionHandler(SecretUnavailableException.class)
    public ResponseEntity<String> secretUnavailable(SecretUnavailableException ex) {
        log.warn("Encrypted secret unavailable: exception={} detail={}",
                ex.getClass().getName(), stackDetail(ex));
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body("Encrypted secret is unavailable");
    }

    @ExceptionHandler(ServiceUnavailableException.class)
    public ResponseEntity<String> serviceUnavailable(ServiceUnavailableException ex) {
        log.warn("Refusing request this deployment cannot serve safely: exception={} detail={}",
                ex.getClass().getName(), stackDetail(ex));
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body("This deployment cannot serve the request");
    }

    @ExceptionHandler(IdentityCollisionReportTimeoutException.class)
    public ResponseEntity<Map<String, String>> identityCollisionReportTimeout(
            IdentityCollisionReportTimeoutException ex) {
        log.warn("Identity collision report deadline exceeded: exception={} detail={}",
                ex.getClass().getName(), stackDetail(ex));
        Map<String, String> body = new LinkedHashMap<>();
        body.put("code", IdentityCollisionReportTimeoutException.CODE);
        body.put("message", IdentityCollisionReportTimeoutException.MESSAGE);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<Map<String, String>> conflict(ConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", ex.getMessage()));
    }

    @ExceptionHandler(OpenDataSubjectRequestException.class)
    public ResponseEntity<Map<String, String>> openDataSubjectRequest(
            OpenDataSubjectRequestException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(Map.of("code", ex.getCode(), "message", ex.getMessage()));
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

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<String> missingPart(MissingServletRequestPartException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body("Missing required multipart part: " + ex.getRequestPartName());
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<String> mediaTypeNotSupported(HttpMediaTypeNotSupportedException ex) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body("Unsupported media type");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<String> methodNotSupported(HttpRequestMethodNotSupportedException ex) {
        return ResponseEntity.status(ex.getStatusCode())
                .headers(ex.getHeaders())
                .body("Request method is not supported");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<String> resourceNotFound(NoResourceFoundException ex) {
        return ResponseEntity.status(ex.getStatusCode()).body("Resource not found");
    }

    /**
     * Reports a support bundle that would exceed its size or time ceiling.
     *
     * <p>The message is deliberately passed through: it tells the operator how to narrow the
     * request, and a generic body would leave them with no next step.
     *
     * @param ex the ceiling breach
     * @return a 413 carrying the actionable message
     */
    @ExceptionHandler(SupportBundleService.SupportBundleTooLargeException.class)
    public ResponseEntity<String> supportBundleTooLarge(
            SupportBundleService.SupportBundleTooLargeException ex) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(ex.getMessage());
    }

    @ExceptionHandler(RequestBodyTooLargeException.class)
    public ResponseEntity<String> requestBodyTooLarge(RequestBodyTooLargeException ex) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body("Request body is too large");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<String> uploadTooLarge(MaxUploadSizeExceededException ex) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body("Request body is too large");
    }

    @ExceptionHandler(UnsupportedUploadMediaTypeException.class)
    public ResponseEntity<String> unsupportedUploadMediaType(UnsupportedUploadMediaTypeException ex) {
        String message = "ja".equals(LocaleContextHolder.getLocale().getLanguage())
            ? "対応しているファイル形式をアップロードしてください"
            : "Upload a supported file type";
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).body(message);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<String> unreadableMessage(HttpMessageNotReadableException ex) {
        if (hasCause(ex, RequestBodyTooLargeException.class) || hasCause(ex, StreamConstraintsException.class)) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body("Request body is too large");
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Malformed request body");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> internalError(Exception ex, HttpServletRequest request) {
        String correlationId = CorrelationIds.current();
        String detail = stackDetail(ex);
        try {
            errorReporter.report(new ReportedError(
                    Source.SERVER,
                    correlationId,
                    tenantContext.getWorkspaceId(),
                    tenantContext.getUserId(),
                    ex.getClass().getName(),
                    detail,
                    request.getRequestURI()));
        } catch (Throwable reportingFailure) {
            log.error("Application error reporter failed: reporter={} reporterDetail={} exception={} detail={}",
                    reportingFailure.getClass().getName(),
                    stackDetail(reportingFailure),
                    ex.getClass().getName(),
                    detail);
        }
        Map<String, String> response = new LinkedHashMap<>();
        response.put("message", "An unexpected error occurred");
        response.put("correlationId", correlationId);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<String> authenticationError(AuthenticationException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Authentication failed");
    }

    private static Map<String, String> codedError(String code, String message) {
        return Map.of("code", code, "message", Objects.requireNonNullElse(message, "Request failed"));
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

    private static String stackDetail(Throwable throwable) {
        StringBuilder detail = new StringBuilder();
        Throwable current = throwable;
        int depth = 0;
        while (current != null && depth < MAX_CAUSE_DEPTH && detail.length() < MAX_STACK_DETAIL_LENGTH) {
            if (depth > 0) {
                appendLine(detail, "Caused by: " + current.getClass().getName());
            }
            StackTraceElement[] frames = current.getStackTrace();
            int count = Math.min(frames.length, MAX_STACK_FRAMES);
            for (int index = 0; index < count && detail.length() < MAX_STACK_DETAIL_LENGTH; index++) {
                appendLine(detail, frames[index].toString());
            }
            current = current.getCause() == current ? null : current.getCause();
            depth++;
        }
        return detail.toString();
    }

    private static void appendLine(StringBuilder detail, String line) {
        int separatorLength = detail.isEmpty() ? 0 : 1;
        int remaining = MAX_STACK_DETAIL_LENGTH - detail.length() - separatorLength;
        if (remaining <= 0) {
            return;
        }
        if (separatorLength != 0) {
            detail.append('\n');
        }
        detail.append(line, 0, Math.min(line.length(), remaining));
    }
}
