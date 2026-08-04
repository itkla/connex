package ooo.klae.connex.backend.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Active workspace node in an organization layout. Restricted rosters are represented by
 * {@code rosterVisible=false} and an empty membership list without hidden-member metadata.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record OrganizationLayoutWorkspaceDto(
        int id,
        String name,
        String slug,
        String timezone,
        boolean rosterVisible,
        List<OrganizationLayoutWorkspaceMemberDto> memberships,
        boolean membershipsTruncated) {

    public OrganizationLayoutWorkspaceDto {
        memberships = List.copyOf(memberships);
    }
}
