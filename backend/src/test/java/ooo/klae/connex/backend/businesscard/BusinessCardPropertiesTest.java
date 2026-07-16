package ooo.klae.connex.backend.businesscard;

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
                () -> properties.setMaxImageBytes(0));
        assertThrows(IllegalArgumentException.class,
                () -> properties.setMaxPixels(-1));
    }
}
