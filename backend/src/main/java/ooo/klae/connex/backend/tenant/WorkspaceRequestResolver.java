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
 * Resolves the requested workspace candidate from the header, cookie, or raw remembered
 * workspace id. With no remembered selection, uses the caller's first active membership.
 * Membership validation and method-specific stale-selection recovery belong to the interceptor.
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
        Integer remembered = workspaceService.rememberedWorkspaceIdFor(userId);
        return remembered != null
            ? remembered
            : workspaceService.firstMembershipWorkspaceIdFor(userId);
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
     * Whether a failed membership on a {@code candidate} returned by {@link #resolve}
     * is a stale selection eligible for recovery on safe methods (#1108, #1649).
     *
     * <p>The SPA mirrors {@code connex_workspace} into the header, so a matching stale
     * pair (or cookie-only SSR) is the revocation case. Without a header or cookie,
     * the remembered candidate is an implicit selection eligible for the same recovery.
     * A header that is absent from the cookie, or that disagrees with it, is treated
     * as an intentional pin and must stay 403.
     */
    public boolean isStaleWorkspacePin(HttpServletRequest request, int candidate) {
        Integer fromCookie = cookieId(request);
        Integer fromHeader = parseId(request.getHeader(HEADER));
        if (fromCookie == null) {
            return fromHeader == null;
        }
        if (fromCookie != candidate) {
            return false;
        }
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
