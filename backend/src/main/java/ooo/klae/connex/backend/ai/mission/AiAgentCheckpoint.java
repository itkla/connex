package ooo.klae.connex.backend.ai.mission;

import java.util.List;
import java.util.Objects;

import ooo.klae.connex.backend.ai.mission.AiAgentMemory.DurableText;
import ooo.klae.connex.backend.ai.mission.AiAgentMemory.EvidenceReference;

/** Versioned execution ledger bound to the exact durable run and privacy generation. */
public record AiAgentCheckpoint(int schemaVersion, int definitionVersion, int workspaceId,
        int runId, String scopeFingerprint, String privacyGeneration, String revision,
        String planRevision, List<String> completedPlanItemKeys, String nextPlanItemKey,
        int lastCommittedSegmentNo, List<CompletedFact> completedFacts,
        List<ActionSlotReference> actionSlotReferences, List<EvidenceReference> evidenceReferences,
        Coverage coverage, Continuation continuation,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        AiAgentSourceObservations sourceObservations) {
    /** Preserves schema-one construction without adding a serialized observation property. */
    public AiAgentCheckpoint(int schemaVersion, int definitionVersion, int workspaceId,
            int runId, String scopeFingerprint, String privacyGeneration, String revision,
            String planRevision, List<String> completedPlanItemKeys, String nextPlanItemKey,
            int lastCommittedSegmentNo, List<CompletedFact> completedFacts,
            List<ActionSlotReference> actionSlotReferences, List<EvidenceReference> evidenceReferences,
            Coverage coverage, Continuation continuation) {
        this(schemaVersion, definitionVersion, workspaceId, runId, scopeFingerprint, privacyGeneration,
                revision, planRevision, completedPlanItemKeys, nextPlanItemKey, lastCommittedSegmentNo,
                completedFacts, actionSlotReferences, evidenceReferences, coverage, continuation, null);
    }

    public AiAgentCheckpoint {
        Objects.requireNonNull(scopeFingerprint);
        Objects.requireNonNull(privacyGeneration);
        Objects.requireNonNull(revision);
        Objects.requireNonNull(planRevision);
        completedPlanItemKeys = List.copyOf(completedPlanItemKeys);
        completedFacts = List.copyOf(completedFacts);
        actionSlotReferences = List.copyOf(actionSlotReferences);
        evidenceReferences = List.copyOf(evidenceReferences);
        Objects.requireNonNull(coverage);
        Objects.requireNonNull(continuation);
    }

    /** Append-only observation for one completed item, independent of later label edits. */
    public record CompletedFact(String planItemKey, DurableText summary,
            List<EvidenceReference> evidenceReferences, int recordedSegmentNo) {
        public CompletedFact {
            Objects.requireNonNull(planItemKey);
            Objects.requireNonNull(summary);
            evidenceReferences = List.copyOf(evidenceReferences);
        }
    }

    /** Reserved server-only shape; runtime 2 cannot execute a nonempty action inventory. */
    public record ActionSlotReference(String slotKey, String planItemKey) {
        public ActionSlotReference {
            Objects.requireNonNull(slotKey);
            Objects.requireNonNull(planItemKey);
        }
    }

    /** Server-derived coverage of the single immutable primary scope. */
    public record Coverage(int processed, int skipped, int failed, int unprocessed,
            List<CoverageReason> reasons) {
        public Coverage {
            reasons = List.copyOf(reasons);
        }
    }

    /** Bounded explanatory count for server-observed coverage. */
    public record CoverageReason(String code, int count) {
        public CoverageReason {
            Objects.requireNonNull(code);
        }
    }

    /** Closed continuation discriminator; future missions require an explicit contract. */
    public record Continuation(String type, int version, String phase) {
        public Continuation {
            Objects.requireNonNull(type);
            Objects.requireNonNull(phase);
        }
    }
}
