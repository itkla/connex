package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class ProviderConnectionMapperXmlTest {

    @Test
    void revocationIsGenerationFencedAndLeavesNoCredential() throws Exception {
        String source = resource("mappers/ProviderConnectionMapper.xml");

        assertTrue(source.contains("status = 'revoking'"));
        assertTrue(source.contains("status = 'disconnected'"));
        assertTrue(source.contains("credential_generation = credential_generation + 1"));
        assertTrue(source.contains("credential_generation = #{generation}"));
        assertTrue(source.contains("credential_ref = NULL"));
        assertTrue(source.contains("access_token_expires_at = NULL"));
        assertFalse(source.contains("provider_account_id = NULL"));
        assertFalse(source.contains("last_sync_at"));
        assertFalse(source.contains("${"));
    }

    private static String resource(String path) throws Exception {
        try (InputStream input = ProviderConnectionMapperXmlTest.class
                .getClassLoader().getResourceAsStream(path)) {
            assertNotNull(input, "Missing mapper " + path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
