package ooo.klae.connex.backend.webauthn;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

/**
 * Relying-party configuration for WebAuthn/passkeys, bound from {@code connex.webauthn.*}
 * / {@code CONNEX_WEBAUTHN_*}. The {@code rpId} must match the browsing origin's registrable
 * domain and {@code allowedOrigins} must list every origin the SPA is served from (they differ
 * per environment: localhost in dev, the staging/production hostnames otherwise).
 */
@Data
@Component
@ConfigurationProperties(prefix = "connex.webauthn")
public class WebAuthnProperties {

    /** Relying-party id — the registrable domain of the site (no scheme/port). */
    private String rpId = "localhost";

    /** Human-readable relying-party name shown by some authenticators. */
    private String rpName = "Connex";

    /** Exact origins (scheme://host[:port]) permitted to complete ceremonies. */
    private List<String> allowedOrigins = List.of("http://localhost:3000");
}
