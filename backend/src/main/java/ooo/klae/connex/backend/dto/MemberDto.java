package ooo.klae.connex.backend.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A workspace member with their role, for the member-management view.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MemberDto {
    private int id;
    private String username;
    private String displayName;
    private String email;
    private String profilePictureUrl;
    private String role;
    private String builtInRole;
    private Integer roleId;
    private String status;
    /** Org-local approval gap, omitted unless the caller can issue an approval for this member. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Boolean passkeyApprovalNeeded;
}
