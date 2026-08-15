package ooo.klae.connex.backend.integration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.Filter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Exercises signature-webhook provider resolution with the operator gate switched on, which is the
 * only configuration where resolution runs at all. While the feature is disabled every provider key
 * stops at the fail-closed gate instead, so the sibling
 * {@link DocumentSignatureWebhookIntegrationTest} cannot cover these outcomes.
 */
@SpringBootTest
@TestPropertySource(properties = "connex.signature.enabled=true")
class DocumentSignatureWebhookEnabledIntegrationTest {
    @Autowired private WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") private Filter springSecurityFilterChain;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
            .addFilters(springSecurityFilterChain)
            .build();
    }

    @Test
    void unknownProviderIsNotFoundWithoutSessionOrCsrf() throws Exception {
        mockMvc.perform(post("/api/document-signature/webhooks/unknown"))
            .andExpect(status().isNotFound());
    }

    /**
     * The built-in acceptance provider is link-based and registers no callback, so its key must not
     * expose a webhook surface even when the feature is enabled.
     */
    @Test
    void builtInProviderExposesNoWebhookWithoutSessionOrCsrf() throws Exception {
        mockMvc.perform(post("/api/document-signature/webhooks/in_app"))
            .andExpect(status().isNotFound());
    }
}
