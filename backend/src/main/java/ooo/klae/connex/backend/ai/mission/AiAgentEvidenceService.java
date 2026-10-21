package ooo.klae.connex.backend.ai.mission;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.ai.assistant.AiAssistantLoopException;
import ooo.klae.connex.backend.ai.assistant.AiAssistantToolResult;
import ooo.klae.connex.backend.ai.assistant.AiChatResourceRegistry;
import ooo.klae.connex.backend.ai.mission.AiAgentMemory.EvidenceReference;
import ooo.klae.connex.backend.ai.mission.AiAgentMemory.EvidenceVersion;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Versions only privacy-shaped authorized reads and keeps segment observations ephemeral. */
@Service
@RequiredArgsConstructor(onConstructor_ = @org.springframework.beans.factory.annotation.Autowired)
public class AiAgentEvidenceService {
    private static final ThreadLocal<Observations> CURRENT = new ThreadLocal<>();
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /** Preserves direct unit construction while production supplies the server clock. */
    public AiAgentEvidenceService(ObjectMapper objectMapper) {
        this(objectMapper, Clock.systemUTC());
    }

    /** Starts a fresh observation inventory for exactly one claimed segment attempt. */
    public void begin(AiAgentExecutionPolicy policy) {
        CURRENT.set(new Observations(this, Objects.requireNonNull(policy, "policy")));
    }

    /** Removes all request-local evidence after a bounded execution. */
    public void clear() {
        CURRENT.remove();
    }

    /** Records the authoritative anchor immediately before retrieval begins. */
    public static void beforeRead(String tool) {
        Observations observations = CURRENT.get();
        if (observations != null && observations.policy == AiAgentExecutionPolicy.current()
                && Set.of("get_record", "get_records", "list_activities", "list_tasks").contains(tool)) {
            observations.readStartedAt = observations.service.clock.instant().truncatedTo(ChronoUnit.MICROS);
        }
    }

    /** Returns server-owned observations for atomic continuation and proposal persistence. */
    public static AiAgentSourceObservations currentAnchors() {
        Observations observations = CURRENT.get();
        return observations == null ? new AiAgentSourceObservations(1, List.of()) : observations.anchors;
    }

    /** Restores original action provenance before any reconstruction reads capture newer anchors. */
    public static void carrySources(AiAgentSourceObservations carried) {
        Observations observations = CURRENT.get();
        if (observations != null && observations.policy == AiAgentExecutionPolicy.current()
                && observations.policy.proposalCapable()) {
            observations.anchors = observations.anchors.merge(carried);
        }
    }

    /** Captures the shaped result before masking; transient handles never affect its version. */
    public static void capture(String tool, JsonNode arguments, AiAssistantToolResult result,
            AiChatResourceRegistry resources) {
        Observations observations = CURRENT.get();
        if (observations == null || observations.policy != AiAgentExecutionPolicy.current()) {
            return;
        }
        String source = switch (tool) {
            case "get_record", "get_records" -> "record";
            case "list_activities" -> "activities";
            case "list_tasks" -> "tasks";
            default -> null;
        };
        if (source == null) {
            return;
        }
        int limit = "record".equals(source) ? 0 : arguments.path("limit").asInt(10);
        var scope = observations.policy.scope();
        EvidenceReference reference = observations.service.reference(scope.kind(), scope.id(),
                source, limit, result);
        if (observations.references.stream().anyMatch(existing -> existing.kind().equals(reference.kind())
                && existing.id() == reference.id() && existing.version().source().equals(source)
                && existing.version().limit() == limit && !existing.equals(reference))) {
            throw refused("source_changed");
        }
        observations.references.add(reference);
        if (observations.policy.proposalCapable()) {
            if (observations.readStartedAt == null) {
                throw refused("mission_observation_missing");
            }
            observations.anchors = observations.anchors.merge(new AiAgentSourceObservations(1, List.of(
                    new AiAgentSourceObservations.Observation(reference.kind(), reference.id(), source,
                            limit, reference.version().sha256(), observations.readStartedAt))));
        }
        observations.readStartedAt = null;
        if (observations.references.size() > 64) {
            throw refused("mission_evidence_overflow");
        }
    }

    /** Binds carried text to every authorized source that could have supplied its identifiers. */
    public List<EvidenceReference> observed(AiAgentExecutionPolicy policy) {
        return List.copyOf(requireObservations(policy).references);
    }

