package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;

import jakarta.servlet.Filter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import ooo.klae.connex.backend.services.SessionSecurityService;

/**
 * Verifies the absolute-session timeout is enforced by the real security filter chain.
 */
@SpringBootTest
class AbsoluteSessionTimeoutIntegrationTest {
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
    void expiredApiSessionReturnsUnauthorizedAndInvalidates() throws Exception {
        MockHttpSession session = new MockHttpSession();
        long expiredAt = System.currentTimeMillis() - Duration.ofHours(13).toMillis();
        session.setAttribute(SessionSecurityService.AUTHENTICATED_AT_ATTR, expiredAt);

        mockMvc.perform(get("/api/auth/csrf").session(session))
            .andExpect(status().isUnauthorized());

        assertThrows(IllegalStateException.class,
            () -> session.getAttribute(SessionSecurityService.AUTHENTICATED_AT_ATTR));
    }

    @Test
    void freshApiSessionReachesController() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionSecurityService.AUTHENTICATED_AT_ATTR, System.currentTimeMillis());

        mockMvc.perform(get("/api/auth/csrf").session(session))
            .andExpect(status().isOk());
    }
}
