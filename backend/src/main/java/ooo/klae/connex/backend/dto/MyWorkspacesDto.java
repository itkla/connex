package ooo.klae.connex.backend.dto;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The current user's workspaces plus the id that is active by default
 * (null when the user belongs to no workspace yet). Powers the switcher and
 * the onboarding redirect decision.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MyWorkspacesDto {
    private List<WorkspaceMembershipDto> workspaces;
    private Integer activeWorkspaceId;
}
