package ooo.klae.connex.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

@Component
@Validated
@ConfigurationProperties(prefix = "connex.record-creation")
public class RecordCreationProperties {
    @NotNull
    @Pattern(regexp = "^(?:true|false)$")
    private String guidedCutoverEnabled = "false";

    public boolean isGuidedCutoverEnabled() {
        return Boolean.parseBoolean(guidedCutoverEnabled);
    }

    public void setGuidedCutoverEnabled(String guidedCutoverEnabled) {
        this.guidedCutoverEnabled = guidedCutoverEnabled;
    }
}
