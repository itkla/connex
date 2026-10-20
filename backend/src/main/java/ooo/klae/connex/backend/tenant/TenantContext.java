package ooo.klae.connex.backend.tenant;

import java.util.Optional;

import org.springframework.stereotype.Component;

/**
 * Holds the active workspace resolved for the current thread (request). Populated
 * once per request by {@link TenantResolutionInterceptor} and read by
 * {@code WorkspaceService.getCurrentWorkspaceId()}. Off the request thread (tests,
 * scheduled jobs) it stays unresolved and callers fall back to membership lookup.
 * The optional catalog pins the org's placement-routed database for the whole
 * request span; {@code null} means the default (shared) catalog for a resolved scope.
 * A validated control selection retains identity and audit attribution without resolving a tenant
 * catalog. It never satisfies {@link #isResolved()} and cannot authorize tenant SQL.
 */
@Component
public class TenantContext {

    private record Scope(int workspaceId, int orgId, int userId, String role, String catalog) {}
    private record ValidatedControlSelection(int workspaceId, int orgId, int userId, String role) {}

    /** Opaque identity snapshot; catalog overrides belong to their enclosing TenantWorkScope. */
    public static final class IdentitySnapshot {
        private final Scope scope;
        private final ValidatedControlSelection selection;

        private IdentitySnapshot(Scope scope, ValidatedControlSelection selection) {
            this.scope = scope;
            this.selection = selection;
        }
    }

    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<ValidatedControlSelection> CONTROL_SELECTION = new ThreadLocal<>();
    private static final ThreadLocal<Optional<String>> CATALOG_OVERRIDE = new ThreadLocal<>();

    /** Captures ordinary identity or the validated control selection before an explicit runAs span. */
    public IdentitySnapshot snapshotIdentity() {
        return new IdentitySnapshot(CURRENT.get(), CONTROL_SELECTION.get());
    }

    /** Restores identity without replacing a still-active placement override or SQL restriction. */
    public void restoreIdentity(IdentitySnapshot snapshot) {
        if (snapshot.scope == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(snapshot.scope);
        }
        if (snapshot.selection == null) {
            CONTROL_SELECTION.remove();
        } else {
            CONTROL_SELECTION.set(snapshot.selection);
        }
    }

    /**
     * Installs the scope for the current thread. The catalog must come from
     * {@link TenantCatalogResolver} (or be an explicit {@code null} for the
     * default/shared catalog) — there is deliberately no catalog-less overload,
     * so every installer decides routing explicitly.
     */
    public void set(int workspaceId, int orgId, int userId, String role, String catalog) {
        CONTROL_SELECTION.remove();
        CURRENT.set(new Scope(workspaceId, orgId, userId, role, catalog));
    }

    /** Installs membership-validated identity for a handler restricted to control-plane SQL. */
    void setControlSelection(int workspaceId, int orgId, int userId, String role) {
        clear();
        CONTROL_SELECTION.set(new ValidatedControlSelection(workspaceId, orgId, userId, role));
    }

    /** Whether control-only handling has a validated workspace selection, without a tenant route. */
    public boolean hasControlSelection() {
        return CONTROL_SELECTION.get() != null;
    }

    public boolean isResolved() {
        return CURRENT.get() != null;
    }

    public Integer getWorkspaceId() {
        Scope s = CURRENT.get();
        if (s != null) {
            return s.workspaceId();
        }
        ValidatedControlSelection selection = CONTROL_SELECTION.get();
        return selection == null ? null : selection.workspaceId();
    }

    public Integer getOrgId() {
        Scope s = CURRENT.get();
        if (s != null) {
            return s.orgId();
        }
        ValidatedControlSelection selection = CONTROL_SELECTION.get();
        return selection == null ? null : selection.orgId();
    }

    public Integer getUserId() {
        Scope s = CURRENT.get();
        if (s != null) {
            return s.userId();
        }
        ValidatedControlSelection selection = CONTROL_SELECTION.get();
        return selection == null ? null : selection.userId();
    }

    public String getRole() {
        Scope s = CURRENT.get();
        if (s != null) {
            return s.role();
        }
        ValidatedControlSelection selection = CONTROL_SELECTION.get();
        return selection == null ? null : selection.role();
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
        CONTROL_SELECTION.remove();
        CATALOG_OVERRIDE.remove();
    }
}
