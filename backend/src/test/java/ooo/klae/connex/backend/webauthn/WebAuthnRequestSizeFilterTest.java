package ooo.klae.connex.backend.webauthn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Verifies the passkey ceremony body-size guard rejects oversized payloads before they are buffered,
 * while leaving normal payloads and non-ceremony paths untouched.
 */
class WebAuthnRequestSizeFilterTest {

    private final WebAuthnRequestSizeFilter filter = new WebAuthnRequestSizeFilter();

    @Test
    void rejectsOversizedCeremonyBody() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/webauthn/authenticate");
        request.setContent(new byte[65 * 1024]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(413, response.getStatus());
        assertNull(chain.getRequest(), "chain must not run for an oversized body");
    }

    @Test
    void allowsNormalCeremonyBody() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/webauthn/register");
        request.setContent(new byte[512]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        assertNotNull(chain.getRequest(), "chain must run for a normal body");
    }

    @Test
    void ignoresNonCeremonyPaths() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
        request.setContent(new byte[65 * 1024]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertNotNull(chain.getRequest(), "non-ceremony paths are not size-capped here");
    }
}
