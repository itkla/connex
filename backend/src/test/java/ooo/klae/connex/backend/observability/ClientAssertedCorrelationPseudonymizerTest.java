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

    @Test
    void storageAndDisclosureDependOnDeploymentHmacSecret() {
        ClientAssertedCorrelationPseudonymizer first = pseudonymizer();
        ClientAssertedCorrelationPseudonymizer second =
            pseudonymizer("second-test-correlation-hmac-secret-change-me");
        String clientValue = "A0a_".repeat(16);
        String stored = first.forStorage(3, clientValue);

        assertNotEquals(stored, second.forStorage(3, clientValue),
            "Catches HMAC replaced with an unkeyed digest for storage");
        assertNotEquals(first.forDisclosure(3, stored), second.forDisclosure(3, stored),
            "Catches HMAC replaced with an unkeyed digest for disclosure");
    }

    @Test
    void exactDomainLabelsMatchPinnedOpenSslVectors() {
        ClientAssertedCorrelationPseudonymizer pseudonymizer = pseudonymizer();
        String clientValue = "A0a_".repeat(16);
        String stored = pseudonymizer.forStorage(3, clientValue);

        assertEquals("2a38271a47de3e6200ad4906ccfcdf196074b7377bebd076410cb84db6471320",
            stored, "Catches any edit to the exact STORAGE_DOMAIN label or storage framing");
        assertEquals("a5ebdde2d170a714bd1a362cf30f3b9b2ba9d710f5d427bd0ff157a901df095e",
            pseudonymizer.forDisclosure(3, stored),
            "Catches DISCLOSURE_DOMAIN set equal to STORAGE_DOMAIN or any disclosure label edit");
    }

    private static ClientAssertedCorrelationPseudonymizer pseudonymizer() {
        return pseudonymizer("test-correlation-hmac-secret-change-me");
    }

    private static ClientAssertedCorrelationPseudonymizer pseudonymizer(String hmacSecret) {
        AuditIntegrityProperties properties = new AuditIntegrityProperties();
        properties.setHmacSecret(hmacSecret);
        return new ClientAssertedCorrelationPseudonymizer(properties);
    }
}
