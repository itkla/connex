package ooo.klae.connex.backend.tenant;

import org.springframework.stereotype.Component;

/**
 * Holds the active workspace resolved for the current thread (request). Populated
 * once per request by {@link TenantResolutionInterceptor} and read by
 * {@code WorkspaceService.getCurrentWorkspaceId()}. Off the request thread (tests,
 * scheduled jobs) it stays unresolved and callers fall back to membership lookup.
 */
@Component
public class TenantContext {

    private record Scope(int workspaceId, int userId, String role) {}

    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();

    public void set(int workspaceId, int userId, String role) {
        CURRENT.set(new Scope(workspaceId, userId, role));
    }

    public boolean isResolved() {
        return CURRENT.get() != null;
    }

    public Integer getWorkspaceId() {
        Scope s = CURRENT.get();
        return s == null ? null : s.workspaceId();
    }

    public Integer getUserId() {
        Scope s = CURRENT.get();
        return s == null ? null : s.userId();
    }

    public String getRole() {
        Scope s = CURRENT.get();
        return s == null ? null : s.role();
    }

    public void clear() {
        CURRENT.remove();
    }
}
