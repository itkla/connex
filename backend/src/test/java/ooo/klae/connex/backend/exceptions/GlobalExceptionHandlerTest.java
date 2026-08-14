package ooo.klae.connex.backend.exceptions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.sql.SQLTimeoutException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import ooo.klae.connex.backend.observability.CorrelationIds;
import ooo.klae.connex.backend.observability.ErrorReporter;
import ooo.klae.connex.backend.observability.ReportedError;
import ooo.klae.connex.backend.observability.ReportedError.Source;
import ooo.klae.connex.backend.tenant.TenantContext;
import tools.jackson.databind.ObjectMapper;

/**
 * The data-integrity handler must not echo which unique column collided (#81): a duplicate email or
 * username that races past the {@code AuthService} pre-check and trips the DB constraint must return
 * a generic body, never {@code {email}} or {@code {username}}, so the response can't be used to
 * enumerate existing accounts.
 */
class GlobalExceptionHandlerTest {

    private ErrorReporter errorReporter;
    private TenantContext tenantContext;
    private GlobalExceptionHandler handler;

    @BeforeEach
    void setUp() {
        errorReporter = mock(ErrorReporter.class);
        tenantContext = new TenantContext();
        handler = new GlobalExceptionHandler(errorReporter, tenantContext);
    }

    @AfterEach
    void tearDown() {
        tenantContext.clear();
        LocaleContextHolder.resetLocaleContext();
        MDC.clear();
    }

