package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.crypto.password.PasswordEncoder;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.DuplicateResourceException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;

/**
 * Exercises the verified email-change service: current-password step-up, uniqueness,
 * single active token, and the ownership-proving request → confirm flow that applies
 * the new address. Also asserts the unverified profile-update path cannot change email.
 */
@Import(EmailChangeServiceTest.CapturingEmailConfig.class)
class EmailChangeServiceTest extends AbstractServiceTest {

    @Autowired private EmailChangeService emailChangeService;
    @Autowired private UserService userService;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private CapturingEmailChangeService email;

    private static final String PASSWORD = "Str0ng-Pw1!";

    @BeforeEach
    void giveCurrentUserRealPasswordAndResetCapture() {
        userMapper.updatePasswordHash(currentUser.getId(), passwordEncoder.encode(PASSWORD));
        email.reset();
    }

    @Test
    void requestChange_wrongPassword_forbidden() {
        assertThrows(ForbiddenException.class,
            () -> emailChangeService.requestChange("new_" + unique() + "@example.com", "wrong-password", "1.2.3.4"));
        assertNull(email.lastToken, "no verification email should be sent when the password is wrong");
    }

    @Test
    void requestChange_addressAlreadyInUse_rejected() {
        User other = newUser();
        assertThrows(DuplicateResourceException.class,
            () -> emailChangeService.requestChange(other.getEmail(), PASSWORD, "1.2.3.4"));
        assertNull(email.lastToken);
    }

    @Test
    void requestChange_sameAddress_rejected() {
        assertThrows(BadRequestException.class,
            () -> emailChangeService.requestChange(currentUser.getEmail(), PASSWORD, "1.2.3.4"));
    }

    @Test
    void requestThenConfirm_appliesNewEmail_andTokenIsSingleUse() {
        String newEmail = "new_" + unique() + "@example.com";
        emailChangeService.requestChange(newEmail, PASSWORD, "1.2.3.4");

        assertNotNull(email.lastToken);
        assertEquals(newEmail, email.lastNewEmail);

        emailChangeService.confirmChange(email.lastToken);

        assertEquals(newEmail, userMapper.getUserById(currentUser.getId()).getEmail());
        assertFalse(emailChangeService.validateToken(email.lastToken), "a consumed token must not be reusable");
    }

    @Test
    void confirmChange_invalidToken_rejected() {
        assertThrows(BadRequestException.class, () -> emailChangeService.confirmChange("not-a-real-token"));
    }

    @Test
    void profileUpdate_cannotChangeEmail() {
        String original = userMapper.getUserById(currentUser.getId()).getEmail();
        User edit = new User();
        edit.setUsername(currentUser.getUsername());
        edit.setDisplayName("Renamed " + unique());
        edit.setEmail("hijack_" + unique() + "@example.com");
        edit.setTimezone("UTC");

        userService.update(currentUser.getId(), edit);

        assertEquals(original, userMapper.getUserById(currentUser.getId()).getEmail(),
            "email must not change through the unverified profile-update path");
    }

    /**
     * Test double that captures the raw token the service would email.
     */
    @TestConfiguration
    static class CapturingEmailConfig {
        @Bean
        @Primary
        CapturingEmailChangeService capturingEmailChangeService() {
            return new CapturingEmailChangeService();
        }
    }

    static class CapturingEmailChangeService implements EmailChangeEmailService {
        volatile String lastToken;
        volatile String lastNewEmail;
        volatile User lastUser;
        volatile int calls;

        @Override
        public void sendVerificationEmail(User user, String newEmail, String rawToken) {
            this.lastUser = user;
            this.lastNewEmail = newEmail;
            this.lastToken = rawToken;
            this.calls++;
        }

        void reset() {
            lastToken = null;
            lastNewEmail = null;
            lastUser = null;
            calls = 0;
        }
    }
}
