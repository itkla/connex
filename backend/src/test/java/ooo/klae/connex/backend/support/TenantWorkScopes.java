package ooo.klae.connex.backend.support;

import static org.mockito.Answers.RETURNS_DEFAULTS;
import static org.mockito.Mockito.mock;

import java.util.function.Supplier;

import ooo.klae.connex.backend.tenant.TenantWorkScope;
import ooo.klae.connex.backend.tenant.WorkspaceRead;

/** Callback execution for unit fixtures whose tenant routing is outside the test boundary. */
public final class TenantWorkScopes {
    private TenantWorkScopes() { }

    /** Keeps read callbacks on their own functional interface in compiled unit fixtures. */
    public static TenantWorkScope passThrough() {
        return mock(TenantWorkScope.class, invocation -> {
            if ("inWorkspaceRead".equals(invocation.getMethod().getName())) {
                return ((WorkspaceRead<?>) invocation.getArgument(1)).read();
            }
            if ("inWorkspace".equals(invocation.getMethod().getName())) {
                return ((Supplier<?>) invocation.getArgument(1)).get();
            }
            return RETURNS_DEFAULTS.answer(invocation);
        });
    }
}
