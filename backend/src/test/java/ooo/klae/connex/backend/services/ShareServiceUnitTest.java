package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.dto.ShareDto;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.services.ShareControlOperations.ShareAccess;
import ooo.klae.connex.backend.services.ShareControlOperations.ShareListControl;
import ooo.klae.connex.backend.services.ShareControlOperations.WorkspaceSnapshot;
import ooo.klae.connex.backend.services.ShareService.Type;
import ooo.klae.connex.backend.tenant.TenantCatalogResolver;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

@ExtendWith(MockitoExtension.class)
class ShareServiceUnitTest {
    @Mock private ShareTenantOperations tenantOperations;
    @Mock private ShareControlOperations controlOperations;
    @Mock private TenantCatalogResolver tenantCatalogResolver;
    @Mock private WorkspaceMapper workspaceMapper;

    private TenantContext tenantContext;
    private ShareService service;

    @BeforeEach
    void setUp() {
        tenantContext = new TenantContext();
        tenantContext.set(7, 9, 42, "owner", "cnx_tenant");
        TenantWorkScope tenantWorkScope = new TenantWorkScope(
            tenantContext, tenantCatalogResolver, workspaceMapper);
        service = new ShareService(tenantOperations, controlOperations, tenantWorkScope);
    }

    @AfterEach
    void clearTenantContext() {
        tenantContext.clear();
    }

    @Test
    void shareRoutesControlAndTenantPhasesToTheirOwnCatalogs() {
        WorkspaceSnapshot snapshot = new WorkspaceSnapshot(
            List.of(7, 8), Map.of(7, "Owner", 8, "Target"));
        ShareAccess access = new ShareAccess(7, 9, 42);
        when(controlOperations.requireAccess()).thenAnswer(invocation -> {
            assertNull(tenantContext.getCatalog());
            return access;
        });
        when(controlOperations.prepareTarget(7, 9, 8, 42)).thenAnswer(invocation -> {
            assertNull(tenantContext.getCatalog());
            return snapshot;
        });
        doAnswer(invocation -> {
            assertEquals("cnx_tenant", tenantContext.getCatalog());
            return null;
        }).when(tenantOperations).requireShareableOwned(Type.COMPANY, 7, 101);
        doAnswer(invocation -> {
            assertEquals("cnx_tenant", tenantContext.getCatalog());
            return null;
        }).when(tenantOperations).share(Type.COMPANY, 101, 7, 8, snapshot.ids(), 42, true);
        doAnswer(invocation -> {
            assertNull(tenantContext.getCatalog());
            return null;
        }).when(controlOperations).recordShare("company", 101, 7, 9, 8);

        service.share("company", 101, 8, true);

        assertEquals("cnx_tenant", tenantContext.getCatalog());
        verify(tenantOperations).share(Type.COMPANY, 101, 7, 8, snapshot.ids(), 42, true);
        verify(controlOperations).recordShare("company", 101, 7, 9, 8);
    }

    @Test
    void listHydratesSortsAndOmitsUnknownWorkspaceRows() {
        WorkspaceSnapshot snapshot = new WorkspaceSnapshot(
            List.of(7, 8, 10), Map.of(7, "Owner", 8, "zulu", 10, "Alpha"));
        ShareDto zulu = share(8, false);
        ShareDto stale = share(99, false);
        ShareDto alpha = share(10, true);
        when(controlOperations.prepareList()).thenAnswer(invocation -> {
            assertNull(tenantContext.getCatalog());
            return new ShareListControl(new ShareAccess(7, 9, 42), snapshot);
        });
        when(tenantOperations.list(Type.PERSON, 7, 202)).thenAnswer(invocation -> {
            assertEquals("cnx_tenant", tenantContext.getCatalog());
            return List.of(zulu, stale, alpha);
        });

        List<ShareDto> result = service.listShares("person", 202);

        assertEquals(List.of(10, 8), result.stream().map(ShareDto::getWorkspaceId).toList());
        assertEquals(List.of("Alpha", "zulu"), result.stream().map(ShareDto::getWorkspaceName).toList());
        assertEquals("cnx_tenant", tenantContext.getCatalog());
        verify(tenantOperations).list(Type.PERSON, 7, 202);
        verify(controlOperations).prepareList();
    }

    private static ShareDto share(int workspaceId, boolean canEdit) {
        ShareDto share = new ShareDto();
        share.setWorkspaceId(workspaceId);
        share.setCanEdit(canEdit);
        return share;
    }
}
