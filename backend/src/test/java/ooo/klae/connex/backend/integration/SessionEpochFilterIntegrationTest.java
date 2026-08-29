package ooo.klae.connex.backend.integration;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import jakarta.servlet.Filter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.services.SessionSecurityService;
import ooo.klae.connex.backend.support.AuthenticatedSessions;

/**
 * Drives the epoch check over the real filter chain (#1477).
 *
 * <p>This is the executable guard for the security property. Without it, emptying
 * {@code SessionEpochFilter.refuse} breaks nothing: the unit-level tests call the services directly
 * and never build a chain, and every other chain test attaches a matching stamp, so they exercise
 * only the pass-through branch.
 */
@SpringBootTest
class SessionEpochFilterIntegrationTest {

    @Autowired private WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") private Filter springSecurityFilterChain;
    @Autowired private UserMapper userMapper;

    private MockMvc mockMvc;
    private User account;
    private UsernamePasswordAuthenticationToken authenticated;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(springSecurityFilterChain)
                .build();
        account = AuthenticatedSessions.account(userMapper, "session-epoch-filter");
        authenticated = new UsernamePasswordAuthenticationToken(account, null, List.of());
    }

    @Test
    void aSessionStampedWithTheCurrentEpochIsServed() throws Exception {
        mockMvc.perform(get("/api/auth/me")
                        .session(AuthenticatedSessions.stampedSession(account))
                        .with(authentication(authenticated)))
                .andExpect(status().isOk());
    }

    /**
     * The #1477 race, end to end: the session was stamped before a revocation advanced the account,
     * so the request that follows is de-authenticated even though the security context arrived
     * authenticated.
     */
    @Test
    void aSessionStampedBeforeARevocationIsRefused() throws Exception {
        MockHttpSession session = AuthenticatedSessions.stampedSession(account);

        userMapper.bumpSessionEpoch(account.getId());

        mockMvc.perform(get("/api/auth/me")
                        .session(session)
                        .with(authentication(authenticated)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aSessionCarryingNoStampIsRefusedRatherThanDefaulted() throws Exception {
        mockMvc.perform(get("/api/auth/me")
                        .session(new MockHttpSession())
                        .with(authentication(authenticated)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aStampForAnAccountThatNoLongerExistsIsRefused() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionSecurityService.SESSION_EPOCH_ATTR, 0);
        User deleted = new User();
        deleted.setId(Integer.MAX_VALUE - 7);
        deleted.setUsername("session-epoch-filter-absent");

        mockMvc.perform(get("/api/auth/me")
                        .session(session)
                        .with(authentication(
                                new UsernamePasswordAuthenticationToken(deleted, null, List.of()))))
                .andExpect(status().isUnauthorized());
    }
}
