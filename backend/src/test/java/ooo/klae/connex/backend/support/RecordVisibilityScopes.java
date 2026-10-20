package ooo.klae.connex.backend.support;

import static org.mockito.Answers.RETURNS_DEFAULTS;
import static org.mockito.Mockito.mock;

import java.util.List;

import ooo.klae.connex.backend.services.RecordVisibilityScopeControlAccess;
import ooo.klae.connex.backend.services.RecordVisibilityScopeControlAccess.RecordVisibilityScope;

/** Stable unfiltered scope for unit fixtures whose mapper calls bind the same trusted JSON. */
public final class RecordVisibilityScopes {
    private RecordVisibilityScopes() { }

    /** Supplies a fresh boundary mock without registering unused stubs for unrelated test paths. */
    public static RecordVisibilityScopeControlAccess unfiltered() {
        return mock(RecordVisibilityScopeControlAccess.class, invocation -> {
            if ("withWorkspaceScope".equals(invocation.getMethod().getName())) {
                return ((java.util.function.Supplier<?>) invocation.getArgument(invocation.getArguments().length - 1)).get();
            }
            if ("getForWorkspace".equals(invocation.getMethod().getName())) {
                return new RecordVisibilityScope(90, List.of(7, 8, 9));
            }
            return RETURNS_DEFAULTS.answer(invocation);
        });
    }
}
