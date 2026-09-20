package ooo.klae.connex.backend.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class DeploymentProfileValidatorTest {

    private static final String MISSING_PROFILE_MESSAGE =
        "CONNEX_DEPLOYMENT_PROFILE must be set to saas, silo, or on-prem outside dev/test/seeder";
    private static final String SCRIPTED_PROFILE = "ai-scripted-provider";
    private static final String SCRIPTED_FLAG = "connex.ai.scripted-provider.enabled";

    @Test
    void rejectsUnsetProfileOutsideDevTestAndSeeder() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty("connex.bootstrap.enabled", "true");

        IllegalStateException exception = assertThrows(IllegalStateException.class,
            () -> validator("", environment).run(null));

        assertEquals(MISSING_PROFILE_MESSAGE, exception.getMessage());
    }

    @Test
    void rejectsWhitespaceProfileAtStartupIndependentlyOfBeanValidation() {
        MockEnvironment environment = new MockEnvironment();

        IllegalStateException exception = assertThrows(IllegalStateException.class,
            () -> validator("   ", environment).run(null));

        assertEquals(MISSING_PROFILE_MESSAGE, exception.getMessage());
    }

    @Test
    void beanValidationRejectsAWhitespaceProfileInEveryProfileIncludingSeeder() {
        DeploymentProperties properties = new DeploymentProperties();
        properties.setProfile("   ");

        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Set<ConstraintViolation<DeploymentProperties>> violations = factory.getValidator().validate(properties);

            assertFalse(violations.isEmpty());
        }
    }

    @Test
    void allowsUnsetProfileForSeeder() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("seeder");

        assertDoesNotThrow(() -> validator("", environment).run(null));
    }

    @Test
    void allowsUnsetProfileInTest() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("test");

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
    void rejectsSiloWhenDormantPublicApiIsEnabled() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty("connex.public-api.enabled", "true");

        IllegalStateException exception = assertThrows(IllegalStateException.class,
            () -> validator(DeploymentProperties.PROFILE_SILO, environment).run(null));

        assertEquals("connex.deployment.profile=silo forbids: connex.public-api.enabled=true",
            exception.getMessage());
    }

    @Test
    void siloPublicApiRefusalUsesRelaxedBinding() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty("connex.public-api.ENABLED", "true");

        assertThrows(IllegalStateException.class,
            () -> validator(DeploymentProperties.PROFILE_SILO, environment).run(null));
    }

    @Test
    void allowsBootstrapForOnPrem() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty("connex.bootstrap.enabled", "true");

        assertDoesNotThrow(() -> validator(DeploymentProperties.PROFILE_ON_PREM, environment).run(null));
    }

    @Test
    void rejectsOnPremWhenInstanceManagedMailIsEnabled() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty("connex.mail.managed", "true");

        IllegalStateException exception = assertThrows(IllegalStateException.class,
            () -> validator(DeploymentProperties.PROFILE_ON_PREM, environment).run(null));

        assertEquals("connex.deployment.profile=on-prem forbids: connex.mail.managed=true",
            exception.getMessage());
    }

    @Test
    void rejectsOnPremWhenInstanceManagedMailIsEnabledWithARelaxedSpelling() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty("connex.mail.MANAGED", "true");

        IllegalStateException exception = assertThrows(IllegalStateException.class,
            () -> validator(DeploymentProperties.PROFILE_ON_PREM, environment).run(null));

        assertEquals("connex.deployment.profile=on-prem forbids: connex.mail.managed=true",
            exception.getMessage());
    }

    @Test
    void allowsOnPremWhenInstanceManagedMailIsDisabled() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty("connex.mail.managed", "false")
            .withProperty("connex.mail.enabled", "true");

        assertDoesNotThrow(() -> validator(DeploymentProperties.PROFILE_ON_PREM, environment).run(null));
    }

    @Test
    void allowsInstanceManagedMailForSaasAndSilo() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty("connex.mail.managed", "true");

        assertDoesNotThrow(() -> validator(DeploymentProperties.PROFILE_SAAS, environment).run(null));
        assertDoesNotThrow(() -> validator(DeploymentProperties.PROFILE_SILO, environment).run(null));
    }

    @Test
    void rejectsTheScriptedAiProviderProfileUnderEveryEdition() {
        for (String profile : List.of(
                DeploymentProperties.PROFILE_SAAS,
                DeploymentProperties.PROFILE_SILO,
                DeploymentProperties.PROFILE_ON_PREM)) {
            MockEnvironment environment = new MockEnvironment()
                .withProperty(SCRIPTED_FLAG, "true");
            environment.setActiveProfiles(SCRIPTED_PROFILE, "dev");

            IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> validator(profile, environment).run(null));

            assertEquals("connex.deployment.profile=" + profile
                + " forbids the ai-scripted-provider Spring profile", exception.getMessage());
        }
    }

    /**
     * The shape the eval template actually ships: the dev Spring profile with no edition declared.
     *
     * <p>Appending the scripted profile there would have passed a refusal keyed only on a declared
     * edition, so the flag has to be the thing that is missing, and its absence has to refuse.
     */
    @Test
    void rejectsTheScriptedAiProviderProfileWithoutItsFlagOnAnEditionlessDevStack() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(SCRIPTED_PROFILE, "dev");

        IllegalStateException exception = assertThrows(IllegalStateException.class,
            () -> validator("", environment).run(null));

        assertEquals("The ai-scripted-provider Spring profile requires "
            + "connex.ai.scripted-provider.enabled=true", exception.getMessage());
    }

    @Test
    void rejectsTheScriptedAiProviderProfileOutsideDevAndTest() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty(SCRIPTED_FLAG, "true");
        environment.setActiveProfiles(SCRIPTED_PROFILE);

        IllegalStateException exception = assertThrows(IllegalStateException.class,
            () -> validator("", environment).run(null));

        assertEquals("The ai-scripted-provider Spring profile requires the dev or test "
            + "Spring profile", exception.getMessage());
    }

    @Test
    void rejectsTheScriptedAiProviderFlagWithoutItsProfile() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty(SCRIPTED_FLAG, "true");
        environment.setActiveProfiles("dev");

        IllegalStateException exception = assertThrows(IllegalStateException.class,
            () -> validator("", environment).run(null));

        assertEquals("connex.ai.scripted-provider.enabled requires the ai-scripted-provider "
            + "Spring profile", exception.getMessage());
    }

    @Test
    void reportsTheScriptedAiProviderFlagThroughTheForbiddenKeyPathUnderEveryEdition() {
        for (String profile : List.of(
                DeploymentProperties.PROFILE_SAAS,
                DeploymentProperties.PROFILE_SILO,
                DeploymentProperties.PROFILE_ON_PREM)) {
            MockEnvironment environment = new MockEnvironment()
                .withProperty(SCRIPTED_FLAG, "true");

            IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> validator(profile, environment).run(null));

            assertEquals("connex.deployment.profile=" + profile
                + " forbids: connex.ai.scripted-provider.enabled=true", exception.getMessage());
        }
    }

    @Test
    void scriptedAiProviderRefusalUsesRelaxedBinding() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty("connex.ai.scripted-provider.ENABLED", "true");
        environment.setActiveProfiles("dev");

        IllegalStateException exception = assertThrows(IllegalStateException.class,
            () -> validator("", environment).run(null));

        assertEquals("connex.ai.scripted-provider.enabled requires the ai-scripted-provider "
            + "Spring profile", exception.getMessage());
    }

    @Test
    void allowsTheScriptedAiProviderProfileWithItsFlagInDevAndTest() {
        for (String springProfile : List.of("dev", "test")) {
            MockEnvironment environment = new MockEnvironment()
                .withProperty(SCRIPTED_FLAG, "true");
            environment.setActiveProfiles(SCRIPTED_PROFILE, springProfile);

            assertDoesNotThrow(() -> validator("", environment).run(null));
        }
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

    @Test
    void bindingStillAcceptsAnAbsentProfileSoSeederModeCanBoot() {
        DeploymentProperties properties = new DeploymentProperties();

        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Set<ConstraintViolation<DeploymentProperties>> violations = factory.getValidator().validate(properties);

            assertTrue(violations.isEmpty());
        }
    }

    private static DeploymentProfileValidator validator(String profile, MockEnvironment environment) {
        environment.setProperty("connex.deployment.profile", profile);
        return new DeploymentProfileValidator(environment);
    }
}
