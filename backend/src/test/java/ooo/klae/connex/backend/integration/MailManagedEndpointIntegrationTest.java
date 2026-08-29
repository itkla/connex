package ooo.klae.connex.backend.integration;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import jakarta.servlet.Filter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import ooo.klae.connex.backend.support.AuthenticatedSessions;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.beans.User;

@SpringBootTest(properties = "connex.mail.managed=true")
class MailManagedEndpointIntegrationTest {

    @Autowired private WebApplicationContext context;
    @Autowired private UserMapper userMapper;
    @Autowired @Qualifier("springSecurityFilterChain") private Filter springSecurityFilterChain;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(springSecurityFilterChain)
                .build();
    }

    @Test
    void authenticatedUserReadsManagedFlagWithoutWorkspaceContext() throws Exception {
        User user = AuthenticatedSessions.account(userMapper, "mail-managed-test");
        UsernamePasswordAuthenticationToken authenticated = new UsernamePasswordAuthenticationToken(
                user, null, List.of());

        mockMvc.perform(get("/api/mail/managed")
                        .header("X-Workspace-Id", Integer.MAX_VALUE)
                        .session(AuthenticatedSessions.stampedSession(user))
                        .with(authentication(authenticated)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.managed").value(true));
    }

    @Test
    void unauthenticatedUserReadsManagedFlag() throws Exception {
        mockMvc.perform(get("/api/mail/managed"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.managed").value(true));
    }
}
