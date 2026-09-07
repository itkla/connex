package ooo.klae.connex.backend.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import ooo.klae.connex.backend.services.LoginRateLimiter;
import ooo.klae.connex.backend.util.ClientIpResolver;
import ooo.klae.connex.backend.util.ClientIpResolver.ResolvedClientIp;

class OneTimeLinkExchangeAdmissionFilterTest {

    @ParameterizedTest
    @ValueSource(strings = {
        "/api/account/connections/native/prepare",
        "/api/account/connections/native/complete"
    })
    void nativeBearerExchangesAreAdmittedAndThenThrottled(String path) throws Exception {
        LoginRateLimiter rateLimiter = new LoginRateLimiter(1, 100, 5000, 900);
        ClientIpResolver clientIpResolver = mock(ClientIpResolver.class);
        OneTimeLinkExchangeAdmissionFilter filter =
            new OneTimeLinkExchangeAdmissionFilter(rateLimiter, clientIpResolver);
        when(clientIpResolver.resolveWithProvenance(org.mockito.ArgumentMatchers.any()))
            .thenReturn(new ResolvedClientIp("203.0.113.10", false));

        MockHttpServletResponse firstResponse = invoke(filter, path);
        MockHttpServletResponse secondResponse = invoke(filter, path);

        assertEquals(200, firstResponse.getStatus());
        assertEquals(429, secondResponse.getStatus());
    }

    @Test
    void unsubscribeExchangeIsBudgetedLikeOtherExchanges() throws Exception {
        LoginRateLimiter rateLimiter = new LoginRateLimiter(1, 100, 5000, 900);
        ClientIpResolver clientIpResolver = mock(ClientIpResolver.class);
        OneTimeLinkExchangeAdmissionFilter filter =
            new OneTimeLinkExchangeAdmissionFilter(rateLimiter, clientIpResolver);
        when(clientIpResolver.resolveWithProvenance(org.mockito.ArgumentMatchers.any()))
            .thenReturn(new ResolvedClientIp("203.0.113.11", false));

        MockHttpServletResponse firstResponse = invoke(filter, "/api/delivery/unsubscribe/exchange");
        MockHttpServletResponse secondResponse = invoke(filter, "/api/delivery/unsubscribe/exchange");
        MockHttpServletResponse previewResponse = invoke(filter, "/api/delivery/unsubscribe");

        assertEquals(200, firstResponse.getStatus());
        assertEquals(429, secondResponse.getStatus());
        assertEquals(200, previewResponse.getStatus());
    }

