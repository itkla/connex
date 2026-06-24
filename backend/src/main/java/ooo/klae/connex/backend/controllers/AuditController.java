package ooo.klae.connex.backend.controllers;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import ooo.klae.connex.backend.beans.AuditLog;
import ooo.klae.connex.backend.services.AuditService;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.tenant.Permission;

import java.util.List;

import lombok.RequiredArgsConstructor;

/**
 * REST controller for the audit log, so frontend-exclusive actions can also be recorded.
 */

@RestController
@RequestMapping("/api/audit")
@RequiredArgsConstructor
public class AuditController {
    private final AuditService auditService;
    private final WorkspaceService workspaceService;

    @GetMapping
    public List<AuditLog> getAuditLog(
        @RequestParam(required = false) String entityType,
        @RequestParam(required = false) Integer entityId,
        @RequestParam(defaultValue = "50") int limit
    ) {
        workspaceService.requirePermission(Permission.AUDIT_READ);
        if (entityType != null && entityId != null) {
            return auditService.forEntity(entityType, entityId, limit);
        }
        return auditService.recent(limit);
    }

    // @PostMapping("/add")
    // public void addAuditLog(@RequestBody AuditLog auditLog) {
    //     auditService.record(auditLog);
    // }
}