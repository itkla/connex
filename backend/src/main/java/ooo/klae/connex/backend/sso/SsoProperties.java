package ooo.klae.connex.backend.sso;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

/**
 * Instance-wide SSO configuration, bound from {@code connex.sso.*} /
 * {@code CONNEX_SSO_*}. Currently just the encryption key used to protect
 * per-organization IdP client secrets at rest. Required only once an organization
 * stores an OIDC client secret; rotating it invalidates previously stored secrets.
 */
@Data
@Component
@ConfigurationProperties(prefix = "connex.sso")
public class SsoProperties {

    /**
     * Base64-encoded AES key (128/192/256-bit) used to encrypt per-organization
     * OIDC client secrets at rest.
     */
    private String secretKey;
}
