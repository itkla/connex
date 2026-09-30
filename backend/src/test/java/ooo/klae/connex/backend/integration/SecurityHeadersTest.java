package ooo.klae.connex.backend.integration;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

import jakarta.servlet.Filter;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.HeaderWriterFilter;
import org.springframework.test.util.ReflectionTestUtils;
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

    private static final List<String> SINGLE_VALUED_HEADERS = List.of(
        "Content-Security-Policy",
        "Cache-Control",
        "X-Content-Type-Options",
        "X-Frame-Options",
        "Referrer-Policy");

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

    /**
     * {@code header().string(name, value)} reads only the first value, so it passes on a duplicated
     * header. Duplication is the production symptom of the double header write (#1761), so each
     * header is pinned to exactly one value.
     */
    @Test
    void securityHeadersAreWrittenExactlyOnce() throws Exception {
        var result = mockMvc.perform(get("/api/auth/csrf").secure(true)).andReturn();
        for (String name : SINGLE_VALUED_HEADERS) {
            assertEquals(1, result.getResponse().getHeaders(name).size(),
                () -> name + " was written " + result.getResponse().getHeaders(name).size()
                    + " times: " + result.getResponse().getHeaders(name));
        }
        assertEquals(1, result.getResponse().getHeaders("Strict-Transport-Security").size());
    }

    /**
     * The double write races on any handler that returns a {@code StreamingResponseBody}: the async
     * worker commits the response, firing {@code OnCommittedResponseWrapper}'s hook, while the
     * request thread is still writing the same headers from {@code HeaderWriterFilter}'s
     * {@code finally}. Writing eagerly is what removes both the wrapper and the second write, so
     * every chain must keep it — a new chain that forgets it reintroduces the flake and the
     * duplication (#1761).
     */
    @Test
    void everyChainWritesItsSecurityHeadersEagerly() {
        assertTrue(springSecurityFilterChain instanceof FilterChainProxy,
            "the registered chain must be Spring Security's proxy");
        List<SecurityFilterChain> chains = springSecurityFilterChain instanceof FilterChainProxy proxy
            ? proxy.getFilterChains()
            : List.of();
        assertFalse(chains.isEmpty(), "the context must build at least one security chain");
        int inspected = 0;
        for (SecurityFilterChain chain : chains) {
            for (Filter filter : chain.getFilters()) {
                if (filter instanceof HeaderWriterFilter headerWriter) {
                    inspected++;
                    assertEquals(Boolean.TRUE,
                        ReflectionTestUtils.getField(headerWriter, "shouldWriteHeadersEagerly"),
                        "a chain still writes its security headers on the way out");
                }
            }
        }
        assertTrue(inspected >= chains.size(),
            "every chain must install a HeaderWriterFilter; inspected " + inspected
                + " across " + chains.size() + " chains");
    }
}
