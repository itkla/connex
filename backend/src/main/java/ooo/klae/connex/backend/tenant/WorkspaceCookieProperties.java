package ooo.klae.connex.backend.tenant;

import java.util.Locale;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import lombok.Data;

/**
 * Deployment flags for the frontend-readable active-workspace cookie.
 */
@Data
@Validated
@Component
@ConfigurationProperties(prefix = "connex.workspace-cookie")
public class WorkspaceCookieProperties {
    @NotBlank(message = "must not be blank")
    @Pattern(regexp = "(?i)^(strict|lax|none)$", message = "must be Strict, Lax, or None")
    private String sameSite = "Lax";

    private boolean secure = true;

    public String sameSiteHeaderValue() {
        String normalized = sameSite.trim().toLowerCase(Locale.ROOT);
        return normalized.substring(0, 1).toUpperCase(Locale.ROOT) + normalized.substring(1);
    }

    public boolean isEffectiveSecure() {
        return secure || "None".equals(sameSiteHeaderValue());
    }
}
