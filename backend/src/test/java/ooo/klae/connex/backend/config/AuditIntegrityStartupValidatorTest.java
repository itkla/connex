package ooo.klae.connex.backend.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.mock.env.MockEnvironment;

class AuditIntegrityStartupValidatorTest {

    @Test
    void failsOutsideDevAndTestWhenHmacSecretIsMissing() {
        AuditIntegrityProperties properties = new AuditIntegrityProperties();
        AuditIntegrityStartupValidator validator = new AuditIntegrityStartupValidator(properties, new MockEnvironment());

        assertThrows(IllegalStateException.class, () -> validator.run(null));
    }

    @Test
    void allowsDevProfileWithoutHmacSecret() {
        AuditIntegrityProperties properties = new AuditIntegrityProperties();
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("dev");
        AuditIntegrityStartupValidator validator = new AuditIntegrityStartupValidator(properties, environment);

        assertDoesNotThrow(() -> validator.run(null));
    }

    @Test
    void allowsValidHmacSecretWithoutDevProfile() {
        AuditIntegrityProperties properties = new AuditIntegrityProperties();
        properties.setHmacSecret("production-audit-integrity-secret-value");
        AuditIntegrityStartupValidator validator = new AuditIntegrityStartupValidator(properties, new MockEnvironment());

        assertDoesNotThrow(() -> validator.run(null));
    }

    @Test
    void bindsAuditIntegrityHmacSecretProperty() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty("connex.audit.integrity.hmac-secret", "bound-audit-integrity-secret-value");

        AuditIntegrityProperties properties = Binder.get(environment)
            .bind("connex.audit.integrity", Bindable.of(AuditIntegrityProperties.class))
            .orElseThrow(() -> new AssertionError("Audit integrity properties did not bind"));

        assertEquals("bound-audit-integrity-secret-value", properties.getHmacSecret());
    }
}