    @Test
    void missingParameter_mapsTo400WithParameterName() {
        ResponseEntity<String> response = handler.missingParameter(
                new MissingServletRequestParameterException("personA", "int"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("Missing required parameter: personA", response.getBody());
    }

    @Test
    void unsupportedMethod_mapsTo405WithoutLoggingAnInternalError() {
        ResponseEntity<String> response = handler.methodNotSupported(
                new HttpRequestMethodNotSupportedException("GET", List.of("POST")));

        assertEquals(HttpStatus.METHOD_NOT_ALLOWED, response.getStatusCode());
        assertEquals(Set.of(HttpMethod.POST), response.getHeaders().getAllow());
        assertEquals("Request method is not supported", response.getBody());
    }

    @Test
    void unsupportedMediaTypeMapsToExactSanitized415WithoutHeaders() {
        HttpMediaTypeNotSupportedException failure =
            new HttpMediaTypeNotSupportedException(
                MediaType.parseMediaType("text/plain;profile=private"),
                List.of(MediaType.APPLICATION_JSON),
                HttpMethod.POST,
                "secret media failure");

        ResponseEntity<String> response = handler.mediaTypeNotSupported(failure);

        assertEquals(HttpStatus.UNSUPPORTED_MEDIA_TYPE, response.getStatusCode());
        assertTrue(response.getHeaders().isEmpty());
        assertEquals("Unsupported media type", response.getBody());
    }

    @Test
    void uploadTypeRejectionIsLocalizedAndDoesNotEchoParserDetail() {
        UnsupportedUploadMediaTypeException failure =
            new UnsupportedUploadMediaTypeException("ZIP parser secret at /tmp/private");

        ResponseEntity<String> english = handler.unsupportedUploadMediaType(failure);
        LocaleContextHolder.setLocale(Locale.JAPANESE);
        ResponseEntity<String> japanese = handler.unsupportedUploadMediaType(failure);

        assertEquals(HttpStatus.UNSUPPORTED_MEDIA_TYPE, english.getStatusCode());
        assertEquals("Upload a supported file type", english.getBody());
        assertEquals(HttpStatus.UNSUPPORTED_MEDIA_TYPE, japanese.getStatusCode());
        assertEquals("対応しているファイル形式をアップロードしてください", japanese.getBody());
    }

    @Test
    void missingResource_mapsTo404WithoutLoggingAnInternalError() {
        ResponseEntity<String> response = handler.resourceNotFound(
                new NoResourceFoundException(
                        HttpMethod.GET,
                        "/api/identity-collisions/members",
                        "/api/identity-collisions/members"));

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals("Resource not found", response.getBody());
    }

    @Test
    void dataIntegrity_onEmailConstraint_doesNotRevealField() {
        ResponseEntity<Map<String, String>> response = handler.dataIntegrity(
            new DataIntegrityViolationException("Duplicate entry 'x@y.com' for key 'app_user.email'"));

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        Map<String, String> body = response.getBody();
        assertNotNull(body);
        assertFalse(body.containsKey("email"), "must not reveal the email field");
        assertFalse(body.containsKey("username"), "must not reveal the username field");
        assertTrue(body.containsKey("message"), "generic message body expected");
    }

    @Test
    void dataIntegrity_onUsernameConstraint_doesNotRevealField() {
        ResponseEntity<Map<String, String>> response = handler.dataIntegrity(
            new DataIntegrityViolationException("Duplicate entry 'alice' for key 'app_user.username'"));

        Map<String, String> body = response.getBody();
        assertNotNull(body);
        assertFalse(body.containsKey("username"), "must not reveal the username field");
        assertTrue(body.containsKey("message"), "generic message body expected");
    }

    /**
     * A field-less {@link DuplicateResourceException} (what {@code AuthService.register} now throws on
     * any registration conflict) maps to a generic {@code {message}} body — no {@code username}/{@code email}
     * key — so the duplicate-username and duplicate-email responses are byte-identical.
     */
    @Test
    void duplicate_withoutField_returnsGenericMessageBody() {
        ResponseEntity<Map<String, String>> response = handler.duplicate(
            new DuplicateResourceException("Registration could not be completed"));

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        Map<String, String> body = response.getBody();
        assertNotNull(body);
        assertFalse(body.containsKey("username"));
        assertFalse(body.containsKey("email"));
        assertEquals("Registration could not be completed", body.get("message"));
    }

    @Test
    void illegalState_returnsGenericBody_notRawMessage() {
        ResponseEntity<String> response = handler.illegalState(
            new IllegalStateException("Failed to decrypt secret: bad AES key length"));

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals("The request conflicts with the current state", response.getBody());
    }

    @Test
    void tooManyRequests_mapsTo429() {
        ResponseEntity<String> response = handler.tooManyRequests(
            new TooManyRequestsException("slow down"));

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.getStatusCode());
        assertEquals("slow down", response.getBody());
    }

    @Test
    void openDataSubjectRequestReturnsStableTeardownRefusalCode() {
        ResponseEntity<Map<String, String>> response = handler.openDataSubjectRequest(
            new OpenDataSubjectRequestException(
                "An open data-subject request still references this workspace"));

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        Map<String, String> body = response.getBody();
        assertNotNull(body);
        assertEquals(OpenDataSubjectRequestException.CODE, body.get("code"));
        assertEquals(
            "An open data-subject request still references this workspace",
            body.get("message"));
    }

    @Test
    void ssoEnforced_returnsStableCode() {
        ResponseEntity<Map<String, String>> response = handler.ssoEnforced(new SsoEnforcedException());

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        Map<String, String> body = response.getBody();
        assertNotNull(body);
        assertEquals(SsoEnforcedException.CODE, body.get("code"));
        assertEquals("This account must sign in with SSO", body.get("message"));
    }

    @Test
    void recentAuthenticationRequired_returnsStableCode() {
        ResponseEntity<Map<String, String>> response = handler.recentAuthenticationRequired(
            new RecentAuthenticationRequiredException());

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        Map<String, String> body = response.getBody();
        assertNotNull(body);
        assertEquals(RecentAuthenticationRequiredException.CODE, body.get("code"));
        assertEquals("Recent WebAuthn authentication required", body.get("message"));
    }

    @Test
    void passkeyEnrollmentRequired_returnsStableCode() {
        ResponseEntity<Map<String, String>> response = handler.passkeyEnrollmentRequired(
            new PasskeyEnrollmentRequiredException());

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        Map<String, String> body = response.getBody();
        assertNotNull(body);
        assertEquals(PasskeyEnrollmentRequiredException.CODE, body.get("code"));
    }

    @Test
    void secretUnavailable_returnsSanitizedBody() {
        ResponseEntity<String> response = handler.secretUnavailable(
            new SecretUnavailableException("missing key id prod-v1"));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals("Encrypted secret is unavailable", response.getBody());
    }

    @Test
    void identityCollisionReportTimeoutReturnsExactStructured503WithoutLoggingPii()
            throws Exception {
        IdentityCollisionReportTimeoutException failure =
            new IdentityCollisionReportTimeoutException(
                new SQLTimeoutException("canonical@example.com"));
        failure.setStackTrace(new StackTraceElement[] {
            new StackTraceElement("example.IdentityReport", "list", "IdentityReport.java", 19)
        });

        List<ILoggingEvent> events =
            captureHandlerLogs(() -> handler.identityCollisionReportTimeout(failure));
        ResponseEntity<Map<String, String>> response =
            handler.identityCollisionReportTimeout(failure);

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals(Map.of(
            "code",
            "IDENTITY_COLLISION_REPORT_TIMEOUT",
            "message",
            "Identity collision report timed out; narrow the filters and retry"),
            response.getBody());
        assertEquals(
            "{\"code\":\"IDENTITY_COLLISION_REPORT_TIMEOUT\","
                + "\"message\":\"Identity collision report timed out; "
                + "narrow the filters and retry\"}",
            new ObjectMapper().writeValueAsString(response.getBody()));
        assertEquals(1, events.size());
        assertNull(events.getFirst().getThrowableProxy());
        assertFalse(events.getFirst().getFormattedMessage().contains(
            "canonical@example.com"));
    }

    @Test
    void unreadableMessage_onRequestBodyLimit_mapsTo413() {
        ResponseEntity<String> response = handler.unreadableMessage(
            new HttpMessageNotReadableException("too large", new RequestBodyTooLargeException(8), null));

        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, response.getStatusCode());
        assertEquals("Request body is too large", response.getBody());
    }

