package ooo.klae.connex.backend.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Set;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class DeploymentProfileValidatorTest {

    @Test
    void allowsUnsetProfileOutsideDevDuringSoftLaunch() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty("connex.bootstrap.enabled", "true");

        assertDoesNotThrow(() -> validator("", environment).run(null));
    }

    @Test
    void allowsUnsetProfileInDev() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty("connex.mail.allow-internal-hosts", "true");
        environment.setActiveProfiles("dev");

        assertDoesNotThrow(() -> validator("", environment).run(null));
    }

    @Test
    void allowsSaasWhenForbiddenFlagsAreFalseOrAbsent() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty("connex.bootstrap.enabled", "false")
            .withProperty("connex.sso.allow-private-issuer-hosts", "false");

        assertDoesNotThrow(() -> validator(DeploymentProperties.PROFILE_SAAS, environment).run(null));
    }

    @Test
    void rejectsSaasWhenBootstrapIsEnabled() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty("connex.bootstrap.enabled", "true");

        IllegalStateException exception = assertThrows(IllegalStateException.class,
            () -> validator(DeploymentProperties.PROFILE_SAAS, environment).run(null));

        assertEquals("connex.deployment.profile=saas forbids: connex.bootstrap.enabled=true",
            exception.getMessage());
    }

    @Test
    void rejectsSaasWithEveryEnabledInternalAccessFlag() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty("connex.sso.allow-private-issuer-hosts", "true")
            .withProperty("connex.ai.allow-internal-endpoints", "true")
            .withProperty("connex.mail.allow-internal-hosts", "true");

        IllegalStateException exception = assertThrows(IllegalStateException.class,
            () -> validator(DeploymentProperties.PROFILE_SAAS, environment).run(null));

        assertEquals("connex.deployment.profile=saas forbids: "
            + "connex.sso.allow-private-issuer-hosts=true, "
            + "connex.ai.allow-internal-endpoints=true, "
            + "connex.mail.allow-internal-hosts=true", exception.getMessage());
    }

    @Test
    void allowsInternalMailHostForSilo() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty("connex.mail.allow-internal-hosts", "true");

        assertDoesNotThrow(() -> validator(DeploymentProperties.PROFILE_SILO, environment).run(null));
    }

    @Test
    void allowsBootstrapForOnPrem() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty("connex.bootstrap.enabled", "true");

        assertDoesNotThrow(() -> validator(DeploymentProperties.PROFILE_ON_PREM, environment).run(null));
    }

    @Test
    void rejectsInvalidDeploymentProfileValue() {
        DeploymentProperties properties = new DeploymentProperties();
        properties.setProfile("shared");

        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Set<ConstraintViolation<DeploymentProperties>> violations = factory.getValidator().validate(properties);

            assertFalse(violations.isEmpty());
            assertEquals("connex.deployment.profile must be one of: saas, silo, on-prem",
                violations.iterator().next().getMessage());
        }
    }

    private static DeploymentProfileValidator validator(String profile, MockEnvironment environment) {
        DeploymentProperties properties = new DeploymentProperties();
        properties.setProfile(profile);
        return new DeploymentProfileValidator(properties, environment);
    }
}
