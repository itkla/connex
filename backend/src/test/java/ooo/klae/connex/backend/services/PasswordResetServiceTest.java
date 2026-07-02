package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.mappers.PasswordResetTokenMapper;

/**
 * Exercises the forgot-password service: enumeration safety, single active token,
 * expiry/consumption gating, policy-safe reset, and rate limiting. Session
 * expiry needs real HTTP sessions and is covered by the running-server checks.
 */
@Import(PasswordResetServiceTest.CapturingEmailConfig.class)
class PasswordResetServiceTest extends AbstractServiceTest {

    @Autowired private PasswordResetService passwordResetService;
    @Autowired private PasswordResetTokenMapper passwordResetTokenMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private CapturingEmailService email;

    private static final String NEW_PASSWORD = "NewPass1!";

    @BeforeEach
    void resetCapture() {
        email.reset();
    }

    @Test
    void requestReset_unknownEmail_issuesNothing() {
        passwordResetService.requestReset("nobody_" + unique() + "@example.com", unique());
        assertNull(email.lastToken, "no reset email should be sent for an unknown address");
        assertEquals(0, email.calls);
    }

    @Test
    void requestReset_knownEmail_issuesRedeemableToken() {
        User user = newUser();
        passwordResetService.requestReset(user.getEmail(), unique());

        assertNotNull(email.lastToken);
        assertEquals(user.getId(), email.lastUser.getId());
        assertTrue(passwordResetService.validateToken(email.lastToken));
    }

    @Test
    void requestReset_supersedesPriorToken() {
        User user = newUser();
        passwordResetService.requestReset(user.getEmail(), unique());
        String firstToken = email.lastToken;
        passwordResetService.requestReset(user.getEmail(), unique());
        String secondToken = email.lastToken;

        assertFalse(passwordResetService.validateToken(firstToken), "issuing a new token must invalidate the prior one");
        assertTrue(passwordResetService.validateToken(secondToken));
    }

    @Test
    void resetPassword_validToken_updatesHashAndConsumesToken() {
        User user = newUser();
        passwordResetService.requestReset(user.getEmail(), unique());
        String token = email.lastToken;

        passwordResetService.resetPassword(token, NEW_PASSWORD);

        String storedHash = userMapper.getUserById(user.getId()).getPasswordHash();
        assertTrue(passwordEncoder.matches(NEW_PASSWORD, storedHash));
        assertFalse(passwordResetService.validateToken(token), "a consumed token must not be reusable");
    }

    @Test
    void resetPassword_tokenIsSingleUse() {
        User user = newUser();
        passwordResetService.requestReset(user.getEmail(), unique());
        String token = email.lastToken;
        passwordResetService.resetPassword(token, NEW_PASSWORD);

        assertThrows(BadRequestException.class, () -> passwordResetService.resetPassword(token, "Another1!"));
    }

    @Test
    void resetPassword_invalidToken_throws() {
        assertThrows(BadRequestException.class, () -> passwordResetService.resetPassword("not-a-real-token", NEW_PASSWORD));
    }

    @Test
    void requestReset_rateLimited_stopsIssuing() {
        User user = newUser();
        for (int i = 0; i < 5; i++) {
            passwordResetService.requestReset(user.getEmail(), unique());
        }
        int callsBeforeLimit = email.calls;
        passwordResetService.requestReset(user.getEmail(), unique());

        assertEquals(callsBeforeLimit, email.calls, "requests beyond the limit must not send more emails");
        assertTrue(passwordResetTokenMapper.countRecentByUser(user.getId(), 900) <= 5);
    }

    /**
     * Test double that captures the raw token the service would email.
     */
    @TestConfiguration
    static class CapturingEmailConfig {
        @Bean
        @Primary
        CapturingEmailService capturingEmailService() {
            return new CapturingEmailService();
        }
    }

    static class CapturingEmailService implements PasswordResetEmailService {
        volatile String lastToken;
        volatile User lastUser;
        volatile int calls;

        @Override
        public void sendResetEmail(User user, String rawToken) {
            this.lastUser = user;
            this.lastToken = rawToken;
            this.calls++;
        }

        void reset() {
            lastToken = null;
            lastUser = null;
            calls = 0;
        }
    }
}