    /**
     * The document-acceptance admission filter deliberately skips its exchange path because the
     * bearer lives in the JSON body; this per-source budget is therefore the only throttle applied
     * before that body is read and validated.
     */
    @Test
    void documentAcceptanceExchangeIsBudgetedBeforeTheBodyIsRead() throws Exception {
        LoginRateLimiter rateLimiter = new LoginRateLimiter(1, 100, 5000, 900);
        ClientIpResolver clientIpResolver = mock(ClientIpResolver.class);
        OneTimeLinkExchangeAdmissionFilter filter =
            new OneTimeLinkExchangeAdmissionFilter(rateLimiter, clientIpResolver);
        when(clientIpResolver.resolveWithProvenance(org.mockito.ArgumentMatchers.any()))
            .thenReturn(new ResolvedClientIp("203.0.113.12", false));
        MockHttpServletRequest throttled = new MockHttpServletRequest(
            "POST", "/api/document-acceptance/exchange");
        throttled.setServletPath("/api/document-acceptance/exchange");
        throttled.setContent("{\"token\":\"".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        MockHttpServletResponse throttledResponse = new MockHttpServletResponse();
        MockFilterChain throttledChain = new MockFilterChain();

        MockHttpServletResponse firstResponse = invoke(filter, "/api/document-acceptance/exchange");
        filter.doFilter(throttled, throttledResponse, throttledChain);
        MockHttpServletResponse previewResponse = invoke(filter, "/api/document-acceptance");

        assertEquals(200, firstResponse.getStatus());
        assertEquals(429, throttledResponse.getStatus());
        assertNull(throttledChain.getRequest());
        assertEquals(200, previewResponse.getStatus());
    }

    /**
     * Path parameters and repeated slashes are ignored by handler mapping, so an exchange spelled
     * either way still reaches its controller. It must therefore consume the same per-source budget
     * as the canonical spelling rather than slipping past this filter unbudgeted.
     */
    @ParameterizedTest
    @CsvSource({
        "/api/delivery/unsubscribe/exchange;x=1, /api/delivery/unsubscribe/exchange",
        "//api/delivery/unsubscribe/exchange, /api/delivery/unsubscribe/exchange",
        "/api/document-acceptance/exchange;x=1, /api/document-acceptance/exchange",
        "//api/document-acceptance/exchange, /api/document-acceptance/exchange",
        "/api/auth/reset-password/exchange;x=1, /api/auth/reset-password/exchange",
        "//api/auth/reset-password/exchange, /api/auth/reset-password/exchange"
    })
    void alternateSpellingsConsumeTheSameBudgetAsTheCanonicalExchange(
            String spelling, String canonical) throws Exception {
        LoginRateLimiter rateLimiter = new LoginRateLimiter(1, 100, 5000, 900);
        ClientIpResolver clientIpResolver = mock(ClientIpResolver.class);
        OneTimeLinkExchangeAdmissionFilter filter =
            new OneTimeLinkExchangeAdmissionFilter(rateLimiter, clientIpResolver);
        when(clientIpResolver.resolveWithProvenance(org.mockito.ArgumentMatchers.any()))
            .thenReturn(new ResolvedClientIp("203.0.113.13", false));

        MockHttpServletResponse spelledResponse = invoke(filter, spelling);
        MockHttpServletResponse canonicalResponse = invoke(filter, canonical);

        assertEquals(200, spelledResponse.getStatus(), spelling);
        assertEquals(429, canonicalResponse.getStatus(), spelling);
    }

    /** A deployment context path must not shift an exchange path out of the budgeted set. */
    @Test
    void aContextPathPrefixStillMatchesTheExchangePath() throws Exception {
        LoginRateLimiter rateLimiter = new LoginRateLimiter(1, 100, 5000, 900);
        ClientIpResolver clientIpResolver = mock(ClientIpResolver.class);
        OneTimeLinkExchangeAdmissionFilter filter =
            new OneTimeLinkExchangeAdmissionFilter(rateLimiter, clientIpResolver);
        when(clientIpResolver.resolveWithProvenance(org.mockito.ArgumentMatchers.any()))
            .thenReturn(new ResolvedClientIp("203.0.113.14", false));

        MockHttpServletResponse firstResponse =
            invokeWithContextPath(filter, "/connex", "/connex/api/invites/exchange");
        MockHttpServletResponse secondResponse =
            invokeWithContextPath(filter, "/connex", "/connex/api/invites/exchange");

        assertEquals(200, firstResponse.getStatus());
        assertEquals(429, secondResponse.getStatus());
    }

    @Test
    void rejectsAnExchangeAfterTheExistingIpBudgetIsExhausted() throws Exception {
        LoginRateLimiter rateLimiter = new LoginRateLimiter(1, 100, 5000, 900);
        ClientIpResolver clientIpResolver = mock(ClientIpResolver.class);
        OneTimeLinkExchangeAdmissionFilter filter =
            new OneTimeLinkExchangeAdmissionFilter(rateLimiter, clientIpResolver);
        when(clientIpResolver.resolveWithProvenance(org.mockito.ArgumentMatchers.any()))
            .thenReturn(new ResolvedClientIp("203.0.113.9", false));

        MockHttpServletResponse firstResponse = invoke(filter, "/api/invites/exchange");
        MockHttpServletResponse secondResponse = invoke(filter, "/api/invites/exchange");

        assertEquals(200, firstResponse.getStatus());
        assertEquals(429, secondResponse.getStatus());
    }

    @Test
    void sharedProxyFallbackDoesNotUseTheOrdinaryPerClientLimit() throws Exception {
        LoginRateLimiter rateLimiter = new LoginRateLimiter(1, 100, 2, 900);
        ClientIpResolver clientIpResolver = mock(ClientIpResolver.class);
        OneTimeLinkExchangeAdmissionFilter filter =
            new OneTimeLinkExchangeAdmissionFilter(rateLimiter, clientIpResolver);
        when(clientIpResolver.resolveWithProvenance(org.mockito.ArgumentMatchers.any()))
            .thenReturn(new ResolvedClientIp("172.20.0.4", false));

        MockHttpServletResponse firstResponse = invoke(filter, "/api/invites/exchange");
        MockHttpServletResponse secondResponse = invoke(filter, "/api/invites/exchange");
        MockHttpServletResponse thirdResponse = invoke(filter, "/api/invites/exchange");

        assertEquals(200, firstResponse.getStatus());
        assertEquals(200, secondResponse.getStatus());
        assertEquals(429, thirdResponse.getStatus());
    }

    private static MockHttpServletResponse invokeWithContextPath(
            OneTimeLinkExchangeAdmissionFilter filter, String contextPath, String uri)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.setContextPath(contextPath);
        request.setServletPath(uri.substring(contextPath.length()));
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    private static MockHttpServletResponse invoke(
            OneTimeLinkExchangeAdmissionFilter filter, String path) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setServletPath(path);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }
}
