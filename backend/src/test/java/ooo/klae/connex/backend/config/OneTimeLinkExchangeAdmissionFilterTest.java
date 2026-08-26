package ooo.klae.connex.backend.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
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

    private static MockHttpServletResponse invoke(
            OneTimeLinkExchangeAdmissionFilter filter, String path) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setServletPath(path);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }
}
