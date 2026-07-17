package ooo.klae.connex.backend.delivery;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

/**
 * Instance-wide native delivery configuration, bound from {@code connex.delivery.*} /
 * {@code CONNEX_DELIVERY_*}. {@link #isEnabled()} is the operator setting behind the
 * {@code CAMPAIGN_DELIVERY} capability and defaults false so delivery is fail-closed until an
 * operator opts in.
 */
@Data
@Component
@ConfigurationProperties(prefix = "connex.delivery")
public class DeliveryProperties {

    /** Master switch for native campaign delivery on this instance. */
    private boolean enabled = false;

    /** Absolute base URL used to build recipient-facing unsubscribe links; empty yields a relative path. */
    private String publicBaseUrl = "";
}
