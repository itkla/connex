package ooo.klae.connex.backend.dto;

import java.time.Instant;
import java.util.List;

/** Canonical report and deliberately empty slice-one review collection. */
public record AiAgentOutputDto(int schemaVersion, int runId, String outputRevision,
        Report report, ProposalSet proposalSet, List<Object> autoWriteReceipts) {
    public record Report(int messageId, String markdown, List<AiChatCitationDto> citations,
            Coverage coverage, Instant generatedAt) {}
    public record Coverage(int processed, int skipped, int failed, int unprocessed) {}
    public record Counts(int pending, int executed, int rejected, int withdrawn) {}
    public record ProposalSet(int id, String revision, boolean frozen, Instant reviewExpiresAt,
            List<Object> items, Counts derivedCounts) {}
}
