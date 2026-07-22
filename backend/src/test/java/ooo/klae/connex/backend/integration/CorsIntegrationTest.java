package ooo.klae.connex.backend.integration;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Locale;

import jakarta.servlet.Filter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Verifies cross-origin preflight requests are handled by Spring Security before
 * authentication and use the narrowed API header allow-list.
 */
@SpringBootTest
class CorsIntegrationTest {

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
    void preflightUsesSecurityCorsConfigurationBeforeAuthentication() throws Exception {
        MvcResult result = mockMvc.perform(options("/api/companies")
                .header("Origin", "http://localhost:3000")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "content-type,idempotency-key,x-csrf-token,x-workspace-id,accept-language"))
            .andExpect(status().isOk())
            .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"))
            .andExpect(header().string("Access-Control-Allow-Credentials", "true"))
            .andExpect(header().string("Access-Control-Allow-Methods", containsString("POST")))
            .andReturn();

        String allowHeaders = result.getResponse().getHeader("Access-Control-Allow-Headers");
        assertThat(allowHeaders, notNullValue());
        assertThat(allowHeaders.toLowerCase(Locale.ROOT), allOf(
            containsString("content-type"),
            containsString("idempotency-key"),
            containsString("x-csrf-token"),
            containsString("x-workspace-id"),
            containsString("accept-language")
        ));
    }

    @Test
    void actualCorsRequestCarriesAllowOriginOnUnauthenticatedResponse() throws Exception {
        mockMvc.perform(get("/api/companies")
                .header("Origin", "http://localhost:3000"))
            .andExpect(status().isUnauthorized())
            .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"))
            .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
    }

    @Test
    void actualCorsRequestAllowsLocalStagingOrigin() throws Exception {
        mockMvc.perform(get("/api/companies")
                .header("Origin", "http://localhost:3001"))
            .andExpect(status().isUnauthorized())
            .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3001"))
            .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
    }

    @Test
    void preflightRejectsHeadersOutsideApiAllowList() throws Exception {
        mockMvc.perform(options("/api/companies")
                .header("Origin", "http://localhost:3000")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "x-not-allowed"))
            .andExpect(status().isForbidden());
    }

    @Test
    void preflightRejectsOriginsOutsideApiAllowList() throws Exception {
        mockMvc.perform(options("/api/companies")
                .header("Origin", "https://evil.example")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "content-type,x-csrf-token"))
            .andExpect(status().isForbidden())
            .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }
}
