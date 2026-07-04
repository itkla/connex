package ooo.klae.connex.backend.sso;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;
import lombok.ToString;

/**
 * Consumer social-login configuration, bound from {@code connex.social-login.*} /
 * {@code CONNEX_SOCIAL_LOGIN_*}. Each provider is a single instance-wide OAuth client
 * ("Sign in with Google/Microsoft"), independent of the per-organization enterprise SSO
 * ({@code connex.sso.*}). Every provider defaults to disabled so an instance ships with
 * no social login until an operator configures one.
 */
@Data
@Component
@ConfigurationProperties(prefix = "connex.social-login")
public class SocialLoginProperties {

    private Provider google = new Provider();
    private Provider microsoft = new Provider();

    /**
     * A single social-login provider's instance-wide OAuth client. {@code enabled} gates
     * whether the provider is offered at all; the client secret is kept out of {@code toString}.
     */
    @Data
    @ToString(exclude = "clientSecret")
    public static class Provider {
        private boolean enabled = false;
        private String clientId;
        private String clientSecret;
    }
}
