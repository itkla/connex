package ooo.klae.connex.backend.controllers;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.dto.AddOrgMemberRequest;
import ooo.klae.connex.backend.dto.MfaAttestationCodeDto;
import ooo.klae.connex.backend.dto.OrgMemberDto;
import ooo.klae.connex.backend.dto.MemberRoleChange;
import ooo.klae.connex.backend.dto.OwnershipOfferCreatedDto;
import ooo.klae.connex.backend.dto.OrgMemberRequest;
import ooo.klae.connex.backend.dto.OrgMembershipDto;
import ooo.klae.connex.backend.services.AuthService;
import ooo.klae.connex.backend.services.OrgMemberService;
import ooo.klae.connex.backend.services.OwnershipOfferService;
import ooo.klae.connex.backend.services.PrivilegedMfaAttestationService;

/**
 * Organization membership administration (#316). Listing an org's members
 * requires org admin; changing membership requires org owner — both enforced in
 * the service against {@code org_member}, independent of workspace roles.
 */
@RestController
@RequestMapping("/api/orgs")
@RequiredArgsConstructor
public class OrgMemberController {

    private final OrgMemberService orgMemberService;
    private final OwnershipOfferService ownershipOfferService;
    private final PrivilegedMfaAttestationService attestationService;
    private final AuthService authService;

    @GetMapping
    public List<OrgMembershipDto> myOrganizations() {
        return orgMemberService.membershipsForUser(authService.getCurrentUser().getId());
    }

    @GetMapping("/{orgId}/members")
    public List<OrgMemberDto> members(@PathVariable int orgId) {
        return orgMemberService.listMembers(orgId, authService.getCurrentUser().getId());
    }

    @PostMapping("/{orgId}/members")
    public ResponseEntity<?> addMember(@PathVariable int orgId, @Valid @RequestBody AddOrgMemberRequest request) {
        MemberRoleChange<OrgMemberDto> result = ownershipOfferService.changeOrganizationRole(orgId, authService.getCurrentUser().getId(), null,
                request.getEmail(), request.getOrgRole(),
                Boolean.TRUE.equals(request.getAcknowledgeConfinement()));
        return result.offer() == null ? ResponseEntity.noContent().build()
            : ResponseEntity.accepted().body(OwnershipOfferCreatedDto.from(result));
    }

    @PutMapping("/{orgId}/members/{userId}")
    public ResponseEntity<?> setMember(@PathVariable int orgId, @PathVariable int userId,
            @Valid @RequestBody OrgMemberRequest request) {
        MemberRoleChange<OrgMemberDto> result = ownershipOfferService.changeOrganizationRole(orgId, authService.getCurrentUser().getId(), userId, null, request.getOrgRole(),
                Boolean.TRUE.equals(request.getAcknowledgeConfinement()));
        return result.offer() == null ? ResponseEntity.noContent().build()
            : ResponseEntity.accepted().body(OwnershipOfferCreatedDto.from(result));
    }

    @DeleteMapping("/{orgId}/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeMember(@PathVariable int orgId, @PathVariable int userId) {
        ownershipOfferService.removeOrganizationMember(orgId, authService.getCurrentUser().getId(), userId);
    }

    /**
     * Leaves the organization: removes the caller's own org membership (#1534). It stays reachable
     * while privileged MFA confines the caller, so a membership that confines an account is always
     * one the account can give up. The last owner cannot leave.
     */
    @PostMapping("/{orgId}/leave")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void leave(@PathVariable int orgId) {
        ownershipOfferService.leaveOrganization(orgId, authService.getCurrentUser().getId());
    }

    /**
     * Issues a one-time code attesting an org member's passkey for privileged MFA in this
     * organization (#1534). Owners only. The code is returned once and must reach the member out
     * of band.
     */
    @PostMapping("/{orgId}/members/{userId}/mfa-attestations")
    @ResponseStatus(HttpStatus.CREATED)
    public MfaAttestationCodeDto issueMfaAttestation(@PathVariable int orgId, @PathVariable int userId) {
        return attestationService.issueForOrganization(orgId, authService.getCurrentUser().getId(), userId);
    }

    /** Revokes an org member's open attestation code for this organization (#1534). Owners only. */
    @DeleteMapping("/{orgId}/members/{userId}/mfa-attestations")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeMfaAttestation(@PathVariable int orgId, @PathVariable int userId) {
        attestationService.revokeForOrganization(orgId, authService.getCurrentUser().getId(), userId);
    }
}
