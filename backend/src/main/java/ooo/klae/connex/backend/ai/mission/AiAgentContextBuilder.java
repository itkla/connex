package ooo.klae.connex.backend.ai.mission;

import ooo.klae.connex.backend.services.RecordVisibilityScopeControlAccess;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.ai.assistant.AiAssistantLoopException;
import ooo.klae.connex.backend.ai.assistant.AiAssistantMemberIdentifiers;
import ooo.klae.connex.backend.ai.assistant.AiAssistantToolExecutor;
import ooo.klae.connex.backend.ai.assistant.AiAssistantToolResult;
import ooo.klae.connex.backend.ai.assistant.AiChatResourceRegistry;
import ooo.klae.connex.backend.ai.masking.MaskingContext;
import ooo.klae.connex.backend.ai.masking.MaskingEngine;
import ooo.klae.connex.backend.ai.mission.AiAgentMemory.EvidenceReference;
import ooo.klae.connex.backend.services.WorkspaceService;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Reconstructs a mission using only its durable state and freshly authorized bounded evidence. */
@Service
@RequiredArgsConstructor
public class AiAgentContextBuilder {
    private final AiAgentPlanService planService;
    private final AiAgentEvidenceService evidenceService;
    private final AiAssistantToolExecutor toolExecutor;
    private final WorkspaceService workspaceService;
    private final ObjectMapper objectMapper;

    private final RecordVisibilityScopeControlAccess recordVisibilityScopeControlAccess;

    /** Builds a separate model projection without provenance, canonical IDs, or session history. */
    public AiAssistantToolResult build(AiAgentExecutionPolicy policy, MaskingContext context,
            AiChatResourceRegistry resources) {
        return recordVisibilityScopeControlAccess.withDeferredWorkspaceScope(
            policy.turn().workspaceId(), () -> buildWithinVisibilityScope(policy, context, resources));
    }

