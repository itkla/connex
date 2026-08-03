package ooo.klae.connex.backend.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.exceptions.ServiceUnavailableException;
import ooo.klae.connex.backend.mappers.TenantLifecycleControlMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;

@ExtendWith(MockitoExtension.class)
class TenantWorkScopeTest {

    @Mock private TenantCatalogResolver tenantCatalogResolver;
    @Mock private WorkspaceMapper workspaceMapper;
    @Mock private TenantLifecycleControlMapper tenantLifecycleControlMapper;

    private final TenantContext tenantContext = new TenantContext();
    private TenantWorkScope workScope;

    @BeforeEach
    void setUp() {
        workScope = new TenantWorkScope(
            tenantContext,
            tenantCatalogResolver,
            workspaceMapper,
            tenantLifecycleControlMapper);
    }

    @AfterEach
    void clearThread() {
        tenantContext.clear();
        tenantContext.swapCatalogOverride(null);
    }

    @Test
    void inWorkspacePinsTheResolvedCatalogAndRestoresAfter() {
        when(workspaceMapper.getOrgId(7)).thenReturn(42);
        when(tenantCatalogResolver.resolveCatalog(42)).thenReturn("cnx_abc");

        String during = workScope.inWorkspace(7, () -> tenantContext.getCatalog());

        assertEquals("cnx_abc", during);
        assertNull(tenantContext.getCatalog());
    }

    @Test
    void clearReleasesTheCatalogOverrideAsWellAsTheIdentityScope() {
        tenantContext.set(7, 42, 3, "owner", "cnx_scope");
        tenantContext.swapCatalogOverride(Optional.of("cnx_override"));

        tenantContext.clear();

        assertFalse(tenantContext.isResolved());
        assertFalse(tenantContext.hasCatalogOverride());
        assertNull(tenantContext.getCatalog());
    }

    @Test
    void clearInsideANestedSpanCannotCorruptTheEnclosingOverride() {
        when(workspaceMapper.getOrgId(7)).thenReturn(42);
        when(tenantCatalogResolver.resolveCatalog(42)).thenReturn("cnx_inner");
        AtomicReference<String> duringClear = new AtomicReference<>("unset");
        AtomicReference<String> afterInnerSpan = new AtomicReference<>("unset");

        workScope.withCatalog("cnx_outer", () -> {
            workScope.inWorkspace(7, () -> {
                tenantContext.clear();
                duringClear.set(tenantContext.getCatalog());
                return null;
            });
            afterInnerSpan.set(tenantContext.getCatalog());
            return null;
        });

        assertNull(duringClear.get());
        assertEquals("cnx_outer", afterInnerSpan.get());
        assertNull(tenantContext.getCatalog());
    }

    @Test
    void sharedPlacementPinsTheDefaultCatalog() {
        when(workspaceMapper.getOrgId(7)).thenReturn(42);
        when(tenantCatalogResolver.resolveCatalog(42)).thenReturn(null);

        assertNull(workScope.inWorkspace(7, () -> tenantContext.getCatalog()));
    }

    @Test
    void sameWorkspaceNestedScopeKeepsOneCatalogSnapshot() {
        when(workspaceMapper.getOrgId(7)).thenReturn(42);
        when(tenantCatalogResolver.resolveCatalog(42)).thenReturn("cnx_before", "cnx_after");

        String nested = workScope.inWorkspace(7,
            () -> workScope.inWorkspace(7, () -> tenantContext.getCatalog()));

        assertEquals("cnx_before", nested);
        verify(workspaceMapper, times(1)).getOrgId(7);
        verify(tenantCatalogResolver, times(1)).resolveCatalog(42);
    }

    @Test
    void differentWorkspaceNestedScopeResolvesItsOwnCatalog() {
        when(workspaceMapper.getOrgId(7)).thenReturn(42);
        when(workspaceMapper.getOrgId(8)).thenReturn(43);
        when(tenantCatalogResolver.resolveCatalog(42)).thenReturn("cnx_outer");
        when(tenantCatalogResolver.resolveCatalog(43)).thenReturn("cnx_inner");

        String restored = workScope.inWorkspace(7, () -> {
            assertEquals("cnx_inner", workScope.inWorkspace(8, () -> tenantContext.getCatalog()));
            return tenantContext.getCatalog();
        });

        assertEquals("cnx_outer", restored);
    }

