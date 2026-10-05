package ooo.klae.connex.backend.services;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;

import ooo.klae.connex.backend.beans.User;

@TestPropertySource(properties = "connex.registration-verification.enabled=true")
@Import(AbstractRegistrationVerificationTest.CapturingConfig.class)
abstract class AbstractRegistrationVerificationTest extends AbstractServiceTest {

    @Autowired protected CapturingEmail email;

    @BeforeEach
    void resetCapture() {
        email.reset();
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
