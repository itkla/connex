package ooo.klae.connex.backend.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;

import org.junit.jupiter.api.Test;

/** Unit tests for delivery transport bounds that protect the audience-export lease. */
class DeliveryPropertiesTest {

    @Test
    void audienceExportLeaseCoversConfiguredTransportBoundsAndSafetyMargin() {
        DeliveryProperties properties = new DeliveryProperties();
        properties.setEspConnectTimeoutMs(10_000);
        properties.setEspRequestTimeoutMs(20_000);

        assertEquals(Duration.ofSeconds(60), properties.audienceExportLeaseDuration());
    }

    @Test
    void startupRejectsTransportBoundsThatCannotFitWithinTheMaximumLease() {
        DeliveryProperties properties = new DeliveryProperties();
        properties.setEspConnectTimeoutMs(3_000);
        properties.setEspRequestTimeoutMs(Duration.ofMinutes(5).toMillis());

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class, properties::validateAudienceExportTransportBounds);

        assertEquals(
                "connex.delivery ESP transport bounds plus the audience-export safety margin "
                        + "must fit within the 5-minute maximum lease",
                exception.getMessage());
    }
}
