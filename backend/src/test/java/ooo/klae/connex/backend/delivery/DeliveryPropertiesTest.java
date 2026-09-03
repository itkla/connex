package ooo.klae.connex.backend.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;

import org.junit.jupiter.api.Test;

/** Unit tests for delivery transport bounds that protect the audience-export lease. */
class DeliveryPropertiesTest {

    @Test
    void audienceExportLeaseSafetyMarginDefaultsToThirtySeconds() {
        DeliveryProperties properties = new DeliveryProperties();

        assertEquals(30_000, properties.getAudienceExportLeaseSafetyMarginMs());
        assertEquals(Duration.ofSeconds(48), properties.audienceExportLeaseDuration());
    }

    @Test
    void audienceExportLeaseCoversConfiguredTransportBoundsAndSafetyMargin() {
        DeliveryProperties properties = new DeliveryProperties();
        properties.setEspConnectTimeoutMs(10_000);
        properties.setEspRequestTimeoutMs(20_000);
        properties.setAudienceExportProviderDeadlineMs(25_000);
        properties.setAudienceExportLeaseSafetyMarginMs(45_000);

        assertEquals(Duration.ofSeconds(25), properties.audienceExportProviderDeadline());
        assertEquals(Duration.ofSeconds(70), properties.audienceExportLeaseDuration());
    }

    @Test
    void startupRejectsALeaseSafetyMarginBelowThirtySeconds() {
        DeliveryProperties properties = new DeliveryProperties();
        properties.setAudienceExportLeaseSafetyMarginMs(29_999);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class, properties::validateAudienceExportTransportBounds);

        assertEquals(
                "Invalid connex.delivery audience-export transport bounds: the audience-export "
                        + "lease safety margin must be at least 30 seconds",
                exception.getMessage());
    }

    @Test
    void startupRejectsAProviderDeadlineThatCannotFitWithinTheMaximumLease() {
        DeliveryProperties properties = new DeliveryProperties();
        properties.setAudienceExportProviderDeadlineMs(Duration.ofMinutes(5).toMillis());

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class, properties::validateAudienceExportTransportBounds);

        assertEquals(
                "Invalid connex.delivery audience-export transport bounds: the provider deadline "
                        + "plus safety margin must fit within the 5-minute maximum lease",
                exception.getMessage());
    }

    @Test
    void startupRejectsAConnectTimeoutLongerThanTheProviderDeadline() {
        DeliveryProperties properties = new DeliveryProperties();
        properties.setEspConnectTimeoutMs(20_000);
        properties.setEspRequestTimeoutMs(10_000);
        properties.setAudienceExportProviderDeadlineMs(15_000);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class, properties::validateAudienceExportTransportBounds);

        assertEquals(
                "Invalid connex.delivery audience-export transport bounds: connection and response "
                        + "inactivity timeouts must not exceed the provider deadline",
                exception.getMessage());
    }

    @Test
    void startupRejectsAResponseInactivityTimeoutLongerThanTheProviderDeadline() {
        DeliveryProperties properties = new DeliveryProperties();
        properties.setEspConnectTimeoutMs(5_000);
        properties.setEspRequestTimeoutMs(20_000);
        properties.setAudienceExportProviderDeadlineMs(15_000);

        assertThrows(IllegalArgumentException.class, properties::validateAudienceExportTransportBounds);
    }
}
