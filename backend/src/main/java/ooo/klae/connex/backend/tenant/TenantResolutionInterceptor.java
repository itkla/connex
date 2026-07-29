package ooo.klae.connex.backend.tenant;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.util.regex.Pattern;

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
    private static final Pattern WORKSPACE_LIFECYCLE_PATH = Pattern.compile(
        "/api/orgs/\\d+/workspaces/\\d+");
    private static final Pattern ORGANIZATION_LIFECYCLE_PATH = Pattern.compile(
        "/api/orgs/\\d+");

    private final WorkspaceService workspaceService;
    private final TenantContext tenantContext;
    private final TenantCatalogResolver tenantCatalogResolver;
    private final WorkspaceRequestResolver workspaceRequestResolver;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (isLifecycleRequest(request)) {
            return true;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof User user)) {
            return true; // unauthenticated; permitAll endpoints, scoped ones fail closed downstream
        }

        Integer candidate = workspaceRequestResolver.resolve(request, user.getId());
        if (candidate == null) {
            return true; // user belongs to no workspace yet (onboarding); leave unresolved
        }

        String role = workspaceService.getRole(candidate, user.getId());
        if (role == null) {
            throw new ForbiddenException("Not a member of workspace " + candidate);
        }
        int orgId = workspaceService.getOrgId(candidate);
        String catalog = tenantCatalogResolver.resolveCatalog(orgId);
        tenantContext.set(candidate, orgId, user.getId(), role, catalog);
        return true;
    }

    private boolean isLifecycleRequest(HttpServletRequest request) {
        String method = request.getMethod();
        String path = request.getRequestURI();
        return ("GET".equals(method)
                && path.endsWith("/export")
                && WORKSPACE_LIFECYCLE_PATH.matcher(
                    path.substring(0, path.length() - "/export".length())).matches())
            || ("DELETE".equals(method)
                && (WORKSPACE_LIFECYCLE_PATH.matcher(path).matches()
                    || ORGANIZATION_LIFECYCLE_PATH.matcher(path).matches()));
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        tenantContext.clear();
    }

}
