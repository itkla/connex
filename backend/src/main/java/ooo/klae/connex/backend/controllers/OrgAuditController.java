package ooo.klae.connex.backend.controllers;

import java.util.List;

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
 * The organization's audit trail (#316): org-scoped events (SSO config, org membership, allowed
 * domains, and every workspace action's org attribution), gated on org admin/owner. Distinct from
 * the workspace audit log ({@code /api/audit}), which is workspace-scoped.
 */
@RestController
@RequestMapping("/api/orgs/{orgId}/audit")
@RequiredArgsConstructor
public class OrgAuditController {

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
}
