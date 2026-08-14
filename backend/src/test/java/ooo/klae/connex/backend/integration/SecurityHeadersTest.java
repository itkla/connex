package ooo.klae.connex.backend.integration;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

import jakarta.servlet.Filter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Verifies the hardening security headers (#88) are emitted on responses through
 * the real security filter chain: a restrictive Content-Security-Policy with
 * {@code frame-ancestors 'none'}, the default {@code X-Frame-Options: DENY}, a
 * strict cross-origin referrer policy, and HSTS on secure requests.
 */
@SpringBootTest
class SecurityHeadersTest {

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
    void responsesCarrySecurityHeaders() throws Exception {
        mockMvc.perform(get("/api/auth/csrf"))
            .andExpect(header().string("Content-Type", containsString("application/json")))
            .andExpect(header().string("Content-Security-Policy", containsString("frame-ancestors 'none'")))
            .andExpect(header().string("Cache-Control", containsString("no-store")))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
            .andExpect(header().string("X-Frame-Options", "DENY"))
            .andExpect(header().string("Referrer-Policy", "strict-origin-when-cross-origin"));
    }

    @Test
    void secureResponsesCarryHsts() throws Exception {
        mockMvc.perform(get("/api/auth/csrf").secure(true))
            .andExpect(header().string("Strict-Transport-Security", containsString("max-age=31536000")));
    }
}
