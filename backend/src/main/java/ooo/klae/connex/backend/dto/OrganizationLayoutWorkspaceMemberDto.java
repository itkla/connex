package ooo.klae.connex.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.Data;
import lombok.NoArgsConstructor;

/** One visible workspace membership edge in an organization layout response. */
@Data
@NoArgsConstructor
public class OrganizationLayoutWorkspaceMemberDto {
    private int workspaceId;
    private int userId;
    private String displayName;
    private String profilePictureUrl;
    private String role;
    private Integer roleId;
    private String status;
    @JsonIgnore
    private boolean rosterTruncated;
}
