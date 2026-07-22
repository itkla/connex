package ooo.klae.connex.backend.ai;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class AiPropertiesTest {

    @Test
    void instanceAiRequiresExplicitOperatorOptIn() throws IOException {
        ClassPathResource applicationConfig = new ClassPathResource("application.yml");
        String yaml = new String(applicationConfig.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertFalse(new AiProperties().isEnabled());
        assertTrue(new AiProperties().getNat64Prefixes().isEmpty());
        assertEquals(2, new AiProperties().getMaxConcurrentMediaRequests());
        assertEquals(1, new AiProperties().getMaxConcurrentMediaRequestsPerOrg());
        assertEquals(67108864, new AiProperties().getMaxMediaWorkingBytes());
        assertTrue(yaml.contains("enabled: ${CONNEX_AI_ENABLED:false}"));
        assertTrue(yaml.contains("nat64-prefixes: ${CONNEX_AI_NAT64_PREFIXES:}"));
        assertTrue(yaml.contains("max-concurrent-media-requests: ${CONNEX_AI_MAX_CONCURRENT_MEDIA_REQUESTS:2}"));
    }
}
