package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.RegisterDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;

/**
 * Exercises the registration email-verification service with the feature enabled: a token is
 * issued and emailed, redeeming it marks the account verified and is single-use, and no token is
 * issued for an already-verified account or an invalid redemption.
 */
@TestPropertySource(properties = "connex.registration-verification.enabled=true")
@Import(RegistrationVerificationServiceTest.CapturingConfig.class)
class RegistrationVerificationServiceTest extends AbstractServiceTest {

    @Autowired private RegistrationVerificationService service;
    @Autowired private AuthService authService;
    @Autowired private CapturingEmail email;

    @BeforeEach
    void resetCapture() {
        email.reset();
    }

    @Test
    void issueThenConfirm_marksAccountVerified() {
        User user = newUser();
        assertFalse(userMapper.getUserById(user.getId()).isEmailVerified());

        service.issue(user, "1.2.3.4");
        assertNotNull(email.lastToken, "a verification link should be issued when enabled");
        assertTrue(service.validateToken(email.lastToken));

        service.confirm(email.lastToken);
        assertTrue(userMapper.getUserById(user.getId()).isEmailVerified());

        assertThrows(BadRequestException.class, () -> service.confirm(email.lastToken),
            "a verification token is single-use");
    }

    @Test
    void issue_isNoopForAlreadyVerifiedAccount() {
        User user = newUser();
        userMapper.markEmailVerified(user.getId());

        service.issue(userMapper.getUserById(user.getId()), "1.2.3.4");

        assertNull(email.lastToken);
    }

    @Test
    void confirm_invalidToken_rejected() {
        assertThrows(BadRequestException.class, () -> service.confirm("not-a-real-token"));
        assertFalse(service.validateToken("not-a-real-token"));
    }

    @Test
    void selfServiceRegistration_startsUnverifiedAndIssuesToken() {
        RegisterDto dto = new RegisterDto();
        dto.setUsername("ss_" + unique());
        dto.setDisplayName("Self Serve");
        dto.setEmail(unique() + "@example.com");
        dto.setPassword("Str0ngPw1!");
        dto.setTimezone("UTC");

        User user = authService.registerSelfService(dto, "1.2.3.4");

        assertFalse(userMapper.getUserById(user.getId()).isEmailVerified(),
            "self-serve accounts start unverified when verification is enabled");
        assertNotNull(email.lastToken, "registration issues a verification link");
    }

    @TestConfiguration
    static class CapturingConfig {
        @Bean
        @Primary
        CapturingEmail capturingRegistrationVerificationEmailService() {
            return new CapturingEmail();
        }
    }

    static class CapturingEmail implements RegistrationVerificationEmailService {
        volatile String lastToken;
        volatile User lastUser;

        @Override
        public void sendVerificationEmail(User user, String rawToken) {
            this.lastUser = user;
            this.lastToken = rawToken;
        }

        void reset() {
            lastToken = null;
            lastUser = null;
        }
    }
}
