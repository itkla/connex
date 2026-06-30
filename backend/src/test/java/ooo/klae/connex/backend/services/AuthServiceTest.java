package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

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
        authService.register(registration(username, unique() + "@example.com"));

        DuplicateResourceException ex = assertThrows(DuplicateResourceException.class,
            () -> authService.register(registration(username, unique() + "@example.com")));
        assertNull(ex.getField(), "a duplicate username must not be revealed via the error field");
        assertEquals("Registration could not be completed", ex.getMessage());
    }

    @Test
    void register_duplicateEmail_throwsIdenticalFieldlessConflict() {
        String email = "taken_" + unique() + "@example.com";
        authService.register(registration("user_" + unique(), email));

        DuplicateResourceException ex = assertThrows(DuplicateResourceException.class,
            () -> authService.register(registration("user_" + unique(), email)));
        assertNull(ex.getField(), "a duplicate email must not be revealed via the error field");
        assertEquals("Registration could not be completed", ex.getMessage());
    }
}
