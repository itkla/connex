package ooo.klae.connex.backend.ai.egress;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.ai.AiProperties;

class AiEndpointAddressValidatorTest {
    private final AiEndpointAddressValidator validator = new AiEndpointAddressValidator(new AiProperties());

    @Test
    void isFetchableAppliesTheRuntimeAddressClassPolicy() {
        assertTrue(validator.isFetchable("8.8.8.8", false));
        assertFalse(validator.isFetchable("10.0.0.12", false));
        assertTrue(validator.isFetchable("10.0.0.12", true));
        assertFalse(validator.isFetchable("8.8.8.8", true));
    }

    @Test
    void constructorRejectsInvalidNat64Configuration() {
        AiProperties properties = new AiProperties();
        properties.setNat64Prefixes("2001:db8::/33");

        assertThrows(IllegalStateException.class, () -> new AiEndpointAddressValidator(properties));
    }
}
