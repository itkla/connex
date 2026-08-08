package ooo.klae.connex.backend.tenant;

import java.time.Duration;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/** Writes the path-scoped HttpOnly credential used for one tenant export download. */
@Component
public class TenantExportGrantCookie {
    public static final String NAME = "connex_tenant_export_grant";

    private final WorkspaceCookieProperties properties;

    public TenantExportGrantCookie(WorkspaceCookieProperties properties) {
        this.properties = properties;
    }

    /** Sets a grant cookie for exactly one organization/workspace export path. */
    public void set(
            HttpServletResponse response,
            int orgId,
            int workspaceId,
            String value,
            Duration maxAge) {
        response.addHeader(
            HttpHeaders.SET_COOKIE,
            builder(orgId, workspaceId, value).maxAge(maxAge).build().toString());
    }

    /** Expires the grant cookie on its exact download path. */
    public void clear(HttpServletResponse response, int orgId, int workspaceId) {
        response.addHeader(
            HttpHeaders.SET_COOKIE,
            builder(orgId, workspaceId, "").maxAge(Duration.ZERO).build().toString());
    }

    private ResponseCookie.ResponseCookieBuilder builder(
            int orgId,
            int workspaceId,
            String value) {
        ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from(NAME, value)
            .path(downloadPath(orgId, workspaceId))
            .httpOnly(true)
            .sameSite("Strict");
        if (properties.isEffectiveSecure()) {
            builder.secure(true);
        }
        return builder;
    }

    private static String downloadPath(int orgId, int workspaceId) {
        return "/api/orgs/" + orgId + "/workspaces/" + workspaceId + "/export";
    }
}
