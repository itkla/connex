package ooo.klae.connex.backend.sso;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;
import lombok.ToString;

/**
 * Instance-wide SSO configuration, bound from {@code connex.sso.*} /
 * {@code CONNEX_SSO_*}. Currently just the encryption key used to protect
 * per-organization IdP client secrets at rest. Required only once an organization
 * stores an OIDC client secret; rotating it invalidates previously stored secrets.
 */
@Data
@ToString(exclude = "secretKey")
@Component
@ConfigurationProperties(prefix = "connex.sso")
public class SsoProperties {

    /**
     * Base64-encoded AES key (128/192/256-bit) used to encrypt per-organization
     * OIDC client secrets at rest.
     */
    private String secretKey;

    /**
     * Whether an OIDC issuer may resolve to a loopback or private (RFC1918/CGNAT/ULA/link-local)
     * address. Defaults to false so the server-side discovery fetch cannot be pointed at internal
     * services or the cloud metadata endpoint (SSRF). Enable only on trusted deployments whose IdP
     * is genuinely on-premises on a private network.
     */
    private boolean allowPrivateIssuerHosts = false;
}
