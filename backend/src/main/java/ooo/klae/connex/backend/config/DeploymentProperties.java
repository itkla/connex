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
