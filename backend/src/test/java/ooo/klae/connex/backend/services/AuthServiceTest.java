package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.BadCredentialsException;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.RegisterDto;
import ooo.klae.connex.backend.exceptions.DuplicateResourceException;
import ooo.klae.connex.backend.exceptions.BreachedPasswordException;

/**
 * Registration conflicts must surface a single generic, field-less error so an unauthenticated
 * caller cannot enumerate which usernames or emails already exist (#81). A duplicate username and
 * a duplicate email must be indistinguishable from each other.
 */
class AuthServiceTest extends AbstractServiceTest {

    @Autowired private AuthService authService;
    @Autowired private SessionSecurityService sessionSecurityService;

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
    void selfServiceRegistration_rejectsKnownBreachedPassword() {
        RegisterDto request = registration("breached_" + unique(), unique() + "@example.com");
        request.setPassword("Password1!");

        BreachedPasswordException exception = assertThrows(BreachedPasswordException.class,
                () -> authService.registerSelfService(request, "1.2.3.4"));

        assertEquals("password", exception.getField());
        assertFalse(exception.getMessage().contains(request.getPassword()));
    }

    @Test
    void administratorRegistration_rejectsKnownBreachedPassword() {
        RegisterDto request = registration("admin_breached_" + unique(), unique() + "@example.com");
        request.setPassword("Password1!");

        assertThrows(BreachedPasswordException.class,
                () -> authService.register(request, true));
    }

    @Test
    void bootstrapOwnerProvisioning_rejectsKnownBreachedPassword() {
        RegisterDto request = registration("bootstrap_breached_" + unique(), unique() + "@example.com");
        request.setPassword("Password1!");

        assertThrows(BreachedPasswordException.class,
                () -> authService.provisionBootstrapOwner(request));
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

    @Test
    void firstPasskeyBootstrap_passwordBackedAccountStillRequiresPasswordAfterFreshLogin() {
        User user = authService.register(registration("bootstrap_pw_" + unique(),
            unique() + "@example.com"), true);
        MockHttpServletRequest request = new MockHttpServletRequest();
        sessionSecurityService.markAuthenticated(request, user.getId());

        assertThrows(BadCredentialsException.class,
            () -> authService.requireFirstPasskeyBootstrapAuthentication(user.getId(), "wrong", request));
        assertDoesNotThrow(() -> authService.requireFirstPasskeyBootstrapAuthentication(
            user.getId(), "Aa1!aaaa", request));
    }

    @Test
    void firstPasskeyBootstrap_passwordlessAccountAcceptsFreshSameUserSession() {
        User user = passwordlessUser();
        MockHttpServletRequest request = new MockHttpServletRequest();
        sessionSecurityService.markAuthenticated(request, user.getId());

        assertDoesNotThrow(() -> authService.requireFirstPasskeyBootstrapAuthentication(
            user.getId(), null, request));
    }

    @Test
    void firstPasskeyBootstrap_passwordlessAccountRejectsUnboundSession() {
        User user = passwordlessUser();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession();

        assertThrows(ooo.klae.connex.backend.exceptions.ForbiddenException.class,
            () -> authService.requireFirstPasskeyBootstrapAuthentication(user.getId(), null, request));
    }

    @Test
    void hasPasswordCredentialReflectsStoredCredentialType() {
        User passwordBacked = authService.register(registration("credential_pw_" + unique(),
            unique() + "@example.com"), true);
        User passwordless = passwordlessUser();

        assertTrue(authService.hasPasswordCredential(passwordBacked.getId()));
        assertFalse(authService.hasPasswordCredential(passwordless.getId()));
    }

    @Test
    void principalSwitchClearsPriorSessionStateAndWorkspaceCookieForAccountWithoutMemberships() {
        User passwordless = passwordlessUser();
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        sessionSecurityService.markAuthenticated(request, currentUser.getId());
        MockHttpSession priorSession = (MockHttpSession) request.getSession();
        priorSession.setAttribute("pending-passkey-options", "secret state");

        authService.establishAuthenticatedSession(passwordless, request, response);

        assertNotSame(priorSession, request.getSession(false));
        assertNull(request.getSession(false).getAttribute("pending-passkey-options"));
        assertTrue(response.getHeaders("Set-Cookie").stream()
            .anyMatch(header -> header.startsWith("connex_workspace=;") && header.contains("Max-Age=0")));
    }

    @Test
    void federatedSessionCeremonyDoesNotCreatePasskeyStepUp() {
        User passwordless = passwordlessUser();
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        authService.establishAuthenticatedSession(passwordless, request, response);

        assertEquals(passwordless.getId(), request.getSession(false).getAttribute(
                SessionSecurityService.AUTHENTICATED_USER_ATTR));
        assertNull(request.getSession(false).getAttribute(
                SessionSecurityService.WEBAUTHN_STEP_UP_USER_ATTR));
        assertNull(request.getSession(false).getAttribute(
                SessionSecurityService.WEBAUTHN_STEP_UP_AT_ATTR));
    }

    private User passwordlessUser() {
        String value = unique();
        User user = new User();
        user.setUsername("passwordless_" + value);
        user.setDisplayName("Passwordless " + value);
        user.setEmail(value + "@example.com");
        user.setEmailVerified(true);
        user.setTimezone("UTC");
        user.setPasswordHash(null);
        userMapper.insert(user);
        return userMapper.getUserById(user.getId());
    }
}
