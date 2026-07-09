package ooo.klae.connex.backend.controllers;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import ooo.klae.connex.backend.beans.AuditLog;
import ooo.klae.connex.backend.services.AuditService;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.tenant.Permission;

import lombok.RequiredArgsConstructor;

/**
 * REST controller for the audit log, so frontend-exclusive actions can also be recorded.
 */

@RestController
@RequestMapping("/api/audit")
@RequiredArgsConstructor
public class AuditController {
    private static final byte[] UTF8_BOM = { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF };

    private final AuditService auditService;
    private final WorkspaceService workspaceService;

    @GetMapping
    public List<AuditLog> getAuditLog(
        @RequestParam(required = false) String entityType,
        @RequestParam(required = false) Integer entityId,
        @RequestParam(defaultValue = "50") int limit,
        @RequestParam(defaultValue = "0") int offset
    ) {
        workspaceService.requirePermission(Permission.AUDIT_READ);
        if (entityType != null && entityId != null) {
            return auditService.forEntity(entityType, entityId, limit, offset);
        }
        return auditService.recent(limit, offset);
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> exportAuditLog(
        @RequestParam(required = false) String entityType,
        @RequestParam(required = false) Integer entityId,
        @RequestParam(defaultValue = "10000") int limit,
        @RequestParam(defaultValue = "0") int offset
    ) {
        workspaceService.requirePermission(Permission.AUDIT_READ);
        String body = entityType != null && entityId != null
            ? auditService.exportForEntity(entityType, entityId, limit, offset)
            : auditService.exportRecent(limit, offset);
        return csv("audit-log.csv", body);
    }

    private static ResponseEntity<byte[]> csv(String filename, String body) {
        byte[] content = body.getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[UTF8_BOM.length + content.length];
        System.arraycopy(UTF8_BOM, 0, payload, 0, UTF8_BOM.length);
        System.arraycopy(content, 0, payload, UTF8_BOM.length, content.length);
        return ResponseEntity.ok()
            .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
            .body(payload);
    }
}
