package ooo.klae.connex.backend.integration;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import jakarta.servlet.Filter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import ooo.klae.connex.backend.support.AuthenticatedSessions;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.controllers.MetricsController;
import ooo.klae.connex.backend.services.HealthService;
import ooo.klae.connex.backend.services.HealthService.Readiness;
import ooo.klae.connex.backend.services.HealthService.Status;

@SpringBootTest(properties = "connex.metrics.scrape-token=operator-metrics-token-123456")
class ObservabilityEndpointSecurityTest {
    private static final String SCRAPE_TOKEN = "operator-metrics-token-123456";

    @Autowired private WebApplicationContext context;
    @Autowired private UserMapper userMapper;
    @Autowired @Qualifier("springSecurityFilterChain") private Filter springSecurityFilterChain;
    @MockitoBean private HealthService healthService;

    private MockMvc mockMvc;
    private UsernamePasswordAuthenticationToken authenticated;
    private MockHttpSession authenticatedSession;

    @BeforeEach
    void setUp() {
        when(healthService.readiness())
                .thenReturn(new Readiness(Status.UP, Status.UP, Status.UP, Status.UP));
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(springSecurityFilterChain)
                .build();
        User user = AuthenticatedSessions.account(userMapper, "observability");
        authenticatedSession = AuthenticatedSessions.stampedSession(user);
        authenticated = new UsernamePasswordAuthenticationToken(user, null, List.of());
    }

    @Test
    void healthEndpointsAreAnonymousAndGetOnly() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"status\":\"UP\"}"));
        mockMvc.perform(get("/api/health/ready"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.checks.db").value("UP"))
                .andExpect(jsonPath("$.checks.migrations").value("UP"))
                .andExpect(jsonPath("$.checks.startup").value("UP"))
                .andExpect(jsonPath("$.checks.auditGuard").value("UP"));
        mockMvc.perform(post("/api/health").with(csrf().asHeader()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void metricsAcceptConfiguredScrapeTokenWithoutCreatingSession() throws Exception {
        mockMvc.perform(get("/api/metrics")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + SCRAPE_TOKEN))
                .andExpect(status().isOk())
                .andExpect(content().contentType(
                        MediaType.parseMediaType(MetricsController.PROMETHEUS_CONTENT_TYPE)))
                .andExpect(content().string(containsString("jvm_memory_used_bytes")))
                .andExpect(content().string(containsString("hikaricp")))
                .andExpect(result -> assertNull(result.getRequest().getSession(false)))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
    }

    @Test
    void metricsRejectMissingAndWrongScrapeCredentialsWithoutMetricText() throws Exception {
        mockMvc.perform(get("/api/metrics"))
                .andExpect(status().is4xxClientError())
                .andExpect(content().string(not(containsString("jvm_memory_used_bytes"))));

        mockMvc.perform(get("/api/metrics")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer wrong-token"))
                .andExpect(status().is4xxClientError())
                .andExpect(content().string(not(containsString("jvm_memory_used_bytes"))));
    }

    @Test
    void sessionAuthenticationAloneCannotReadMetricsOnAnyMethod() throws Exception {
        mockMvc.perform(get("/api/metrics")
                        .header("X-Workspace-Id", Integer.MAX_VALUE)
                        .session(authenticatedSession).with(authentication(authenticated)))
                .andExpect(status().isForbidden())
                .andExpect(content().string(not(containsString("jvm_memory_used_bytes"))));

        mockMvc.perform(head("/api/metrics")
                        .session(authenticatedSession).with(authentication(authenticated)))
                .andExpect(status().isForbidden())
                .andExpect(content().string(not(containsString("jvm_memory_used_bytes"))));
    }

    @Test
    void scrapeTokenNeverAuthenticatesAnotherEndpoint() throws Exception {
        mockMvc.perform(get("/api/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + SCRAPE_TOKEN))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void clientReportsRequireAuthenticationAndCsrf() throws Exception {
        String body = "{\"message\":\"Render failed\"}";

        mockMvc.perform(post("/api/client-errors")
                        .with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/client-errors")
                        .session(authenticatedSession).with(authentication(authenticated))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden())
                .andExpect(content().string(not(containsString("workspace membership"))));

        mockMvc.perform(post("/api/client-errors")
                        .session(authenticatedSession).with(authentication(authenticated))
                        .with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden())
                .andExpect(content().string(containsString("workspace membership")));
    }

    @Test
    void actuatorHttpEndpointsAreUnavailable() throws Exception {
        for (String path : List.of("/actuator", "/actuator/health", "/actuator/metrics")) {
            mockMvc.perform(get(path).session(authenticatedSession).with(authentication(authenticated)))
                    .andExpect(result -> assertTrue(result.getResponse().getStatus() >= 400))
                    .andExpect(content().string(not(containsString("\"_links\""))));
        }
    }
}
