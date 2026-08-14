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

import ooo.klae.connex.backend.beans.User;

@SpringBootTest(properties = {
    "connex.mail.managed=true",
    "connex.security.privileged-mfa.enforced=true"
})
class CapabilitiesEndpointIntegrationTest {

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
    void unauthenticatedClientReadsCapabilities() throws Exception {
        mockMvc.perform(get("/api/capabilities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mailManaged").value(true))
                .andExpect(jsonPath("$.sso").value(true))
                .andExpect(jsonPath("$.socialLogin.google").value(false))
                .andExpect(jsonPath("$.socialLogin.microsoft").value(false))
                .andExpect(jsonPath("$.businessCardScanning").isBoolean())
                .andExpect(jsonPath("$.businessCardImport").isBoolean())
                .andExpect(jsonPath("$.privilegedMfaEnforced").value(true));
    }

    @Test
    void authenticatedClientReadsCapabilitiesWithoutWorkspaceContext() throws Exception {
        User user = new User();
        user.setId(7);
        user.setUsername("capabilities-test");
        UsernamePasswordAuthenticationToken authenticated = new UsernamePasswordAuthenticationToken(
                user, null, List.of());

        mockMvc.perform(get("/api/capabilities")
                        .header("X-Workspace-Id", Integer.MAX_VALUE)
                        .with(authentication(authenticated)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mailManaged").value(true));
    }
}
