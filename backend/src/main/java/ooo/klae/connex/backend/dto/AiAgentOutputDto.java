package ooo.klae.connex.backend.dto;

import java.time.Instant;
import java.util.List;

/** Canonical report and exact immutable singleton proposal membership. */
public record AiAgentOutputDto(int schemaVersion, int runId, String outputRevision,
        Report report, ProposalSet proposalSet, List<Object> autoWriteReceipts) {
    public record Report(int messageId, String markdown, List<AiChatCitationDto> citations,
            Coverage coverage, Instant generatedAt) {}
    public record Coverage(int processed, int skipped, int failed, int unprocessed) {}
    public record Counts(int pending, int executed, int rejected, int withdrawn, int undone) {
        /** Preserves existing read-only callers with no inverse outcome. */
        public Counts(int pending, int executed, int rejected, int withdrawn) {
            this(pending, executed, rejected, withdrawn, 0);
        }
    }
    public record ProposalItem(String actionSlot, String preparationId, int ordinal, Instant preparedAt,
            AiAssistantToolCallReadDto card) {}
    public record ProposalSet(int id, String revision, boolean frozen, Instant frozenAt,
            Instant firstPreparedAt, Instant reviewExpiresAt, List<ProposalItem> items, Counts derivedCounts) {
        /** Preserves empty runtime-one/two output. */
        public ProposalSet(int id, String revision, boolean frozen, Instant reviewExpiresAt,
                List<ProposalItem> items, Counts derivedCounts) {
            this(id, revision, frozen, null, null, reviewExpiresAt, items, derivedCounts);
        }
    }
}
