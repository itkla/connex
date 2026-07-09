package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.BadCredentialsException;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.RegisterDto;
import ooo.klae.connex.backend.exceptions.DuplicateResourceException;

/**
 * Registration conflicts must surface a single generic, field-less error so an unauthenticated
 * caller cannot enumerate which usernames or emails already exist (#81). A duplicate username and
 * a duplicate email must be indistinguishable from each other.
 */
class AuthServiceTest extends AbstractServiceTest {

    @Autowired private AuthService authService;

    private RegisterDto registration(String username, String email) {
        RegisterDto dto = new RegisterDto();
        dto.setUsername(username);
        dto.setEmail(email);
        dto.setDisplayName("Test " + username);
        dto.setPassword("Aa1!aaaa");
        dto.setTimezone("UTC");
        return dto;
    }

    @Test
    void register_duplicateUsername_throwsFieldlessGenericConflict() {
        String username = "taken_" + unique();
        authService.register(registration(username, unique() + "@example.com"), true);

        DuplicateResourceException ex = assertThrows(DuplicateResourceException.class,
            () -> authService.register(registration(username, unique() + "@example.com"), true));
        assertNull(ex.getField(), "a duplicate username must not be revealed via the error field");
        assertEquals("Registration could not be completed", ex.getMessage());
    }

    @Test
    void register_duplicateEmail_throwsIdenticalFieldlessConflict() {
        String email = "taken_" + unique() + "@example.com";
        authService.register(registration("user_" + unique(), email), true);

        DuplicateResourceException ex = assertThrows(DuplicateResourceException.class,
            () -> authService.register(registration("user_" + unique(), email), true));
        assertNull(ex.getField(), "a duplicate email must not be revealed via the error field");
        assertEquals("Registration could not be completed", ex.getMessage());
    }

    @Test
    void selfServiceRegistration_whenVerificationDisabled_startsVerified() {
        User user = authService.registerSelfService(
            registration("ss_" + unique(), unique() + "@example.com"), "1.2.3.4");

        assertTrue(userMapper.getUserById(user.getId()).isEmailVerified(),
            "with verification off, self-serve accounts are verified so enabling it later never gates them");
    }

    @Test
    void requireCurrentPassword_acceptsTheAccountPassword() {
        User user = authService.register(registration("pw_" + unique(), unique() + "@example.com"), true);

        assertDoesNotThrow(() -> authService.requireCurrentPassword(user.getId(), "Aa1!aaaa", "203.0.113.10"));
    }

    @Test
    void requireCurrentPassword_rejectsWrongPassword() {
        User user = authService.register(registration("badpw_" + unique(), unique() + "@example.com"), true);

        assertThrows(BadCredentialsException.class,
            () -> authService.requireCurrentPassword(user.getId(), "wrong", "203.0.113.10"));
    }
}
