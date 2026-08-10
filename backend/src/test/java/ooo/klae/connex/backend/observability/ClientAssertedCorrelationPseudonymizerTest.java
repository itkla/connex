package ooo.klae.connex.backend.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.config.AuditIntegrityProperties;

class ClientAssertedCorrelationPseudonymizerTest {

    @Test
    void storageAndDisclosureAreStableDomainSeparatedHmacs() {
        ClientAssertedCorrelationPseudonymizer pseudonymizer = pseudonymizer();
        String clientValue = "A0a_".repeat(16);

        String stored = pseudonymizer.forStorage(3, clientValue);
        String disclosed = pseudonymizer.forDisclosure(3, stored);

        assertEquals(stored, pseudonymizer.forStorage(3, clientValue));
        assertEquals(disclosed, pseudonymizer.forDisclosure(3, stored));
        assertNotEquals(stored, disclosed);
        assertNotEquals(stored, pseudonymizer.forStorage(4, clientValue));
        assertNotEquals(disclosed, pseudonymizer.forDisclosure(4, stored));
        assertFalse(stored.contains(clientValue));
        assertFalse(disclosed.contains(clientValue));
        assertEquals(64, stored.length());
        assertEquals(64, disclosed.length());
    }

    private static ClientAssertedCorrelationPseudonymizer pseudonymizer() {
        AuditIntegrityProperties properties = new AuditIntegrityProperties();
        properties.setHmacSecret("test-correlation-hmac-secret-change-me");
        return new ClientAssertedCorrelationPseudonymizer(properties);
    }
}
