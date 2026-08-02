package ooo.klae.connex.backend.tenant;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.util.regex.Pattern;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.AsyncHandlerInterceptor;

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
 *
 * <p>{@link TenantContext} is a {@code ThreadLocal} on a pooled container thread,
 * so the scope's teardown is load-bearing for tenant isolation (#988). Two rules
 * keep a scope from outliving the request that installed it:
 *
 * <ul>
 *   <li>{@link #preHandle} clears before every early return, so a request that
 *       resolves no workspace — a lifecycle path, an unauthenticated caller, or a
 *       user with no active membership — can never inherit whatever the previous
 *       request on this thread left behind.</li>
 *   <li>This is an {@link AsyncHandlerInterceptor} because Spring dispatches
 *       {@link #afterConcurrentHandlingStarted} <em>instead of</em>
 *       {@link #afterCompletion} once a handler starts async processing, and only
 *       to interceptors of that type. A plain {@code HandlerInterceptor} therefore
 *       gets no teardown callback at all on the streaming endpoints and hands the
 *       thread back to the pool with the scope still installed.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class TenantResolutionInterceptor implements AsyncHandlerInterceptor {
    private static final Pattern WORKSPACE_LIFECYCLE_PATH = Pattern.compile(
        "/api/orgs/\\d+/workspaces/\\d+");
    private static final Pattern ORGANIZATION_LIFECYCLE_PATH = Pattern.compile(
        "/api/orgs/\\d+");

    private final WorkspaceService workspaceService;
    private final TenantContext tenantContext;
    private final TenantCatalogResolver tenantCatalogResolver;
    private final WorkspaceRequestResolver workspaceRequestResolver;

    /**
     * Discards any scope left on this pooled thread, then resolves the request's own.
     * The clear runs first so that every early return below leaves the thread
     * unresolved rather than inheriting the previous request's tenant.
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        tenantContext.clear();
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

    /**
     * Releases the scope when the handler hands the response off to async processing
     * and the container thread returns to the pool, which is the one exit
     * {@link #afterCompletion} never observes.
     *
     * <p>Safe for the {@code StreamingResponseBody} endpoints: their tenant-scoped
     * work all completes on this thread before the body is returned, and the body
     * itself runs on a separate executor that never inherits this
     * {@code ThreadLocal}. Bodies that do need a scope install their own — the
     * tenant export re-pins its route through {@code TenantLifecycleAccess}.
     */
    @Override
    public void afterConcurrentHandlingStarted(
            HttpServletRequest request, HttpServletResponse response, Object handler) {
        tenantContext.clear();
    }

}
