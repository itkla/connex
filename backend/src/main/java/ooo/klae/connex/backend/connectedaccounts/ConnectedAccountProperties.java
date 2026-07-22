package ooo.klae.connex.backend.connectedaccounts;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;
import lombok.ToString;

/**
 * Per-user connected-account (mail/calendar) OAuth configuration, bound from
 * {@code connex.connected-accounts.*} / {@code CONNEX_CONNECTED_ACCOUNTS_*}. These are separate
 * OAuth client apps from social login ({@code connex.social-login.*}): connecting a mailbox
 * requests offline access to mail and calendar scopes, which a sign-in client must never hold.
 * Every provider defaults to disabled so an instance ships with no connected-account support
 * until an operator configures one.
 */
@Data
@Component
@ConfigurationProperties(prefix = "connex.connected-accounts")
public class ConnectedAccountProperties {

    private Provider google = new Provider();
    private Provider microsoft = new Provider();

    /**
     * One provider's instance-wide OAuth client for connected accounts. {@code enabled} gates
     * availability; the client secret is kept out of {@code toString}.
     */
    @Data
    @ToString(exclude = "clientSecret")
    public static class Provider {
        private boolean enabled = false;
        private String clientId;
        private String clientSecret;
    }
}
