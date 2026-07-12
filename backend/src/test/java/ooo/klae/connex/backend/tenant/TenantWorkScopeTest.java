package ooo.klae.connex.backend.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.exceptions.ServiceUnavailableException;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;

@ExtendWith(MockitoExtension.class)
class TenantWorkScopeTest {

    @Mock private TenantCatalogResolver tenantCatalogResolver;
    @Mock private WorkspaceMapper workspaceMapper;

    private final TenantContext tenantContext = new TenantContext();
    private TenantWorkScope workScope;

    @BeforeEach
    void setUp() {
        workScope = new TenantWorkScope(tenantContext, tenantCatalogResolver, workspaceMapper);
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
    void sharedPlacementPinsTheDefaultCatalog() {
        when(workspaceMapper.getOrgId(7)).thenReturn(42);
        when(tenantCatalogResolver.resolveCatalog(42)).thenReturn(null);

        assertNull(workScope.inWorkspace(7, () -> tenantContext.getCatalog()));
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
    void resolveCatalogUnroutedMasksTheCallerScope() {
        tenantContext.set(1, 1, 1, "owner", "cnx_caller");
        AtomicReference<String> catalogDuringResolution = new AtomicReference<>("unset");
        when(tenantCatalogResolver.resolveCatalog(42)).thenAnswer(invocation -> {
            catalogDuringResolution.set(tenantContext.getCatalog());
            return "cnx_target";
        });

        assertEquals("cnx_target", workScope.resolveCatalogUnrouted(42));
        assertNull(catalogDuringResolution.get());
        assertEquals("cnx_caller", tenantContext.getCatalog());
    }
}
