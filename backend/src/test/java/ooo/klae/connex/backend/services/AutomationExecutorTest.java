package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.context.SecurityContextHolder;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.tenant.TenantCatalogResolver;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

@ExtendWith(MockitoExtension.class)
class AutomationExecutorTest {

    private final AutomationScope automationScope = new AutomationScope();
    @Mock private TenantCatalogResolver tenantCatalogResolver;
    @Mock private WorkspaceMapper workspaceMapper;
    @Mock private User principal;

    private TenantContext tenantContext;
    private TenantWorkScope tenantWorkScope;
    private AutomationExecutor executor;

    @BeforeEach
    void setUp() {
        tenantContext = new TenantContext();
        tenantWorkScope = new TenantWorkScope(tenantContext, tenantCatalogResolver, workspaceMapper);
        executor = new AutomationExecutor(tenantContext, automationScope, tenantWorkScope);
    }

    @AfterEach
    void clearThreadState() {
        tenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void runAsReusesTheSameWorkspaceOperationCatalog() {
        when(workspaceMapper.getOrgId(7)).thenReturn(42);
        when(tenantCatalogResolver.resolveCatalog(42)).thenReturn("cnx_before", "cnx_after");
        when(principal.getId()).thenReturn(9);
        when(principal.getAuthorities()).thenReturn(List.of());

        String during = tenantWorkScope.inWorkspace(7, () -> {
            var prepared = tenantContext.getWorkspaceScopes();
            assertNotNull(prepared);
            return executor.runAs(7, principal, "member", () -> {
                assertSame(prepared, tenantContext.getWorkspaceScopes());
                assertTrue(automationScope.isActive());
                assertEquals(AutomationScope.Origin.task(AutomationScope.Source.BACKGROUND_TASK, "user"),
                    automationScope.origin());
                assertEquals(7, tenantContext.getWorkspaceId());
                assertEquals(42, tenantContext.getOrgId());
                return tenantContext.getCatalog();
            });
        });

        assertEquals("cnx_before", during);
        assertFalse(tenantContext.isResolved());
        assertNull(tenantContext.getCatalog());
        assertNull(tenantContext.getWorkspaceScopes());
        verify(tenantCatalogResolver, times(1)).resolveCatalog(42);
        assertFalse(automationScope.isActive());
        assertNull(automationScope.origin());
    }
}
