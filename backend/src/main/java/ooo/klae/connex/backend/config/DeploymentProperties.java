package ooo.klae.connex.backend.config;

import jakarta.validation.constraints.Pattern;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import lombok.Data;

/**
 * Defines the authoritative deployment profile used to enforce runtime posture.
 */
@Data
@Validated
@Component
@ConfigurationProperties(prefix = "connex.deployment")
public class DeploymentProperties {

    public static final String PROFILE_SAAS = "saas";
    public static final String PROFILE_SILO = "silo";
    public static final String PROFILE_ON_PREM = "on-prem";

    /**
     * Accepts an absent value on purpose. Bean validation runs at bind time, before any
     * profile-aware logic can apply an exemption, and an absent value is legal for the dev,
     * test, and seeder profiles — seeder mode in fact requires it, because
     * {@code SeederStartupConfigurationValidator} refuses a set profile. Making the value
     * mandatory here would therefore make seeder runs unbootable. Presence is instead
     * enforced conditionally at startup by {@link DeploymentProfileValidator}.
     */
    @Pattern(regexp = "^(saas|silo|on-prem)?$",
        message = "connex.deployment.profile must be one of: saas, silo, on-prem")
    private String profile = "";

    /**
     * Returns whether an operator explicitly configured a deployment profile.
     *
     * @return {@code true} when the profile is non-blank
     */
    public boolean isConfigured() {
        return profile != null && !profile.isBlank();
    }

    /**
     * Returns whether this deployment uses the shared SaaS profile.
     *
     * @return {@code true} for the SaaS profile
     */
    public boolean isSaas() {
        return PROFILE_SAAS.equals(profile);
    }
}