    private AiAssistantToolResult buildWithinVisibilityScope(AiAgentExecutionPolicy policy, MaskingContext context,
            AiChatResourceRegistry resources) {
        var snapshot = policy.persistence().snapshot(policy);
        if (AiAgentContentScreen.texts(snapshot).stream().anyMatch(text -> !text.text().isBlank())
                && snapshot.checkpoint().evidenceReferences().stream()
                        .noneMatch(reference -> "record".equals(reference.version().source()))) {
            throw new AiAssistantLoopException("source_changed", "source_changed");
        }
        var members = workspaceService.getMembers(policy.turn().workspaceId());
        Set<Integer> memberIds = members.stream().filter(java.util.Objects::nonNull)
                .map(ooo.klae.connex.backend.beans.User::getId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        for (var text : AiAgentContentScreen.texts(snapshot)) {
            for (var provenance : text.memberProvenance()) {
                if (!memberIds.contains(provenance.userId())) {
                    throw new AiAssistantLoopException("access_lost", "access_lost");
                }
                context.registerMemberCandidate(provenance.originalLabel());
            }
        }
        AiAssistantMemberIdentifiers.seed(members, context);
        evidenceService.begin(policy);
        String handle = resources.register(policy.scope().kind(), policy.scope().id());
        List<AiAssistantToolResult> reads = new ArrayList<>(evidenceService.revalidate(
                snapshot.checkpoint().evidenceReferences(), reference -> read(reference, handle, resources)));
        if (snapshot.checkpoint().evidenceReferences().stream()
                .noneMatch(reference -> "record".equals(reference.version().source()))) {
            ObjectNode primaryArgs = objectMapper.createObjectNode().put("handle", handle);
            reads.add(toolExecutor.execute("get_record", primaryArgs, resources,
                    false, policy.turn().scope()));
        }
        List<AiAssistantToolResult.Identifier> identifiers = new ArrayList<>();
        for (int index = 0; index < reads.size(); index++) {
            String prefix = "/missionContext/evidence/" + index;
            reads.get(index).identifiers().stream().map(identifier ->
                    new AiAssistantToolResult.Identifier(identifier.kind(), identifier.value(),
                            identifier.path() == null ? null : prefix + identifier.path())).forEach(identifiers::add);
        }
        for (var identifier : identifiers) {
            switch (identifier.kind()) {
                case "person" -> MaskingEngine.maskField(
                        ooo.klae.connex.backend.ai.masking.EntityKind.PERSON, identifier.value(), context);
                case "company" -> MaskingEngine.maskField(
                        ooo.klae.connex.backend.ai.masking.EntityKind.COMPANY, identifier.value(), context);
                case "deal" -> MaskingEngine.maskField(
                        ooo.klae.connex.backend.ai.masking.EntityKind.DEAL, identifier.value(), context);
                case "member" -> context.registerMemberCandidate(identifier.value());
                default -> { }
            }
        }
        Map<String, Object> continuation = new LinkedHashMap<>();
        continuation.put("plan", planService.modelPlanText(snapshot.plan(), text -> text.text()));
        Map<String, Object> checkpoint = new LinkedHashMap<>();
        checkpoint.put("revision", snapshot.checkpoint().revision());
        checkpoint.put("planRevision", snapshot.checkpoint().planRevision());
        checkpoint.put("nextPlanItemKey", snapshot.checkpoint().nextPlanItemKey());
        checkpoint.put("completedFacts", snapshot.checkpoint().completedFacts().stream()
                .map(fact -> Map.of("planItemKey", fact.planItemKey(),
                        "summary", fact.summary().text(),
                        "citations", citations(fact.evidenceReferences(), resources))).toList());
        checkpoint.put("coverage", snapshot.checkpoint().coverage());
        checkpoint.put("continuation", snapshot.checkpoint().continuation());
        continuation.put("checkpoint", checkpoint);
        continuation.put("memory", Map.of("summary",
                snapshot.memory().summary().text(),
                "citations", citations(snapshot.memory().evidenceReferences(), resources)));
        for (int index = 0; index < snapshot.plan().items().size(); index++) {
            identifiers.addAll(AiAgentContentScreen.identifiers(snapshot.plan().items().get(index).label(),
                    "/missionContext/plan/items/" + index + "/label"));
        }
        for (int index = 0; index < snapshot.checkpoint().completedFacts().size(); index++) {
            identifiers.addAll(AiAgentContentScreen.identifiers(snapshot.checkpoint().completedFacts().get(index).summary(),
                    "/missionContext/checkpoint/completedFacts/" + index + "/summary"));
        }
        identifiers.addAll(AiAgentContentScreen.identifiers(snapshot.memory().summary(), "/missionContext/memory/summary"));
        continuation.put("evidence", reads.stream().map(AiAssistantToolResult::data).toList());
        return new AiAssistantToolResult(Map.of("missionContext", continuation), identifiers);
    }

    private AiAssistantToolResult read(EvidenceReference reference, String handle,
            AiChatResourceRegistry resources) {
        ObjectNode arguments = objectMapper.createObjectNode().put("handle", handle);
        String tool = switch (reference.version().source()) {
            case "record" -> "get_record";
            case "activities" -> "list_activities";
            case "tasks" -> "list_tasks";
            default -> throw new AiAssistantLoopException("unsupported_contract", "unsupported_contract");
        };
        if (!"record".equals(reference.version().source())) {
            arguments.put("limit", reference.version().limit());
        }
        return toolExecutor.execute(tool, arguments, resources, false,
                AiAgentExecutionPolicy.current().turn().scope());
    }

    private static List<String> citations(List<EvidenceReference> evidence,
            AiChatResourceRegistry resources) {
        return evidence.stream().map(reference -> resources.handleFor(reference.kind(), reference.id())
                .orElseThrow(() -> new AiAssistantLoopException("access_lost", "access_lost")))
                .distinct().toList();
    }
}
