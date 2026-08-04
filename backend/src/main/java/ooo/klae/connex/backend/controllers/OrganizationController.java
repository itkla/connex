package ooo.klae.connex.backend.controllers;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.dto.OrganizationIdentityDto;
import ooo.klae.connex.backend.dto.OrganizationLayoutDto;
import ooo.klae.connex.backend.dto.RenameOrganizationRequest;
import ooo.klae.connex.backend.services.AuthService;
import ooo.klae.connex.backend.services.OrganizationService;

/** Organization identity settings and authorized organization-layout reads. */
@RestController
@RequestMapping("/api/orgs")
@RequiredArgsConstructor
public class OrganizationController {
    private final OrganizationService organizationService;
    private final AuthService authService;

    @PatchMapping("/{orgId}")
    public OrganizationIdentityDto rename(
            @PathVariable int orgId,
            @Valid @RequestBody RenameOrganizationRequest request) {
        return organizationService.rename(
            orgId,
            authService.getCurrentUser().getId(),
            request.getName(),
            request.getExpectedName(),
            request.getExpectedIdentityVersion());
    }

    @GetMapping("/{orgId}/layout")
    public OrganizationLayoutDto layout(
            @PathVariable int orgId,
            @RequestParam(defaultValue = "0") int afterWorkspaceId,
            @RequestParam(defaultValue = "0") int afterAuthorityMemberId,
            @RequestParam(defaultValue = "50") int limit) {
        return organizationService.getLayout(
            orgId,
            authService.getCurrentUser().getId(),
            afterWorkspaceId,
            afterAuthorityMemberId,
            limit);
    }
}
