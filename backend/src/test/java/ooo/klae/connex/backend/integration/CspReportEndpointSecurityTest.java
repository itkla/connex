package ooo.klae.connex.backend.integration;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.support.AuthenticatedSessions;

@SpringBootTest
class CspReportEndpointSecurityTest {
    private static final MediaType CSP_REPORT = MediaType.parseMediaType("application/csp-report");
    private static final String LEGACY_BODY = """
            {"csp-report":{"document-uri":"https://connex.example.com/dashboard?token=secret",
            "effective-directive":"img-src","blocked-uri":"https://cdn.example.invalid/logo.png",
            "disposition":"enforce","status-code":200}}
            """;

    @Autowired private WebApplicationContext context;
    @Autowired private UserMapper userMapper;
    @Autowired @Qualifier("springSecurityFilterChain") private Filter springSecurityFilterChain;

    private MockMvc mockMvc;
    private UsernamePasswordAuthenticationToken authenticated;
    private MockHttpSession authenticatedSession;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(springSecurityFilterChain)
                .build();
        User user = AuthenticatedSessions.account(userMapper, "csp-report");
        authenticatedSession = AuthenticatedSessions.stampedSession(user);
        authenticated = new UsernamePasswordAuthenticationToken(user, null, List.of());
    }

    @Test
    void anonymousReportsAreAcceptedWithoutCsrf() throws Exception {
        mockMvc.perform(post("/api/csp-reports").contentType(CSP_REPORT).content(LEGACY_BODY))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
    }

    @Test
    void sessionBearingReportsAreAcceptedWithoutCsrfAndDespiteAStaleWorkspacePin() throws Exception {
        mockMvc.perform(post("/api/csp-reports")
                        .session(authenticatedSession).with(authentication(authenticated))
                        .contentType(CSP_REPORT).content(LEGACY_BODY))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/csp-reports")
                        .session(authenticatedSession).with(authentication(authenticated))
                        .cookie(new Cookie("connex_workspace", String.valueOf(Integer.MAX_VALUE)))
                        .header("X-Workspace-Id", Integer.MAX_VALUE)
                        .contentType(CSP_REPORT).content(LEGACY_BODY))
                .andExpect(status().isNoContent());
    }

    @Test
    void garbageBodiesStillAnswerNoContent() throws Exception {
        mockMvc.perform(post("/api/csp-reports").contentType(CSP_REPORT).content("not json"))
                .andExpect(status().isNoContent());
    }

    @Test
    void readsAndWritesOnOtherMethodsAreNotPermitted() throws Exception {
        mockMvc.perform(get("/api/csp-reports"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(put("/api/csp-reports").with(csrf().asHeader())
                        .contentType(CSP_REPORT).content(LEGACY_BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void otherContentTypesAreRefused() throws Exception {
        mockMvc.perform(post("/api/csp-reports").contentType(MediaType.TEXT_PLAIN).content("x"))
                .andExpect(status().isUnsupportedMediaType());
    }
}
