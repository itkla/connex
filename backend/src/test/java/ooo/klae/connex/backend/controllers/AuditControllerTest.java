package ooo.klae.connex.backend.controllers;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
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
    private static final byte[] UTF8_BOM = { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF };

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

    @Test
    void exportRecentRequiresPermissionAndForwardsLimitAndOffset() {
        when(auditService.exportRecent(500, 20)).thenReturn("csv");
        ResponseEntity<byte[]> response = controller.exportAuditLog(null, null, 500, 20);
        verify(workspaceService).requirePermission(Permission.AUDIT_READ);
        verify(auditService).exportRecent(500, 20);
        assertCsv(response, "audit-log.csv");
    }

    @Test
    void exportEntityRequiresPermissionAndForwardsLimitAndOffset() {
        when(auditService.exportForEntity("company", 12, 500, 20)).thenReturn("csv");
        ResponseEntity<byte[]> response = controller.exportAuditLog("company", 12, 500, 20);
        verify(workspaceService).requirePermission(Permission.AUDIT_READ);
        verify(auditService).exportForEntity("company", 12, 500, 20);
        assertCsv(response, "audit-log.csv");
    }

    private static void assertCsv(ResponseEntity<byte[]> response, String filename) {
        assertEquals(200, response.getStatusCode().value());
        assertEquals("text/csv;charset=UTF-8", String.valueOf(response.getHeaders().getContentType()));
        assertEquals("attachment; filename=\"" + filename + "\"",
            response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION));
        byte[] body = response.getBody();
        assertArrayEquals(UTF8_BOM, new byte[] { body[0], body[1], body[2] });
    }
}
