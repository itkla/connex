package ooo.klae.connex.backend.tenant;

import java.util.Optional;

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
    private static final ThreadLocal<Optional<String>> CATALOG_OVERRIDE = new ThreadLocal<>();

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

    /**
     * The identity scope's own catalog, ignoring any {@code TenantWorkScope}
     * override — the value save/restore code must snapshot, since restoring an
     * override value into the scope would corrupt it once the override pops.
     */
    public String getScopeCatalog() {
        Scope s = CURRENT.get();
        return s == null ? null : s.catalog();
    }

    /** Whether a {@code TenantWorkScope} catalog override is active on the thread. */
    boolean hasCatalogOverride() {
        return CATALOG_OVERRIDE.get() != null;
    }

    public String getCatalog() {
        Optional<String> override = CATALOG_OVERRIDE.get();
        if (override != null) {
            return override.orElse(null);
        }
        Scope s = CURRENT.get();
        return s == null ? null : s.catalog();
    }

    /**
     * Swaps the thread's catalog override and returns the previous one so the
     * caller can restore it in a {@code finally}. An override takes precedence
     * over the identity scope's catalog: {@code Optional.empty()} forces the
     * default (unrouted) catalog — how control-plane reads such as placement
     * resolution stay off tenant catalogs — while a present value pins a
     * routed catalog for background work that has no principal to install.
     * {@code null} clears the override. Only {@link TenantWorkScope} should
     * call this; everything else uses its structured runners.
     *
     * @param override the new override, or {@code null} for none
     * @return the previous override, or {@code null} when there was none
     */
    Optional<String> swapCatalogOverride(Optional<String> override) {
        Optional<String> previous = CATALOG_OVERRIDE.get();
        if (override == null) {
            CATALOG_OVERRIDE.remove();
        } else {
            CATALOG_OVERRIDE.set(override);
        }
        return previous;
    }

    /**
     * Releases everything this thread holds — the identity scope and any
     * {@code TenantWorkScope} catalog override. Both matter: the override wins
     * over the scope's own catalog in {@link #getCatalog()}, which is what
     * {@link TenantRoutingDataSource} reads to pick the physical database, so
     * dropping only the identity would reset who the thread is but not where
     * its SQL goes (#995). Structured overrides restore their own previous
     * value absolutely when their span ends, so a clear inside a span is
     * repaired on exit; anything issued after a clear and outside a span fails
     * closed in {@link TenantScopeInterceptor} rather than silently routing to
     * the default catalog.
     */
    public void clear() {
        CURRENT.remove();
        CATALOG_OVERRIDE.remove();
    }
}
