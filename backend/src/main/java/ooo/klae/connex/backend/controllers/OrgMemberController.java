package ooo.klae.connex.backend.controllers;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
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
import ooo.klae.connex.backend.dto.OrgMemberDto;
import ooo.klae.connex.backend.dto.OrgMemberRequest;
import ooo.klae.connex.backend.dto.OrgMembershipDto;
import ooo.klae.connex.backend.services.AuthService;
import ooo.klae.connex.backend.services.OrgMemberService;

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
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void addMember(@PathVariable int orgId, @Valid @RequestBody AddOrgMemberRequest request) {
        orgMemberService.setMemberByEmail(orgId, authService.getCurrentUser().getId(),
                request.getEmail(), request.getOrgRole());
    }

    @PutMapping("/{orgId}/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setMember(@PathVariable int orgId, @PathVariable int userId,
            @Valid @RequestBody OrgMemberRequest request) {
        orgMemberService.setMember(orgId, authService.getCurrentUser().getId(), userId, request.getOrgRole());
    }

    @DeleteMapping("/{orgId}/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeMember(@PathVariable int orgId, @PathVariable int userId) {
        orgMemberService.removeMember(orgId, authService.getCurrentUser().getId(), userId);
    }
}
