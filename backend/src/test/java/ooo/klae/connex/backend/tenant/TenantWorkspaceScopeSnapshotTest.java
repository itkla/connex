package ooo.klae.connex.backend.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import ooo.klae.connex.backend.services.OrganizationWorkspaceScopeControlOperations.WorkspaceScope;

/** Covers exact binding, complete nested restoration and absence of thread inheritance. */
class TenantWorkspaceScopeSnapshotTest {
    private final TenantContext context = new TenantContext();

    @AfterEach
    void clear() {
        context.clear();
    }

    @Test
    void sameIdentityBindingReusesPreparedDataAndDifferentIdentitiesDiscardIt() {
        var scopes = scopes(7, 900, "cnx_tenant");
        context.setWithWorkspaceScopes(7, 900, 42, "member", "cnx_tenant", scopes);
        context.set(7, 900, 43, "owner", "cnx_tenant");
        assertSame(scopes, context.getWorkspaceScopes());
        context.set(11, 900, 43, "owner", "cnx_tenant");
        assertNull(context.getWorkspaceScopes());
    }

    @Test
    void wrongOrganizationOrCatalogNeverKeepsUsableData() {
        var scopes = scopes(7, 900, "cnx_tenant");
        context.setWithWorkspaceScopes(7, 900, 42, "member", "cnx_tenant", scopes);
        context.set(7, 901, 42, "member", "cnx_tenant");
        assertNull(context.getWorkspaceScopes());
        context.setWithWorkspaceScopes(7, 900, 42, "member", "cnx_tenant", scopes);
        context.set(7, 900, 42, "member", "cnx_other");
        assertNull(context.getWorkspaceScopes());
    }

    @Test
    void invalidPreparedBindingRefusesBeforeChangingTheCurrentIdentity() {
        context.set(3, 5, 9, "owner", null);
        assertThrows(IllegalArgumentException.class,
            () -> context.setWithWorkspaceScopes(7, 901, 42, "member", "cnx_tenant", scopes(7, 900, "cnx_tenant")));
        assertEquals(3, context.getWorkspaceId());
    }

    @Test
    void completeStateRestoresIdentityOverrideAndScopesAfterClear() {
        var scopes = scopes(7, 900, "cnx_tenant");
        context.setWithWorkspaceScopes(7, 900, 42, "owner", "cnx_tenant", scopes);
        context.swapCatalogOverride(Optional.of("cnx_tenant"));
        var previous = context.snapshot();
        context.clear();
        context.set(11, 901, 43, "member", "cnx_other");
        context.restore(previous);
        assertEquals(7, context.getWorkspaceId());
        assertEquals(42, context.getUserId());
        assertTrue(context.hasCatalogOverride());
        assertSame(scopes, context.getWorkspaceScopes());
    }

    @Test
    void controlSelectionHasNoSnapshotAndSurvivesNestedIdentityRestoration() {
        context.setWithWorkspaceScopes(7, 900, 42, "member", "cnx_tenant", scopes(7, 900, "cnx_tenant"));
        context.setControlSelection(7, 900, 42, "member");
        var previous = context.snapshot();
        assertFalse(context.isResolved());
        assertNull(context.getWorkspaceScopes());
        context.set(11, 901, 43, "system", "cnx_other");
        context.restore(previous);
        assertTrue(context.hasControlSelection());
        assertFalse(context.isResolved());
        assertEquals(7, context.getWorkspaceId());
        assertNull(context.getCatalog());
        assertNull(context.getWorkspaceScopes());
    }

    @Test
    void catalogOnlyOverrideMasksAnUnrelatedPreparedRoute() {
        context.setWithWorkspaceScopes(7, 900, 42, "member", "cnx_tenant", scopes(7, 900, "cnx_tenant"));
        context.swapCatalogOverride(Optional.empty());
        assertNull(context.getWorkspaceScopes());
        context.clear();
        assertNull(context.getWorkspaceScopes());
        assertNull(context.getCatalog());
    }

    @Test
    void coldAndReusedWorkersNeverInheritTheCallersPayload() throws Exception {
        context.setWithWorkspaceScopes(7, 900, 42, "member", "cnx_tenant", scopes(7, 900, "cnx_tenant"));
        try (var executor = Executors.newSingleThreadExecutor()) {
            executor.submit(() -> {
                assertFalse(context.isResolved());
                assertNull(context.getWorkspaceScopes());
                var previous = context.snapshot();
                try {
                    context.setWithWorkspaceScopes(11, 901, 43, "system", "cnx_other", scopes(11, 901, "cnx_other"));
                } finally {
                    context.restore(previous);
                }
            }).get(5, TimeUnit.SECONDS);
            executor.submit(() -> {
                assertFalse(context.isResolved());
                assertNull(context.getWorkspaceScopes());
                assertNull(context.getCatalog());
            }).get(5, TimeUnit.SECONDS);
        }
        assertEquals(7, context.getWorkspaceId());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = "cnx_tenant")
    void exactIdentityMatchingIncludesSharedAndDedicatedCatalogs(String catalog) {
        var prepared = scopes(7, 900, catalog);
        assertTrue(prepared.matches(7, 900, catalog));
        assertFalse(prepared.matches(11, 900, catalog));
        assertFalse(prepared.matches(7, 901, catalog));
        assertFalse(prepared.matches(7, 900, "different_catalog"));
        context.setWithWorkspaceScopes(7, 900, 42, "member", catalog, prepared);
        assertSame(prepared, context.getWorkspaceScopes());
    }

    private static TenantWorkspaceScopeSnapshot scopes(int workspaceId, int orgId, String catalog) {
        return new TenantWorkspaceScopeSnapshot(workspaceId, orgId, catalog, Optional.empty(),
            new WorkspaceScope(orgId, List.of(workspaceId), "[" + workspaceId + "]"));
    }
}
