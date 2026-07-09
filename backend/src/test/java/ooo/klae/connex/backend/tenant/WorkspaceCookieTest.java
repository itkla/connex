package ooo.klae.connex.backend.tenant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;

class WorkspaceCookieTest {

    @Test
    void defaultCookieIsSecureAndSameSiteLax() {
        WorkspaceCookie cookie = new WorkspaceCookie(new WorkspaceCookieProperties());
        MockHttpServletResponse response = new MockHttpServletResponse();

        cookie.set(response, 42);

        String header = response.getHeader(HttpHeaders.SET_COOKIE);
        assertTrue(header.startsWith("connex_workspace=42;"));
        assertTrue(header.contains("Path=/"));
        assertTrue(header.contains("Max-Age=31536000"));
        assertTrue(header.contains("Secure"));
        assertTrue(header.contains("SameSite=Lax"));
    }

    @Test
    void deploymentCanDisableSecureAndSetSameSiteStrict() {
        WorkspaceCookieProperties properties = new WorkspaceCookieProperties();
        properties.setSecure(false);
        properties.setSameSite("strict");
        WorkspaceCookie cookie = new WorkspaceCookie(properties);
        MockHttpServletResponse response = new MockHttpServletResponse();

        cookie.clear(response);

        String header = response.getHeader(HttpHeaders.SET_COOKIE);
        assertTrue(header.startsWith("connex_workspace=;"));
        assertTrue(header.contains("Max-Age=0"));
        assertFalse(header.contains("Secure"));
        assertTrue(header.contains("SameSite=Strict"));
    }

    @Test
    void sameSiteNoneRequiresSecureCookie() {
        WorkspaceCookieProperties properties = new WorkspaceCookieProperties();
        properties.setSecure(false);
        properties.setSameSite("none");

        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            assertTrue(factory.getValidator().validate(properties).stream()
                .anyMatch(violation -> violation.getMessage().contains("secure must be true")));
        }
    }
}
