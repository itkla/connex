package ooo.klae.connex.backend.sso;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;
import lombok.ToString;

/**
 * Instance-wide SSO configuration, bound from {@code connex.sso.*} /
 * {@code CONNEX_SSO_*}. The legacy secret key is retained only to decrypt SSO
 * secrets written before the central secret store existed.
 */
@Data
@ToString(exclude = "secretKey")
@Component
@ConfigurationProperties(prefix = "connex.sso")
public class SsoProperties {

    /**
     * Instance-level kill switch for the entire SSO subsystem. Defaults to false so a
     * deployment ships with OIDC/SAML dormant — the login filter chain skips the SSO
     * endpoints, discovery reports unavailable, config saves are refused, and no user is
     * treated as SSO-enforced. Set true only on deployments that offer enterprise SSO.
     */
    private boolean enabled = false;

    /**
     * Legacy Base64-encoded AES key (128/192/256-bit) for pre-secret-store SSO
     * encrypted blobs.
     */
    private String secretKey;

    /**
     * Whether an enterprise OIDC issuer and the endpoints discovered for it may resolve to a
     * loopback or private (RFC1918/CGNAT/ULA/link-local) address. Defaults to false so the
     * server-side discovery fetch cannot be pointed at internal services or the cloud metadata
     * endpoint (SSRF). Enable only on trusted deployments whose IdP is genuinely on-premises on a
     * private network.
     *
     * <p>The exemption is scoped to per-organization enterprise registrations. Consumer social
     * login (Google / Microsoft) always keeps the strict destination policy, so turning this on
     * cannot redirect a social provider fetch at an internal service.
     *
     * <p>Every guarded OIDC fetch pins the validated addresses for the destination host, so an
     * HTTP proxy configured through the JVM {@code http.proxyHost} / {@code https.proxyHost}
     * system properties is deliberately not honoured: routing through a proxy would replace the
     * pinned destination with the proxy's own address and defeat the check. Deployments that must
     * egress through a proxy need a transparent one at the network layer.
     */
    private boolean allowPrivateIssuerHosts = false;
}
