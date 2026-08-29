package ooo.klae.connex.backend.connectedaccounts.nativeflow;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import jakarta.servlet.Filter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import ooo.klae.connex.backend.support.AuthenticatedSessions;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.connectedaccounts.ConnectedAccountMode;
import ooo.klae.connex.backend.connectedaccounts.ConnectedAccountProperties;

@SpringBootTest
class NativeConnectSecurityIntegrationTest {
    @Autowired private WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") private Filter springSecurityFilterChain;
    @Autowired private ConnectedAccountProperties properties;
    @Autowired private UserMapper userMapper;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
            .addFilters(springSecurityFilterChain)
            .build();
    }

    @AfterEach
    void tearDown() {
        properties.getGoogle().setEnabled(false);
        properties.getGoogle().setMode(ConnectedAccountMode.CUSTOM);
        properties.getManaged().getGoogle().setClientId(null);
    }

    @Test
    void helperHandoffsAreAnonymousAndCsrfExempt() throws Exception {
        mockMvc.perform(post("/api/account/connections/native/prepare")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "pairingCode": "invalid-pairing-code",
                      "redirectUri": "http://127.0.0.1:49152/callback"
                    }
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("invalid_pairing_code"));

        mockMvc.perform(post("/api/account/connections/native/complete")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "handoffTicket": "invalid-handoff-ticket",
                      "code": "authorization-code",
                      "state": "state"
                    }
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("invalid_handoff_ticket"));
    }

    @Test
    void everyOtherConnectionRouteStillRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/account/connections"))
            .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/account/connections/native/google/pairing")
                .with(csrf().asHeader()))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void blankManagedIdentityReturnsMachineErrorFromAuthenticatedPairingPost()
            throws Exception {
        properties.getGoogle().setEnabled(true);
        properties.getGoogle().setMode(ConnectedAccountMode.MANAGED);
        properties.getManaged().getGoogle().setClientId("");
        User principal = AuthenticatedSessions.account(userMapper, "native-connect-security");
        UsernamePasswordAuthenticationToken authenticated =
            new UsernamePasswordAuthenticationToken(principal, null, List.of());
        MockHttpSession session = AuthenticatedSessions.stampedSession(principal);

        mockMvc.perform(post("/api/account/connections/native/google/pairing")
                .session(session)
                .with(authentication(authenticated))
                .with(csrf().asHeader()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("managed_identity_unavailable"));
    }
}
