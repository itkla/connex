package ooo.klae.connex.backend.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

import ooo.klae.connex.backend.services.SessionSecurityService;

class AbsoluteSessionTimeoutFilterTest {
    private final SessionSecurityService sessionSecurityService = mock(SessionSecurityService.class);
    private final AbsoluteSessionTimeoutFilter filter = new AbsoluteSessionTimeoutFilter(sessionSecurityService);

    @Test
    void expiredSessionOnAnApiRouteIsInvalidatedAndAnswered401() throws Exception {
        MockHttpSession session = new MockHttpSession();
        when(sessionSecurityService.isAbsoluteExpired(session)).thenReturn(true);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/companies");
        request.setSession(session);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(401, response.getStatus());
        assertTrue(session.isInvalid());
        assertNull(chain.getRequest());
    }

    @Test
    void nonApiRoutesAreNotFilteredAtAll() throws Exception {
        MockHttpSession session = new MockHttpSession();
        when(sessionSecurityService.isAbsoluteExpired(session)).thenReturn(true);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/login");
        request.setSession(session);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        assertFalse(session.isInvalid());
        assertNotNull(chain.getRequest());
    }
}
