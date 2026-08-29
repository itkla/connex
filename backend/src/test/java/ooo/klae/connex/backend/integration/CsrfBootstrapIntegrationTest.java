package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import jakarta.servlet.Filter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import ooo.klae.connex.backend.support.AuthenticatedSessions;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.beans.User;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
class CsrfBootstrapIntegrationTest {
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
    void authenticatedBootstrapReturnsOpaquePrincipalAndSessionGeneration() throws Exception {
        User user = AuthenticatedSessions.account(userMapper, "identity-test");
        UsernamePasswordAuthenticationToken authenticated = new UsernamePasswordAuthenticationToken(
                user, null, List.of());

        MvcResult result = mockMvc.perform(get("/api/auth/csrf")
                        .session(AuthenticatedSessions.stampedSession(user))
                        .with(authentication(authenticated)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isString())
                .andExpect(jsonPath("$.headerName").isString())
                .andExpect(jsonPath("$.requestIdentity").isString())
                .andReturn();

        JsonNode response = JsonMapper.builder().build().readTree(result.getResponse().getContentAsString());
        String requestIdentity = response.get("requestIdentity").asString();
        String sessionId = result.getRequest().getSession(false).getId();

        assertNotNull(UUID.fromString(requestIdentity));
        assertNotEquals(Integer.toString(user.getId()), requestIdentity);
        assertNotEquals(sessionId, requestIdentity);
    }
}
