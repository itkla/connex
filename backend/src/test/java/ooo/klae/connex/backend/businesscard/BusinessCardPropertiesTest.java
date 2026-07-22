package ooo.klae.connex.backend.businesscard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class BusinessCardPropertiesTest {
    @Test
    void rejectsNonPositiveReadinessAndImageBounds() {
        BusinessCardProperties properties = new BusinessCardProperties();

        assertThrows(IllegalArgumentException.class,
                () -> properties.setReadinessCache(Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> properties.setLocalFirstWait(Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> properties.setLocalFirstWait(Duration.ofSeconds(11)));
        assertThrows(IllegalArgumentException.class,
                () -> properties.setMaxImageBytes(0));
        assertThrows(IllegalArgumentException.class,
                () -> properties.setMaxPixels(-1));
        assertThrows(IllegalArgumentException.class,
                () -> properties.setMaxGlobalScansPerMinute(0));
        assertThrows(IllegalArgumentException.class,
                () -> properties.setReservationLease(Duration.ZERO));
        properties.setReservationLease(Duration.ofHours(24));
        assertThrows(IllegalArgumentException.class,
                properties::validateRetentionWindows);
        assertThrows(IllegalArgumentException.class,
                () -> properties.setMaxOutstandingReservations(33));
    }

    @Test
    void acceptsValidRetentionWindowsRegardlessOfSetterOrder() {
        BusinessCardProperties properties = new BusinessCardProperties();

        properties.setIdempotencyRetention(Duration.ofMinutes(1));
        properties.setReservationLease(Duration.ofSeconds(30));

        properties.validateRetentionWindows();
    }

    @Test
    void cleanupDefaultsToOneHundredRowsPerWorkspace() {
        assertEquals(100,
            new BusinessCardProperties().getIdempotencyCleanupPerWorkspaceBatchSize());
    }

    @Test
    void localFirstWaitDefaultsToTwoSeconds() {
        assertEquals(Duration.ofSeconds(2), new BusinessCardProperties().getLocalFirstWait());
    }
}
