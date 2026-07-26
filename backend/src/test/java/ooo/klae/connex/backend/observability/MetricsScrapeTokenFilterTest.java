package ooo.klae.connex.backend.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

class MetricsScrapeTokenFilterTest {
    private static final String TOKEN = "operator-metrics-token-123456";

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void validTokenAuthenticatesOnlyForTheFilterChainSpanWithoutCreatingSession() throws Exception {
        MetricsScrapeTokenFilter filter = new MetricsScrapeTokenFilter(TOKEN);
        MockHttpServletRequest request = request("/api/metrics", "Bearer " + TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<Authentication> duringRequest = new AtomicReference<>();

        filter.doFilter(request, response, (servletRequest, servletResponse) ->
                duringRequest.set(SecurityContextHolder.getContext().getAuthentication()));

        assertEquals("metrics-scraper", duringRequest.get().getPrincipal());
        assertEquals("METRICS_SCRAPE", duringRequest.get().getAuthorities().iterator().next().getAuthority());
        assertNull(request.getSession(false));
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void wrongDuplicateAndBlankConfiguredTokensFailClosed() throws Exception {
        assertUnauthenticated(new MetricsScrapeTokenFilter(TOKEN),
                request("/api/metrics", "Bearer wrong-token"));

        MockHttpServletRequest duplicate = request("/api/metrics", "Bearer " + TOKEN);
        duplicate.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN);
        assertUnauthenticated(new MetricsScrapeTokenFilter(TOKEN), duplicate);

        assertUnauthenticated(new MetricsScrapeTokenFilter(""),
                request("/api/metrics", "Bearer " + TOKEN));
        assertUnauthenticated(new MetricsScrapeTokenFilter("   "),
                request("/api/metrics", "Bearer " + TOKEN));
    }

    @Test
    void tokenNeverAuthenticatesAnotherPathOrMethod() throws Exception {
        assertUnauthenticated(new MetricsScrapeTokenFilter(TOKEN),
                request("/api/users/me", "Bearer " + TOKEN));
        MockHttpServletRequest post = request("/api/metrics", "Bearer " + TOKEN);
        post.setMethod("POST");
        assertUnauthenticated(new MetricsScrapeTokenFilter(TOKEN), post);
    }

    private static void assertUnauthenticated(
            MetricsScrapeTokenFilter filter,
            MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<Authentication> duringRequest = new AtomicReference<>();

        filter.doFilter(request, response, (servletRequest, servletResponse) ->
                duringRequest.set(SecurityContextHolder.getContext().getAuthentication()));

        assertNull(duringRequest.get());
        assertNull(request.getSession(false));
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    private static MockHttpServletRequest request(String path, String authorization) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.addHeader(HttpHeaders.AUTHORIZATION, authorization);
        return request;
    }
}