    @Test
    void oversizedMultipartUpload_mapsTo413() {
        ResponseEntity<String> response = handler.uploadTooLarge(
                new MaxUploadSizeExceededException(64L * 1024L * 1024L));

        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, response.getStatusCode());
        assertEquals("Request body is too large", response.getBody());
    }

    @Test
    void unreadableMessage_onMalformedBody_mapsTo400() {
        ResponseEntity<String> response = handler.unreadableMessage(
            new HttpMessageNotReadableException("bad json", null));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("Malformed request body", response.getBody());
    }

    @Test
    void internalErrorReturnsCorrelationIdAndReportsSanitizedServerMetadata() {
        MDC.put(CorrelationIds.MDC_KEY, "request_id_123");
        tenantContext.set(7, 8, 9, "member", null);
        IllegalArgumentException exception = new IllegalArgumentException("jdbc:mysql://secret");
        exception.setStackTrace(new StackTraceElement[] {
            new StackTraceElement("example.Controller", "handle", "Controller.java", 42)
        });
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/fail");
        request.setQueryString("token=secret");

        ResponseEntity<Map<String, String>> response = handler.internalError(exception, request);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals(Map.of(
                "message", "An unexpected error occurred",
                "correlationId", "request_id_123"), response.getBody());
        ArgumentCaptor<ReportedError> captor = ArgumentCaptor.forClass(ReportedError.class);
        verify(errorReporter).report(captor.capture());
        assertEquals(new ReportedError(
                Source.SERVER,
                "request_id_123",
                7,
                9,
                IllegalArgumentException.class.getName(),
                "example.Controller.handle(Controller.java:42)",
                "/api/fail"), captor.getValue());
        assertFalse(captor.getValue().detail().contains("secret"));
        assertFalse(captor.getValue().path().contains("token"));
    }

    @Test
    void internalErrorReportsCauseChainClassesAndFramesWithoutMessages() {
        IllegalStateException root = new IllegalStateException("password=secret");
        root.setStackTrace(new StackTraceElement[] {
            new StackTraceElement("example.Repository", "query", "Repository.java", 7)
        });
        RuntimeException wrapper = new RuntimeException("outer secret", root);
        wrapper.setStackTrace(new StackTraceElement[] {
            new StackTraceElement("example.Service", "run", "Service.java", 3)
        });
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/fail");

        handler.internalError(wrapper, request);

        ArgumentCaptor<ReportedError> captor = ArgumentCaptor.forClass(ReportedError.class);
        verify(errorReporter).report(captor.capture());
        String detail = captor.getValue().detail();
        assertEquals(String.join("\n",
                "example.Service.run(Service.java:3)",
                "Caused by: " + IllegalStateException.class.getName(),
                "example.Repository.query(Repository.java:7)"), detail);
        assertFalse(detail.contains("secret"));
    }

    @Test
    void reporterFailureCannotAlterStableInternalErrorResponse() {
        doThrow(new IllegalStateException("vendor secret")).when(errorReporter).report(
                org.mockito.ArgumentMatchers.any());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/fail");

        ResponseEntity<Map<String, String>> response =
                assertDoesNotThrow(() -> handler.internalError(new RuntimeException("database secret"), request));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("An unexpected error occurred", response.getBody().get("message"));
        assertTrue(CorrelationIds.isValid(response.getBody().get("correlationId")));
    }

    @Test
    void reporterFailureFallbackKeepsBothFrameSetsAndNoThrowableMessages() {
        doThrow(new IllegalStateException("vendor secret")).when(errorReporter)
                .report(org.mockito.ArgumentMatchers.any());
        RuntimeException failure = new RuntimeException("database secret");
        failure.setStackTrace(new StackTraceElement[] {
            new StackTraceElement("example.Controller", "handle", "Controller.java", 42)
        });
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/fail");

        List<ILoggingEvent> events = captureHandlerLogs(() -> handler.internalError(failure, request));

        assertEquals(1, events.size());
        ILoggingEvent event = events.getFirst();
        String message = event.getFormattedMessage();
        assertNull(event.getThrowableProxy(), "no throwable may reach the log");
        assertTrue(Pattern.compile("reporterDetail=\\S*\\.java:\\d+").matcher(message).find(),
                "the reporter's own frames must survive the fallback");
        assertTrue(message.contains("example.Controller.handle(Controller.java:42)"));
        assertTrue(message.contains(IllegalStateException.class.getName()));
        assertTrue(message.contains(RuntimeException.class.getName()));
        assertFalse(message.contains("vendor secret"));
        assertFalse(message.contains("database secret"));
    }

    @Test
    void alwaysOnWarnPathsLogFramesWithoutThrowableMessages() {
        IllegalStateException failure = new IllegalStateException("Failed to decrypt secret: bad AES key length");
        failure.setStackTrace(new StackTraceElement[] {
            new StackTraceElement("example.Service", "run", "Service.java", 3)
        });

        List<ILoggingEvent> events = captureHandlerLogs(() -> handler.illegalState(failure));

        assertEquals(1, events.size());
        ILoggingEvent event = events.getFirst();
        assertNull(event.getThrowableProxy(), "no throwable may reach the log");
        assertTrue(event.getFormattedMessage().contains("example.Service.run(Service.java:3)"));
        assertFalse(event.getFormattedMessage().contains("bad AES key length"));
    }

    @Test
    void internalErrorMapsUnrecognizedApiPathsToUnknown() {
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/api/invites/aBc123defGhi456jklMno/accept");

        handler.internalError(new RuntimeException("boom"), request);

        ArgumentCaptor<ReportedError> captor = ArgumentCaptor.forClass(ReportedError.class);
        verify(errorReporter).report(captor.capture());
        assertEquals("unknown", captor.getValue().path());
    }

    private static List<ILoggingEvent> captureHandlerLogs(Runnable action) {
        Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            action.run();
            return List.copyOf(appender.list);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
