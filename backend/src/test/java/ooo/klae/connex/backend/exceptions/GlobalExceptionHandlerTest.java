package ooo.klae.connex.backend.exceptions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;

/**
 * The data-integrity handler must not echo which unique column collided (#81): a duplicate email or
 * username that races past the {@code AuthService} pre-check and trips the DB constraint must return
 * a generic body, never {@code {email}} or {@code {username}}, so the response can't be used to
 * enumerate existing accounts.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

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
    void unreadableMessage_onRequestBodyLimit_mapsTo413() {
        ResponseEntity<String> response = handler.unreadableMessage(
            new HttpMessageNotReadableException("too large", new RequestBodyTooLargeException(8), null));

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
}
