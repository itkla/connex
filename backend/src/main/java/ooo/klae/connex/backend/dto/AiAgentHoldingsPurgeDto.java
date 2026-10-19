package ooo.klae.connex.backend.dto;

/** Metadata-only progress of one immutable organization mission-holdings erasure operation. */
public record AiAgentHoldingsPurgeDto(
        int schemaVersion, String operationId, String requestId, String scope,
        String cutoffPolicy, String acceptedAt, String state, int workspaceCount,
        int completedWorkspaceCount, String completedAt, String reasonCode) {}