    /** Returns canonical versions for handles actually read during this attempt. */
    public List<EvidenceReference> resolveCitations(AiAgentExecutionPolicy policy,
            List<String> citations, AiChatResourceRegistry resources) {
        Observations observations = requireObservations(policy);
        var resolved = new LinkedHashSet<EvidenceReference>();
        for (String citation : citations) {
            var resource = resources.resolve(citation);
            policy.scope().requireRecord(resource.kind(), resource.id());
            List<EvidenceReference> matching = observations.references.stream()
                    .filter(reference -> reference.kind().equals(resource.kind())
                            && reference.id() == resource.id()).toList();
            if (matching.isEmpty()) {
                throw AiAssistantLoopException.refusedArguments("mission_citation_unobserved");
            }
            resolved.addAll(matching);
        }
        return List.copyOf(resolved);
    }

    /** Repeats the exact closed retrieval and refuses changed required evidence before dispatch. */
    public List<AiAssistantToolResult> revalidate(List<EvidenceReference> references,
            Function<EvidenceReference, AiAssistantToolResult> reader) {
        List<AiAssistantToolResult> results = new ArrayList<>();
        for (EvidenceReference reference : references) {
            AiAssistantToolResult result;
            try {
                result = reader.apply(reference);
            } catch (AiAssistantLoopException exception) {
                if (!Set.of("access_revoked", "mission_scope_refused").contains(exception.terminalReason())) {
                    throw exception;
                }
                throw refused("access_lost");
            } catch (ooo.klae.connex.backend.exceptions.ForbiddenException
                    | ooo.klae.connex.backend.exceptions.ResourceNotFoundException exception) {
                throw refused("access_lost");
            }
            EvidenceReference current = reference(reference.kind(), reference.id(),
                    reference.version().source(), reference.version().limit(), result);
            if (!current.equals(reference)) {
                throw refused("source_changed");
            }
            results.add(result);
        }
        return List.copyOf(results);
    }

    /** Computes a deterministic version of data and identifier provenance without request tokens. */
    public EvidenceReference reference(String kind, int id, String source, int limit,
            AiAssistantToolResult result) {
        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("version", 1);
        envelope.set("data", canonical(objectMapper.valueToTree(result.data())));
        ArrayNode identifiers = envelope.putArray("identifiers");
        result.identifiers().stream().sorted(Comparator
                .comparing(AiAssistantToolResult.Identifier::kind)
                .thenComparing(AiAssistantToolResult.Identifier::value)
                .thenComparing(identifier -> Objects.toString(identifier.path(), "")))
                .forEach(identifier -> identifiers.add(objectMapper.valueToTree(identifier)));
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(objectMapper.writeValueAsString(envelope).getBytes(StandardCharsets.UTF_8)));
            return new EvidenceReference(kind, id, new EvidenceVersion(source, limit, hash));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private JsonNode canonical(JsonNode value) {
        if (value.isObject()) {
            Map<String, JsonNode> sorted = new TreeMap<>();
            value.properties().stream().filter(entry -> !"handle".equals(entry.getKey()))
                    .forEach(entry -> sorted.put(entry.getKey(), entry.getValue()));
            ObjectNode result = objectMapper.createObjectNode();
            sorted.forEach((key, child) -> result.set(key, canonical(child)));
            if (result.size() == 1 && result.path("records").isArray()
                    && result.path("records").size() == 1) {
                return result.path("records").get(0);
            }
            return result;
        }
        if (value.isArray()) {
            ArrayNode result = objectMapper.createArrayNode();
            value.forEach(child -> result.add(canonical(child)));
            return result;
        }
        return value;
    }

    private static Observations requireObservations(AiAgentExecutionPolicy policy) {
        Observations observations = CURRENT.get();
        if (observations == null || observations.policy != policy) {
            throw new IllegalStateException("Mission evidence identity is unavailable");
        }
        return observations;
    }

    private static AiAssistantLoopException refused(String reason) {
        return new AiAssistantLoopException(reason, reason);
    }

    private static final class Observations {
        private final AiAgentEvidenceService service;
        private final AiAgentExecutionPolicy policy;
        private final List<EvidenceReference> references = new ArrayList<>();
        private AiAgentSourceObservations anchors;
        private Instant readStartedAt;

        private Observations(AiAgentEvidenceService service, AiAgentExecutionPolicy policy) {
            this.service = service;
            this.policy = policy;
            anchors = policy.snapshot() != null && policy.snapshot().checkpoint().sourceObservations() != null
                    ? policy.snapshot().checkpoint().sourceObservations() : new AiAgentSourceObservations(1, List.of());
        }
    }
}
