package ooo.klae.connex.backend.dto;

import java.util.List;

/** Administrative ceiling only; enabling it never creates member authority. */
public record AiStandingApprovalPolicyDto(
        boolean provisioned,
        boolean authoringEnabled,
        boolean enabled,
        List<String> allowedTools,
        int maximumDailyCap,
        int maximumPerRunCap,
        String stateVersion,
        String killEpoch) {
    public AiStandingApprovalPolicyDto {
        allowedTools = List.copyOf(allowedTools);
    }
}