    @Test
    void returningToAnOuterWorkspaceReusesItsOriginalSnapshot() {
        when(workspaceMapper.getOrgId(7)).thenReturn(42);
        when(workspaceMapper.getOrgId(8)).thenReturn(43);
        when(tenantCatalogResolver.resolveCatalog(42)).thenReturn("cnx_before", "cnx_after");
        when(tenantCatalogResolver.resolveCatalog(43)).thenReturn("cnx_other");

        String returned = workScope.inWorkspace(7,
            () -> workScope.inWorkspace(8,
                () -> workScope.inWorkspace(7, () -> tenantContext.getCatalog())));

        assertEquals("cnx_before", returned);
        verify(tenantCatalogResolver, times(1)).resolveCatalog(42);
        verify(tenantCatalogResolver, times(1)).resolveCatalog(43);
    }

    @Test
    void returningToTheRequestWorkspaceReusesItsInstalledSnapshot() {
        tenantContext.set(7, 42, 9, "owner", "cnx_request");
        when(workspaceMapper.getOrgId(8)).thenReturn(43);
        when(tenantCatalogResolver.resolveCatalog(43)).thenReturn("cnx_other");

        String returned = workScope.inWorkspace(8,
            () -> workScope.inWorkspace(7, () -> tenantContext.getCatalog()));

        assertEquals("cnx_request", returned);
        verify(tenantCatalogResolver, times(1)).resolveCatalog(43);
    }

    @Test
    void sameResolvedRequestWorkspaceKeepsItsInstalledCatalog() {
        tenantContext.set(7, 42, 9, "owner", "cnx_request");

        assertEquals("cnx_request", workScope.inWorkspace(7, () -> tenantContext.getCatalog()));

        verifyNoInteractions(workspaceMapper, tenantCatalogResolver);
    }

    @Test
    void resolutionRunsUnroutedEvenInsideARoutedScope() {
        tenantContext.set(1, 1, 1, "owner", "cnx_caller");
        AtomicReference<String> catalogDuringResolution = new AtomicReference<>("unset");
        when(workspaceMapper.getOrgId(7)).thenAnswer(invocation -> {
            catalogDuringResolution.set(tenantContext.getCatalog());
            return 42;
        });
        when(tenantCatalogResolver.resolveCatalog(42)).thenReturn("cnx_target");

        String during = workScope.inWorkspace(7, () -> tenantContext.getCatalog());

        assertNull(catalogDuringResolution.get(),
            "placement resolution must run on the default catalog, never the caller's routed one");
        assertEquals("cnx_target", during);
        assertEquals("cnx_caller", tenantContext.getCatalog());
    }

    @Test
    void unroutedMasksARoutedScopeAndNestsCorrectly() {
        tenantContext.set(1, 1, 1, "owner", "cnx_outer");

        String inner = workScope.unrouted(() -> {
            String masked = tenantContext.getCatalog();
            String nested = workScope.withCatalog("cnx_nested", () -> tenantContext.getCatalog());
            assertEquals("cnx_nested", nested);
            return masked;
        });

        assertNull(inner);
        assertEquals("cnx_outer", tenantContext.getCatalog());
    }

    @Test
    void unservablePlacementPropagatesAndRestores() {
        when(workspaceMapper.getOrgId(7)).thenReturn(42);
        when(tenantCatalogResolver.resolveCatalog(42))
            .thenThrow(new ServiceUnavailableException("unservable"));

        assertThrows(ServiceUnavailableException.class, () -> workScope.inWorkspace(7, () -> null));
        assertNull(tenantContext.getCatalog());
    }

    @Test
    void missingWorkspaceIsRefused() {
        when(workspaceMapper.getOrgId(7)).thenReturn(null);
        assertThrows(IllegalStateException.class, () -> workScope.inWorkspace(7, () -> null));
    }

    @Test
    void lifecycleRoutingUsesTheTrustedUnfilteredWorkspaceLookup() {
        when(tenantLifecycleControlMapper.findWorkspaceOrgIdForLifecycle(7))
            .thenReturn(42);
        when(tenantCatalogResolver.resolveCatalog(42)).thenReturn("cnx_cleanup");

        assertEquals(
            "cnx_cleanup",
            workScope.inLifecycleWorkspace(7, () -> tenantContext.getCatalog()));
        verifyNoInteractions(workspaceMapper);
    }

    @Test
    void ordinaryWorkNestedInALifecycleScopeIsStillFenced() {
        when(tenantLifecycleControlMapper.findWorkspaceOrgIdForLifecycle(7))
            .thenReturn(42);
        when(tenantCatalogResolver.resolveCatalog(42)).thenReturn("cnx_cleanup");
        when(workspaceMapper.getOrgId(7)).thenReturn(null);

        assertThrows(
            IllegalStateException.class,
            () -> workScope.inLifecycleWorkspace(
                7,
                () -> workScope.inWorkspace(7, () -> null)));
    }

}
