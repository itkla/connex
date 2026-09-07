package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.ServletRequestPathUtils;

import ooo.klae.connex.backend.tenant.TenantResolutionInterceptor;

/**
 * The collector's admission rules, on the shipped {@code spring.session.servlet.filter-order}.
 *
 * <p>Inherits the session-neutrality cases; see {@link AbstractCspReportSessionNeutralityTest}.
 */
@SpringBootTest
class CspReportEndpointSecurityTest extends AbstractCspReportSessionNeutralityTest {
    @Autowired private RequestMappingHandlerMapping requestMappingHandlerMapping;

    @Test
    void anonymousReportsAreAcceptedWithoutCsrfAndStartNoSession() throws Exception {
        mockMvc.perform(post("/api/csp-reports").contentType(CSP_REPORT).content(LEGACY_BODY))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
    }

    @Test
    void cookieBearingReportsAreAcceptedWithoutCsrfOrAWorkspace() throws Exception {
        Cookie sessionCookie = sessionCookie(storedAuthenticatedSession());

        mockMvc.perform(post("/api/csp-reports").cookie(sessionCookie)
                        .contentType(CSP_REPORT).content(LEGACY_BODY))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/csp-reports").cookie(sessionCookie)
                        .cookie(new Cookie("connex_workspace", String.valueOf(Integer.MAX_VALUE)))
                        .header("X-Workspace-Id", Integer.MAX_VALUE)
                        .contentType(CSP_REPORT).content(LEGACY_BODY))
                .andExpect(status().isNoContent());
    }

    /**
     * Tenant resolution runs in the {@code DispatcherServlet}, after whichever chain served the
     * request, so no security chain can keep it off the collector — only {@code WebConfig}'s
     * exclusion can. A cookie-bearing report reaches the interceptor anonymous today, which makes
     * the 403 the exclusion prevents unobservable through the endpoint; assert the mapping instead.
     */
    @Test
    void tenantResolutionIsNotMappedOntoTheCollectorPath() throws Exception {
        MockHttpServletRequest report = new MockHttpServletRequest("POST", "/api/csp-reports");
        report.setContentType(CSP_REPORT.toString());

        assertTrue(interceptorsFor(new MockHttpServletRequest("GET", "/api/deals")).stream()
                .anyMatch(TenantResolutionInterceptor.class::isInstance));
        assertFalse(interceptorsFor(report).stream()
                .anyMatch(TenantResolutionInterceptor.class::isInstance));
    }

    @Test
    void garbageBodiesStillAnswerNoContent() throws Exception {
        mockMvc.perform(post("/api/csp-reports").contentType(CSP_REPORT).content("not json"))
                .andExpect(status().isNoContent());
    }

    @Test
    void readsAndWritesOnOtherMethodsAreNotPermittedAndStartNoSession() throws Exception {
        mockMvc.perform(get("/api/csp-reports"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
        mockMvc.perform(put("/api/csp-reports").with(csrf().asHeader())
                        .contentType(CSP_REPORT).content(LEGACY_BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void otherContentTypesAreRefused() throws Exception {
        mockMvc.perform(post("/api/csp-reports").contentType(MediaType.TEXT_PLAIN).content("x"))
                .andExpect(status().isUnsupportedMediaType());
    }

    private List<HandlerInterceptor> interceptorsFor(MockHttpServletRequest request) throws Exception {
        ServletRequestPathUtils.parseAndCache(request);
        HandlerExecutionChain chain = requestMappingHandlerMapping.getHandler(request);
        assertNotNull(chain);
        return chain.getInterceptorList();
    }
}
