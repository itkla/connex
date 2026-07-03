package ooo.klae.connex.backend.exceptions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * The data-integrity handler must not echo which unique column collided (#81): a duplicate email or
 * username that races past the {@code AuthService} pre-check and trips the DB constraint must return
 * a generic body, never {@code {email}} or {@code {username}}, so the response can't be used to
 * enumerate existing accounts.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

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
}
