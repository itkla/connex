package ooo.klae.connex.backend.integration;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import ooo.klae.connex.backend.config.OneTimeLinkFlowCookie;

/** Exercises the public signature-webhook route through the real security filter chain. */
@SpringBootTest
class DocumentSignatureWebhookIntegrationTest {
    private static final String GRANT = "a".repeat(64);
    private static final String ACCEPT_BODY =
        "{\"flowId\":\"" + "b".repeat(64) + "\",\"typedName\":\"External Signer\"}";

    @Autowired private WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") private Filter springSecurityFilterChain;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
            .addFilters(springSecurityFilterChain)
            .build();
    }

    /**
     * With the signature feature disabled — the default — the webhook reaches its fail-closed gate
     * before it resolves the provider, so every provider key answers alike. That is deliberate: a
     * disabled instance must not disclose which adapters are installed, and it matches the public
     * acceptance endpoints below. Provider resolution, including the 404 for an unknown or
     * webhook-less key, is exercised where the feature is enabled.
     */
    @Test
    void everyProviderReachesTheFailClosedGateWithoutSessionOrCsrf() throws Exception {
        mockMvc.perform(post("/api/document-signature/webhooks/unknown"))
            .andExpect(status().isServiceUnavailable());
        mockMvc.perform(post("/api/document-signature/webhooks/in_app"))
            .andExpect(status().isServiceUnavailable());
    }

    /**
     * The grant cookie is the credential and the exchange body carries the bearer, so both are
     * CSRF-protected; with the header present but no session, each reaches the fail-closed gate
     * before any grant or token lookup.
     */
    @Test
    void publicAcceptanceMutationsReachTheirFailClosedGateWithCsrfButNoSession() throws Exception {
        mockMvc.perform(post("/api/document-acceptance/accept")
                .cookie(grantCookie())
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(ACCEPT_BODY))
            .andExpect(status().isServiceUnavailable());
        mockMvc.perform(post("/api/document-acceptance/exchange")
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"w1-" + "a".repeat(64) + "\"}"))
            .andExpect(status().isServiceUnavailable());
        mockMvc.perform(post("/api/document-acceptance/accept")
                .cookie(grantCookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(ACCEPT_BODY))
            .andExpect(status().isForbidden());
    }

    @Test
    void publicAcceptancePreviewReachesItsFailClosedGateWithTheFeatureDisabled() throws Exception {
        mockMvc.perform(get("/api/document-acceptance").cookie(grantCookie()))
            .andExpect(status().isServiceUnavailable());
    }

    private static Cookie grantCookie() {
        return new Cookie(OneTimeLinkFlowCookie.DOCUMENT_ACCEPTANCE, GRANT);
    }
}
