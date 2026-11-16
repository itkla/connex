package ooo.klae.connex.backend.controllers;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.dto.ExportPermissionRequest;
import ooo.klae.connex.backend.dto.ExportPermissions;
import ooo.klae.connex.backend.services.AuthService;
import ooo.klae.connex.backend.services.OrgMemberService;

/** Organization-scoped export policy, independent of workspace role grants. */
@RestController
@RequestMapping("/api/orgs/{orgId}/export-permissions")
@RequiredArgsConstructor
public class OrgExportPermissionController {
    private final OrgMemberService orgMemberService;
    private final AuthService authService;

    @GetMapping
    public ExportPermissions get(@PathVariable int orgId) {
        return orgMemberService.exportPermissions(orgId, authService.getCurrentUser().getId());
    }

    @PutMapping("/{role}")
    public ExportPermissions update(@PathVariable int orgId, @PathVariable String role,
            @Valid @RequestBody ExportPermissionRequest request) {
        return orgMemberService.updateExportPermission(orgId, authService.getCurrentUser().getId(),
            role, request.enabled());
    }
}
