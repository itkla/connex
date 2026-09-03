package ooo.klae.connex.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** Feature readiness configuration for sales sequence authoring APIs. */
@Component
@Validated
@ConfigurationProperties(prefix = "connex.sequences")
public class SequenceProperties {
    @NotNull
    @Pattern(regexp = "^(?:true|false)$")
    private String enabled = "false";

    /** Returns whether the sequence authoring surface is enabled. */
    public boolean isEnabled() {
        return Boolean.parseBoolean(enabled);
    }

    /** Sets the string-backed readiness flag. */
    public void setEnabled(String enabled) {
        this.enabled = enabled;
    }
}
