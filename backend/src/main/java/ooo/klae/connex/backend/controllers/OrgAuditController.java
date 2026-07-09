package ooo.klae.connex.backend.controllers;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.AuditLog;
import ooo.klae.connex.backend.services.AuditService;
import ooo.klae.connex.backend.services.AuthService;
import ooo.klae.connex.backend.services.OrgMemberService;

/**
 * The organization's audit trail (#316): org-plane governance events — SSO config, org membership,
 * and allowed domains — gated on org admin/owner. Workspace record events are NOT included; they
 * stay in the per-workspace audit ({@code /api/audit}) gated by {@code AUDIT_READ}, so org-plane
 * administration cannot read workspace content it has no workspace role for.
 */
@RestController
@RequestMapping("/api/orgs/{orgId}/audit")
@RequiredArgsConstructor
public class OrgAuditController {
    private static final byte[] UTF8_BOM = { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF };

    private final AuditService auditService;
    private final OrgMemberService orgMemberService;
    private final AuthService authService;

    @GetMapping
    public List<AuditLog> orgAudit(@PathVariable int orgId,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        orgMemberService.requireOrgAdmin(orgId, authService.getCurrentUser().getId());
        return auditService.recentForOrg(orgId, limit, offset);
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> exportOrgAudit(@PathVariable int orgId,
            @RequestParam(defaultValue = "10000") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        orgMemberService.requireOrgAdmin(orgId, authService.getCurrentUser().getId());
        return csv("org-audit-log.csv", auditService.exportRecentForOrg(orgId, limit, offset));
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
