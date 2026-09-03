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

    private static final Duration MIN_AUDIENCE_EXPORT_LEASE_SAFETY_MARGIN = Duration.ofSeconds(30);
    private static final Duration MAX_AUDIENCE_EXPORT_LEASE = Duration.ofMinutes(5);

    /** Master switch for native campaign delivery on this instance. */
    private boolean enabled = false;

    /** Absolute base URL used to build recipient-facing unsubscribe links; empty yields a relative path. */
    private String publicBaseUrl = "";

    /** TCP connect timeout, in milliseconds, for outbound HTTP delivery. */
    private long espConnectTimeoutMs = 3000;

    /** Socket read inactivity timeout, in milliseconds, for outbound HTTP delivery. */
    private long espRequestTimeoutMs = 15000;

    /** Hard wall-clock deadline, in milliseconds, for one audience-export provider call. */
    private long audienceExportProviderDeadlineMs = 18000;

    /**
     * Database-clock adjustment allowance, in milliseconds, beyond the provider deadline. Must be
     * at least 30 seconds; operators with looser database-host clock discipline must raise it.
     */
    private long audienceExportLeaseSafetyMarginMs = 30000;

    /** Maximum bytes read from an HTTP ESP response before the send is rejected. */
    private int espMaxResponseBytes = 65536;

    /**
     * Returns the hard wall-clock deadline for one audience-export provider call. Connection and
     * response timeouts are subordinate inactivity limits and cannot exceed this deadline.
     * @return the validated provider-call deadline
     */
    public Duration audienceExportProviderDeadline() {
        if (espConnectTimeoutMs <= 0 || espRequestTimeoutMs <= 0
                || audienceExportProviderDeadlineMs <= 0) {
            throw invalidAudienceExportTransportBounds(
                    "timeouts and the provider deadline must be positive");
        }
        if (espConnectTimeoutMs > audienceExportProviderDeadlineMs
                || espRequestTimeoutMs > audienceExportProviderDeadlineMs) {
            throw invalidAudienceExportTransportBounds(
                    "connection and response inactivity timeouts must not exceed the provider deadline");
        }
        return Duration.ofMillis(audienceExportProviderDeadlineMs);
    }

    /**
     * Returns the running lease for one audience export provider call. The lease covers the hard
     * provider deadline plus the configured database-clock adjustment, handoff, and persistence
     * margin. It prevents database-clock expiry during a live monotonic provider budget only while
     * forward database-clock adjustments during the lease stay below that margin.
     * @return the validated audience-export lease duration
     */
    public Duration audienceExportLeaseDuration() {
        if (audienceExportLeaseSafetyMarginMs
                < MIN_AUDIENCE_EXPORT_LEASE_SAFETY_MARGIN.toMillis()) {
            throw invalidAudienceExportTransportBounds(
                    "the audience-export lease safety margin must be at least 30 seconds");
        }
        try {
            long leaseMillis = Math.addExact(
                    audienceExportProviderDeadline().toMillis(),
                    audienceExportLeaseSafetyMarginMs);
            Duration lease = Duration.ofMillis(leaseMillis);
            if (lease.compareTo(MAX_AUDIENCE_EXPORT_LEASE) > 0) {
                throw invalidAudienceExportTransportBounds(
                        "the provider deadline plus safety margin must fit within the 5-minute maximum lease");
            }
            return lease;
        } catch (ArithmeticException exception) {
            throw invalidAudienceExportTransportBounds(
                    "the provider deadline plus safety margin must fit within the 5-minute maximum lease");
        }
    }

    @PostConstruct
    void validateAudienceExportTransportBounds() {
        audienceExportLeaseDuration();
    }

    private static IllegalArgumentException invalidAudienceExportTransportBounds(String detail) {
        return new IllegalArgumentException(
                "Invalid connex.delivery audience-export transport bounds: " + detail);
    }
}
