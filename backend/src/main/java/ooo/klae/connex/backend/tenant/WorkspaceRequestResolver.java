package ooo.klae.connex.backend.tenant;

import org.springframework.stereotype.Component;
import org.springframework.security.core.Authentication;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.publicapi.ApiCredentialPrincipal;
import ooo.klae.connex.backend.publicapi.PublicApiPaths;

/**
 * Resolves the requested workspace candidate from the header, cookie, or remembered membership.
 */
@Component
@RequiredArgsConstructor
public class WorkspaceRequestResolver {
    private static final String HEADER = "X-Workspace-Id";

    private final WorkspaceService workspaceService;

    public Integer resolve(HttpServletRequest request, int userId) {
        Integer fromHeader = parseId(request.getHeader(HEADER));
        if (fromHeader != null) {
            return fromHeader;
        }
        Integer fromCookie = cookieId(request);
        if (fromCookie != null) {
            return fromCookie;
        }
        return workspaceService.defaultWorkspaceIdFor(userId);
    }

    /**
     * Returns the server-authenticated credential binding for a public API request.
     * Browser workspace headers and cookies are intentionally not consulted.
     */
    public ApiCredentialPrincipal resolvePublicApiCredential(
            HttpServletRequest request, Authentication authentication, int userId) {
        if (!isPublicApiRequest(request)
                || authentication == null
                || !(authentication.getDetails() instanceof ApiCredentialPrincipal credential)
                || credential.userId() != userId) {
            return null;
        }
        return credential;
    }

    /** Returns whether the context-path-adjusted request targets the versioned public API. */
    public boolean isPublicApiRequest(HttpServletRequest request) {
        return PublicApiPaths.isPublicRequest(request);
    }

    /**
     * Whether a failed membership on {@code candidate} is the stale browser/SSR pin
     * that should heal to the caller's next workspace (#1108), rather than an explicit
     * foreign {@code X-Workspace-Id} that must stay 403.
     *
     * <p>The SPA mirrors {@code connex_workspace} into the header, so a matching stale
     * pair (or cookie-only SSR) is the revocation case. A header that is absent from
     * the cookie, or that disagrees with it, is treated as an intentional pin.
     */
    public boolean isStaleWorkspacePin(HttpServletRequest request, int candidate) {
        Integer fromCookie = cookieId(request);
        if (fromCookie == null || fromCookie != candidate) {
            return false;
        }
        Integer fromHeader = parseId(request.getHeader(HEADER));
        return fromHeader == null || fromHeader.equals(fromCookie);
    }

    private static Integer cookieId(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return null;
        }
        for (Cookie cookie : request.getCookies()) {
            if (WorkspaceCookie.NAME.equals(cookie.getName())) {
                Integer fromCookie = parseId(cookie.getValue());
                if (fromCookie != null) {
                    return fromCookie;
                }
            }
        }
        return null;
    }

    private static Integer parseId(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
