package ooo.klae.connex.backend.tenant;

import org.springframework.stereotype.Component;

/**
 * Holds the active workspace resolved for the current thread (request). Populated
 * once per request by {@link TenantResolutionInterceptor} and read by
 * {@code WorkspaceService.getCurrentWorkspaceId()}. Off the request thread (tests,
 * scheduled jobs) it stays unresolved and callers fall back to membership lookup.
 * The optional catalog pins the org's placement-routed database for the whole
 * request span; {@code null} means the default (shared) catalog.
 */
@Component
public class TenantContext {

    private record Scope(int workspaceId, int orgId, int userId, String role, String catalog) {}

    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();

    /**
     * Installs the scope for the current thread. The catalog must come from
     * {@link TenantCatalogResolver} (or be an explicit {@code null} for the
     * default/shared catalog) — there is deliberately no catalog-less overload,
     * so every installer decides routing explicitly.
     */
    public void set(int workspaceId, int orgId, int userId, String role, String catalog) {
        CURRENT.set(new Scope(workspaceId, orgId, userId, role, catalog));
    }

    public boolean isResolved() {
        return CURRENT.get() != null;
    }

    public Integer getWorkspaceId() {
        Scope s = CURRENT.get();
        return s == null ? null : s.workspaceId();
    }

    public Integer getOrgId() {
        Scope s = CURRENT.get();
        return s == null ? null : s.orgId();
    }

    public Integer getUserId() {
        Scope s = CURRENT.get();
        return s == null ? null : s.userId();
    }

    public String getRole() {
        Scope s = CURRENT.get();
        return s == null ? null : s.role();
    }

    public String getCatalog() {
        Scope s = CURRENT.get();
        return s == null ? null : s.catalog();
    }

    public void clear() {
        CURRENT.remove();
    }
}
