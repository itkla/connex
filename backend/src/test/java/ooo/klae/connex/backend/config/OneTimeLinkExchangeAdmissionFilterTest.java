package ooo.klae.connex.backend.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import ooo.klae.connex.backend.services.LoginRateLimiter;
import ooo.klae.connex.backend.util.ClientIpResolver;

class OneTimeLinkExchangeAdmissionFilterTest {

    @Test
    void rejectsAnExchangeAfterTheExistingIpBudgetIsExhausted() throws Exception {
        LoginRateLimiter rateLimiter = new LoginRateLimiter(1, 100, 900);
        ClientIpResolver clientIpResolver = mock(ClientIpResolver.class);
        OneTimeLinkExchangeAdmissionFilter filter =
            new OneTimeLinkExchangeAdmissionFilter(rateLimiter, clientIpResolver);
        when(clientIpResolver.resolve(org.mockito.ArgumentMatchers.any()))
            .thenReturn("203.0.113.9");

        MockHttpServletResponse firstResponse = invoke(filter, "/api/invites/exchange");
        MockHttpServletResponse secondResponse = invoke(filter, "/api/invites/exchange");

        assertEquals(200, firstResponse.getStatus());
        assertEquals(429, secondResponse.getStatus());
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
