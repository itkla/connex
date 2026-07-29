package ooo.klae.connex.backend.integration;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import jakarta.servlet.Filter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.services.HealthService;

@SpringBootTest(properties = "connex.metrics.scrape-token=")
class MetricsScrapeTokenDisabledSecurityTest {
    @Autowired private WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") private Filter springSecurityFilterChain;
    @MockitoBean private HealthService healthService;

    private MockMvc mockMvc;
    private UsernamePasswordAuthenticationToken authenticated;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(springSecurityFilterChain)
                .build();
        User user = new User();
        user.setId(Integer.MAX_VALUE - 1);
        user.setUsername("metrics-disabled-security-test");
        authenticated = new UsernamePasswordAuthenticationToken(user, null, List.of());
    }

    @Test
    void bearerTokenFailsClosedWhenOperatorTokenIsBlank() throws Exception {
        mockMvc.perform(get("/api/metrics")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer operator-metrics-token-123456"))
                .andExpect(status().is4xxClientError())
                .andExpect(content().string(not(containsString("jvm_memory_used_bytes"))));
    }

    @Test
    void sessionAuthenticationCannotSubstituteForTheAbsentScrapeToken() throws Exception {
        mockMvc.perform(get("/api/metrics").with(authentication(authenticated)))
                .andExpect(status().isForbidden())
                .andExpect(content().string(not(containsString("jvm_memory_used_bytes"))));
    }
}
