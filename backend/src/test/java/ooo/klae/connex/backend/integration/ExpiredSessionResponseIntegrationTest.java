package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

/**
 * Verifies that a caller whose session is gone is told so with 401 on every authenticated read the
 * app shell performs. The frontend renders a retryable "unavailable" state for any other failure,
 * so an endpoint answering an absent session with a different status leaves a browser holding a
 * stale cookie stuck on that state: the retry re-reads the same rejection forever.
 */
@SpringBootTest
class ExpiredSessionResponseIntegrationTest {

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
    void currentUserWithoutSessionIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void currentUserWithUnauthenticatedSessionIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/auth/me").session(new MockHttpSession()))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void workspacesWithoutSessionIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/workspaces"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void csrfBootstrapStaysReachableWithoutSession() throws Exception {
        mockMvc.perform(get("/api/auth/csrf"))
            .andExpect(status().isOk());
    }

    @Test
    void rejectingAnAnonymousReadCreatesNoSession() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
            .andExpect(status().isUnauthorized())
            .andExpect(result -> assertNull(
                result.getRequest().getSession(false),
                "a rejected anonymous read must not open a session"));
    }
}
