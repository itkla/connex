package ooo.klae.connex.backend.integration;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;

import jakarta.servlet.Filter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import ooo.klae.connex.backend.config.PublicApiSecurityConfig;
import ooo.klae.connex.backend.controllers.v1.PublicApiIdentityController;

/** Proves the dormant public plane contributes neither its chain nor controllers. */
@SpringBootTest(properties = "connex.public-api.enabled=false")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PublicApiDisabledIntegrationTest {
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
    void disabledPlaneHasNoPublicChainAndFailsWithPublicEnvelope() throws Exception {
        assertTrue(context.getBeansOfType(PublicApiSecurityConfig.class).isEmpty());
        assertTrue(context.getBeansOfType(PublicApiIdentityController.class).isEmpty());

        mockMvc.perform(get("/api/v1/me"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.error.code").value("public_api_unavailable"))
            .andExpect(jsonPath("$.error.request_id").isString());

        mockMvc.perform(options("/api/v1/me")
                .header(HttpHeaders.ORIGIN, "https://untrusted.example")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error.code").value("invalid_cors_request"))
            .andExpect(jsonPath("$.error.request_id").isString());
    }

    @Test
    void disabledPlaneSanitizesMalformedPublicPathsAtTheFirewall() throws Exception {
        mockMvc.perform(request(
                org.springframework.http.HttpMethod.GET,
                URI.create("/api/v1%2Fme")).secure(true))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.error.code").value("public_api_unavailable"))
            .andExpect(jsonPath("$.error.request_id").isString())
            .andExpect(header().string("Content-Security-Policy", containsString("default-src 'none'")))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
            .andExpect(header().string("Referrer-Policy", "no-referrer"));

        mockMvc.perform(get("/api/v1;blocked/me").secure(true))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.error.code").value("public_api_unavailable"))
            .andExpect(jsonPath("$.error.request_id").isString())
            .andExpect(header().string("Content-Security-Policy", containsString("default-src 'none'")))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
            .andExpect(header().string("Referrer-Policy", "no-referrer"));
    }
}
