package ooo.klae.connex.backend.signature;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

/** Instance-wide fail-closed document-signature configuration. */
@Data
@Component
@ConfigurationProperties(prefix = "connex.signature")
public class SignatureProperties {
    /** Master operator switch for every outward-facing document-signature operation. */
    private boolean enabled = false;

    /** Fixed window used by the public acceptance throttle. */
    private Duration rateLimitWindow = Duration.ofMinutes(1);

    /** Maximum public acceptance requests for one bearer token in a window. */
    private int maxRequestsPerToken = 60;

    /** Maximum public acceptance requests for one attributable source address in a window. */
    private int maxRequestsPerSource = 120;

    /** Maximum retained public-throttle buckets per namespace. */
    private int rateLimitMaxKeys = 20_000;

    /** Maximum expired envelopes processed for one workspace in one scheduler pass. */
    private int expiryBatchSize = 100;
}
