package ooo.klae.connex.backend.dto;

import ooo.klae.connex.backend.beans.AiWorkspaceGovernance;

/** Administrator-facing workspace AI availability and assistant turn limits. */
public record AiWorkspaceGovernanceDto(
        int workspaceId,
        boolean enabled,
        int assistantMaxSteps) {

    /** Projects one tenant-local governance row. */
    public static AiWorkspaceGovernanceDto from(AiWorkspaceGovernance governance) {
        return new AiWorkspaceGovernanceDto(
                governance.getWorkspaceId(),
                governance.isAiEnabled(),
                governance.getAssistantMaxSteps());
    }
}
