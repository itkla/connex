package ooo.klae.connex.backend.tenant;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.services.WorkspaceService;

/**
 * Resolves the active workspace once per authenticated request and stores it in
 * {@link TenantContext}. Precedence: {@code X-Workspace-Id} header, then the
 * {@code connex_workspace} cookie, then the user's remembered/first membership.
 * The candidate is always re-validated against membership (403 if not a member),
 * so a forged header or cookie cannot grant access.
 */
@Component
@RequiredArgsConstructor
public class TenantResolutionInterceptor implements HandlerInterceptor {

    static final String HEADER = "X-Workspace-Id";
    static final String COOKIE = "connex_workspace";

    private final WorkspaceService workspaceService;
    private final TenantContext tenantContext;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof User user)) {
            return true; // unauthenticated; permitAll endpoints, scoped ones fail closed downstream
        }

        Integer candidate = candidateFromRequest(request);
        if (candidate == null) {
            candidate = workspaceService.defaultWorkspaceIdFor(user.getId()); // remembered or first membership
        }
        if (candidate == null) {
            return true; // user belongs to no workspace yet (onboarding); leave unresolved
        }

        String role = workspaceService.getRole(candidate, user.getId());
        if (role == null) {
            throw new ForbiddenException("Not a member of workspace " + candidate);
        }
        tenantContext.set(candidate, user.getId(), role);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        tenantContext.clear();
    }

    private Integer candidateFromRequest(HttpServletRequest request) {
        String header = request.getHeader(HEADER);
        Integer fromHeader = parseId(header);
        if (fromHeader != null) {
            return fromHeader;
        }
        if (request.getCookies() != null) {
            for (Cookie cookie : request.getCookies()) {
                if (COOKIE.equals(cookie.getName())) {
                    return parseId(cookie.getValue());
                }
            }
        }
        return null;
    }

    private Integer parseId(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
