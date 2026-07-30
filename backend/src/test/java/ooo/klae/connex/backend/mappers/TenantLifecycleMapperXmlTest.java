package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/** Pins the registry-validated streaming shape used by tenant snapshots. */
class TenantLifecycleMapperXmlTest {

    @Test
    void objectReferencesUseOneStreamingDistinctUnionWithTheUsageLedger() throws Exception {
        String resource = "mappers/TenantLifecycleMapper.xml";
        String xml;
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input);
            xml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertTrue(xml.contains("id=\"streamRows\""));
        assertTrue(xml.contains("id=\"streamActiveObjectReferences\""));
        assertTrue(xml.contains("resultSetType=\"FORWARD_ONLY\""));
        assertTrue(xml.contains("fetchSize=\"-2147483648\""));
        assertTrue(xml.contains("useCache=\"false\""));
        assertTrue(xml.contains("UNION DISTINCT"));
        assertTrue(xml.contains("LEFT JOIN managed_object_usage"));
        assertTrue(xml.contains("ORDER BY candidates.object_key"));
        assertFalse(xml.contains("id=\"findActiveObjectReference\""));
        assertFalse(xml.contains("id=\"findActiveObjectReferencesAfter\""));
    }
}
