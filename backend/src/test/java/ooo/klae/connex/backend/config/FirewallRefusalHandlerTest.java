package ooo.klae.connex.backend.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.firewall.RequestRejectedException;
import org.springframework.security.web.firewall.RequestRejectedHandler;

import tools.jackson.databind.ObjectMapper;

/**
 * Pins the shape of a browser-plane firewall refusal and the handler the firewall boundary installs.
 *
 * <p>Both refusal forms of {@code sendError} commit the response while only the two-argument form
 * records an error message, so the uncommitted response is the guard that catches either and with it
 * the container ERROR dispatch that would answer an anonymous caller 401 (#1780). The status a live
 * caller actually receives is pinned over real HTTP by
 * {@code FirewallRefusalStatusIntegrationTest}.
 */
class FirewallRefusalHandlerTest {

    private static final String REFUSAL_BODY =
        "{\"code\":\"BAD_REQUEST\",\"message\":\"Request was rejected\"}";

    @Test
    void refusalIsWrittenWithSetStatusRatherThanAnErrorDispatch() throws Exception {
        MockHttpServletResponse response =
            refuse(new FirewallRefusalHandler(), "/api/auth/csrf;x=1");

        assertEquals(400, response.getStatus());
        assertFalse(response.isCommitted());
        assertNull(response.getErrorMessage());
        assertTrue(response.getContentType().startsWith(MediaType.APPLICATION_JSON_VALUE));
        assertEquals(REFUSAL_BODY, response.getContentAsString());
    }

    @Test
    void refusalEchoesNeitherTheRequestNorTheRejectionReason() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/marker-path");
        request.setQueryString("probe=marker-query");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new FirewallRefusalHandler().handle(request, response,
            new RequestRejectedException("The request was rejected because of marker-reason"));

        assertEquals(REFUSAL_BODY, response.getContentAsString());
        assertFalse(response.getContentAsString().contains("marker"));
    }

    /** The firewall refuses ahead of the chain's header writers, so the handler applies them. */
    @Test
    void refusalCarriesThePreSecurityHeaderContract() throws Exception {
        MockHttpServletResponse response =
            refuse(new FirewallRefusalHandler(), "/api/auth/csrf;x=1");

        assertEquals("nosniff", response.getHeader("X-Content-Type-Options"));
        assertEquals("no-store", response.getHeader("Cache-Control"));
        assertTrue(response.getHeader("Content-Security-Policy").contains("default-src 'none'"));
    }

    @Test
    void theFirewallBoundaryRoutesEachPlaneToItsOwnRefusal() throws Exception {
        RequestRejectedHandler handler = new PublicApiFirewallConfig()
            .publicApiRequestRejectedHandler(new ObjectMapper(), false);

        MockHttpServletResponse browserPlane = refuse(handler, "/api/auth/csrf;x=1");
        MockHttpServletResponse publicPlane = refuse(handler, "/api/v1/me;x=1");

        assertEquals(400, browserPlane.getStatus());
        assertFalse(browserPlane.isCommitted());
        assertEquals(REFUSAL_BODY, browserPlane.getContentAsString());
        assertEquals(503, publicPlane.getStatus());
        assertTrue(publicPlane.getContentAsString().contains("public_api_unavailable"));
    }

    private static MockHttpServletResponse refuse(RequestRejectedHandler handler, String uri)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        MockHttpServletResponse response = new MockHttpServletResponse();
        handler.handle(request, response, new RequestRejectedException("Rejected"));
        return response;
    }
}
