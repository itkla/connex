package ooo.klae.connex.backend.delivery;

import java.time.Duration;

import jakarta.annotation.PostConstruct;

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

    private static final int AUDIENCE_EXPORT_PROVIDER_RETRIES = 0;
    private static final Duration AUDIENCE_EXPORT_LEASE_SAFETY_MARGIN = Duration.ofSeconds(30);
    private static final Duration MAX_AUDIENCE_EXPORT_LEASE = Duration.ofMinutes(5);

    /** Master switch for native campaign delivery on this instance. */
    private boolean enabled = false;

    /** Absolute base URL used to build recipient-facing unsubscribe links; empty yields a relative path. */
    private String publicBaseUrl = "";

    /** TCP connect timeout, in milliseconds, for outbound HTTP ESP dispatch. */
    private long espConnectTimeoutMs = 3000;

    /** Socket read timeout, in milliseconds, for outbound HTTP ESP dispatch. */
    private long espRequestTimeoutMs = 15000;

    /** Maximum bytes read from an HTTP ESP response before the send is rejected. */
    private int espMaxResponseBytes = 65536;

    /**
     * Returns the running lease for one audience export provider attempt. The lease covers every
     * configured connect/request attempt plus a fixed handoff and persistence margin. The generic
     * list connector disables automatic retries, so its current provider-attempt count is one.
     * @return the validated audience-export lease duration
     */
    public Duration audienceExportLeaseDuration() {
        if (espConnectTimeoutMs <= 0 || espRequestTimeoutMs <= 0) {
            throw invalidAudienceExportTransportBounds();
        }
        try {
            long attemptMillis = Math.addExact(espConnectTimeoutMs, espRequestTimeoutMs);
            long providerMillis = Math.multiplyExact(
                    attemptMillis, Math.addExact(AUDIENCE_EXPORT_PROVIDER_RETRIES, 1));
            long leaseMillis = Math.addExact(
                    providerMillis, AUDIENCE_EXPORT_LEASE_SAFETY_MARGIN.toMillis());
            Duration lease = Duration.ofMillis(leaseMillis);
            if (lease.compareTo(MAX_AUDIENCE_EXPORT_LEASE) > 0) {
                throw invalidAudienceExportTransportBounds();
            }
            return lease;
        } catch (ArithmeticException exception) {
            throw invalidAudienceExportTransportBounds();
        }
    }

    @PostConstruct
    void validateAudienceExportTransportBounds() {
        audienceExportLeaseDuration();
    }

    private static IllegalArgumentException invalidAudienceExportTransportBounds() {
        return new IllegalArgumentException(
                "connex.delivery ESP transport bounds plus the audience-export safety margin "
                        + "must fit within the 5-minute maximum lease");
    }
}
