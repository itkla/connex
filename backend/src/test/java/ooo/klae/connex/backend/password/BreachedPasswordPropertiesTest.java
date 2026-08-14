package ooo.klae.connex.backend.password;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class BreachedPasswordPropertiesTest {

    @Test
    void remoteIsDefaultAndThereIsNoDisabledSource() {
        BreachedPasswordProperties properties = new BreachedPasswordProperties();

        assertEquals(BreachedPasswordSourceType.REMOTE, properties.sourceType());

        properties.setSource("DISABLED");
        assertThrows(IllegalStateException.class, properties::sourceType);
    }
}
