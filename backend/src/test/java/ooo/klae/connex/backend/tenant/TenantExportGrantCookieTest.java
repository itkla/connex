package ooo.klae.connex.backend.tenant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

class TenantExportGrantCookieTest {

    @Test
    void grantCookieIsHttpOnlyStrictSecureAndExactPathScoped() {
        WorkspaceCookieProperties properties = new WorkspaceCookieProperties();
        properties.setSecure(true);
        TenantExportGrantCookie cookie = new TenantExportGrantCookie(properties);
        MockHttpServletResponse response = new MockHttpServletResponse();

        cookie.set(response, 3, 5, "a".repeat(64), Duration.ofMinutes(2));

        String header = response.getHeader("Set-Cookie");
        assertNotNull(header);
        assertTrue(header.contains("connex_tenant_export_grant=" + "a".repeat(64)));
        assertTrue(header.contains("Path=/api/orgs/3/workspaces/5/export"));
        assertTrue(header.contains("Max-Age=120"));
        assertTrue(header.contains("Secure"));
        assertTrue(header.contains("HttpOnly"));
        assertTrue(header.contains("SameSite=Strict"));
    }

    @Test
    void localCookieCanUseHttpWhileRemainingHttpOnlyAndClearable() {
        WorkspaceCookieProperties properties = new WorkspaceCookieProperties();
        properties.setSecure(false);
        TenantExportGrantCookie cookie = new TenantExportGrantCookie(properties);
        MockHttpServletResponse response = new MockHttpServletResponse();

        cookie.clear(response, 7, 9);

        String header = response.getHeader("Set-Cookie");
        assertNotNull(header);
        assertTrue(header.contains("Path=/api/orgs/7/workspaces/9/export"));
        assertTrue(header.contains("Max-Age=0"));
        assertTrue(header.contains("HttpOnly"));
        assertTrue(header.contains("SameSite=Strict"));
        assertFalse(header.contains("; Secure"));
    }
}
