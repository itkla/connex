package ooo.klae.connex.backend.ai.mission;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import ooo.klae.connex.backend.ai.mission.AiAgentMemory.EvidenceReference;
import ooo.klae.connex.backend.ai.mission.AiAgentMemory.EvidenceVersion;

/** Immutable source versions with pre-read observation times kept separate from hash equality. */
public record AiAgentSourceObservations(int schemaVersion, List<Observation> sources) {
    private static final Comparator<Observation> ORDER = Comparator.comparing(Observation::kind)
            .thenComparingInt(Observation::id).thenComparing(Observation::source)
            .thenComparingInt(Observation::limit);

    public AiAgentSourceObservations {
        AiAgentContinuationCodec.require(schemaVersion == 1 && sources != null && sources.size() <= 64);
        AiAgentContinuationCodec.require(sources.stream().allMatch(Objects::nonNull));
        sources = sources.stream().sorted(ORDER).toList();
        var identities = new java.util.HashSet<String>();
        for (Observation source : sources) {
            AiAgentContinuationCodec.require(source != null && identities.add(source.identity()));
        }
    }

    /** Original authoritative retrieval anchor for one complete source version. */
    public record Observation(String kind, int id, String source, int limit, String sha256, Instant observedAt) {
        public Observation {
            AiAgentContinuationCodec.require(kind != null && Set.of("person", "company", "deal").contains(kind) && id > 0);
            AiAgentContinuationCodec.require(("record".equals(source) && limit == 0)
                    || (source != null && Set.of("activities", "tasks").contains(source) && limit >= 1 && limit <= 20));
            AiAgentContinuationCodec.require(sha256 != null && sha256.matches("[0-9a-f]{64}"));
            AiAgentContinuationCodec.require(observedAt != null
                    && observedAt.equals(observedAt.truncatedTo(ChronoUnit.MICROS)));
        }

        /** Version identity deliberately excludes its observation timestamp. */
        public EvidenceReference reference() {
            return new EvidenceReference(kind, id, new EvidenceVersion(source, limit, sha256));
        }

        private String identity() {
            return kind + ":" + id + ":" + source + ":" + limit;
        }
    }

    /** Merges carried observations while preserving the earliest anchor for the same version. */
    public AiAgentSourceObservations merge(AiAgentSourceObservations next) {
        var merged = new LinkedHashMap<String, Observation>();
        for (Observation observation : sources) {
            merged.put(observation.identity(), observation);
        }
        for (Observation observation : next.sources) {
            Observation previous = merged.get(observation.identity());
            AiAgentContinuationCodec.require(previous == null || previous.reference().equals(observation.reference()));
            if (previous == null || observation.observedAt().isBefore(previous.observedAt())) {
                merged.put(observation.identity(), observation);
            }
        }
        return new AiAgentSourceObservations(1, new ArrayList<>(merged.values()));
    }

    /** Validates the complete primary observation and refuses future or contradictory anchors. */
    public void requireComplete(String kind, int id, Instant preparedAt) {
        Objects.requireNonNull(preparedAt);
        AiAgentContinuationCodec.require(sources.stream().anyMatch(observation -> observation.kind().equals(kind)
                && observation.id() == id && "record".equals(observation.source()) && observation.limit() == 0));
        for (Observation observation : sources) {
            AiAgentContinuationCodec.require(kind.equals(observation.kind()) && observation.id() == id
                    && !observation.observedAt().isAfter(preparedAt));
        }
    }

    /** Returns the immutable minimum anchor across the complete source vector. */
    public Instant firstObservedAt() {
        return sources.stream().map(Observation::observedAt).min(Instant::compareTo).orElseThrow();
    }
}
