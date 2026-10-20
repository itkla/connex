package ooo.klae.connex.backend.ai.assistant;

/** Immutable provenance captured before reading any history or derived input. */
public record AiChatPrivacyToken(
        int workspaceId, int sessionId, long privacyGeneration, Integer taintSeqAtAdmission,
        int organizationId, long organizationPrivacyGeneration) {
    public AiChatPrivacyToken {
        if (workspaceId <= 0 || sessionId <= 0 || privacyGeneration <= 0
                || organizationId <= 0 || organizationPrivacyGeneration <= 0
                || (taintSeqAtAdmission != null && taintSeqAtAdmission <= 0)) {
            throw new IllegalArgumentException("Invalid assistant privacy identity");
        }
    }
}
