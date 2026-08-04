package ooo.klae.connex.backend.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Bounded organization-layout page with independent authority and workspace cursors. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record OrganizationLayoutDto(
        OrganizationIdentityDto organization,
        List<OrganizationLayoutAuthorityMemberDto> authorityMemberships,
        Integer nextAuthorityMemberId,
        List<OrganizationLayoutWorkspaceDto> workspaces,
        Integer nextWorkspaceId) {

    public OrganizationLayoutDto {
        authorityMemberships = List.copyOf(authorityMemberships);
        workspaces = List.copyOf(workspaces);
    }
}
