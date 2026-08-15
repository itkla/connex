package ooo.klae.connex.backend.integration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.Filter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** Exercises the public signature-webhook route through the real security filter chain. */
@SpringBootTest
class DocumentSignatureWebhookIntegrationTest {
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
    void unknownAndInAppProvidersReturnNotFoundWithoutSessionOrCsrf() throws Exception {
        mockMvc.perform(post("/api/document-signature/webhooks/unknown"))
            .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/document-signature/webhooks/in_app"))
            .andExpect(status().isNotFound());
    }

    @Test
    void publicAcceptanceMutationReachesItsFailClosedGateWithoutSessionOrCsrf() throws Exception {
        mockMvc.perform(post("/api/document-acceptance/w1-" + "a".repeat(64) + "/accept")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"typedName\":\"External Signer\"}"))
            .andExpect(status().isServiceUnavailable());
    }
}
