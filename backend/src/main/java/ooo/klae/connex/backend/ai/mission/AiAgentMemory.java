package ooo.klae.connex.backend.ai.mission;

import java.util.List;
import java.util.Objects;

/** Screened continuation prose and canonical evidence; neither is a provider token map. */
public record AiAgentMemory(int schemaVersion, DurableText summary,
        List<EvidenceReference> evidenceReferences) {
    public AiAgentMemory {
        Objects.requireNonNull(summary);
        evidenceReferences = List.copyOf(evidenceReferences);
    }

    /** Persistable prose with bounded authorized member-label provenance. */
    public record DurableText(String text, List<MemberProvenance> memberProvenance) {
        public DurableText {
            Objects.requireNonNull(text);
            memberProvenance = List.copyOf(memberProvenance);
        }
    }

    /** Original label authority is rechecked before a fresh masking context registers it. */
    public record MemberProvenance(int userId, String originalLabel) {
        public MemberProvenance {
            Objects.requireNonNull(originalLabel);
        }
    }

    /** Immutable primary-record evidence binding to one exact authorized retrieval. */
    public record EvidenceReference(String kind, int id, EvidenceVersion version) {
        public EvidenceReference {
            Objects.requireNonNull(kind);
            Objects.requireNonNull(version);
        }
    }

    /** Hash version of the privacy-shaped record, bounded activities, or bounded tasks. */
    public record EvidenceVersion(String source, int limit, String sha256) {
        public EvidenceVersion {
            Objects.requireNonNull(source);
            Objects.requireNonNull(sha256);
        }
    }
}
