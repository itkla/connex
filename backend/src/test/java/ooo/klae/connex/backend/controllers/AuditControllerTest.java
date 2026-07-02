package ooo.klae.connex.backend.controllers;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.services.AuditService;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.tenant.Permission;

@ExtendWith(MockitoExtension.class)
class AuditControllerTest {
    @Mock private AuditService auditService;
    @Mock private WorkspaceService workspaceService;

    private AuditController controller;

    @BeforeEach
    void setUp() {
        controller = new AuditController(auditService, workspaceService);
    }

    @Test
    void recentBranchRequiresPermissionAndForwardsLimitAndOffset() {
        when(auditService.recent(150, 200)).thenReturn(List.of());
        controller.getAuditLog(null, null, 150, 200);
        verify(workspaceService).requirePermission(Permission.AUDIT_READ);
        verify(auditService).recent(150, 200);
    }

    @Test
    void entityBranchForwardsLimitAndOffset() {
        when(auditService.forEntity("company", 12, 25, 50)).thenReturn(List.of());
        controller.getAuditLog("company", 12, 25, 50);
        verify(workspaceService).requirePermission(Permission.AUDIT_READ);
        verify(auditService).forEntity("company", 12, 25, 50);
    }
}
