package ooo.klae.connex.backend.ai.mission;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.ai.assistant.AiAssistantLoopException;
import ooo.klae.connex.backend.ai.assistant.AiAssistantPreparedWrite;
import ooo.klae.connex.backend.ai.assistant.AiAssistantToolCatalog.ToolTier;
import ooo.klae.connex.backend.ai.assistant.AiAssistantToolExecutor;
import ooo.klae.connex.backend.ai.assistant.AiAssistantToolProposal;
import ooo.klae.connex.backend.ai.assistant.AiAssistantToolResult;
import ooo.klae.connex.backend.ai.assistant.AiAssistantWriteToolService;
import ooo.klae.connex.backend.ai.assistant.AiChatResourceRegistry;
import ooo.klae.connex.backend.beans.AiAgentAction;
import ooo.klae.connex.backend.beans.AiChatToolCall;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.mappers.AiChatMapper;
import ooo.klae.connex.backend.tenant.TenantWorkScope;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Prepares outside content locks and publishes only after complete model-result admission. */
@Service
@RequiredArgsConstructor
public class AiAgentProposalService {
    private final AiAgentProposalSetPort proposals;
    private final AiAssistantWriteToolService writes;
    private final AiAssistantToolExecutor tools;
    private final AiAgentEvidenceService evidence;
    private final AiChatMapper chat;
    private final ObjectMapper json;
    private final Clock clock;
    private final TenantWorkScope tenantWorkScope;

    /** Candidate remains transient until the caller screens and admits its complete provider result. */
    public record Prepared(AiAgentAction action, AiAssistantPreparedWrite write, AiAssistantToolResult result) {
    }

    /** Resolves semantic identity first and preserves every original pin and preparation anchor on replay. */
    public Prepared prepare(AiAgentExecutionPolicy policy, String tool, JsonNode arguments,
            AiChatResourceRegistry resources, Map<String, List<String>> parsedMembers) {
        return tenantWorkScope.inWorkspace(policy.turn().workspaceId(),
                () -> prepareInWorkspace(policy, tool, arguments, resources, parsedMembers));
    }

    private Prepared prepareInWorkspace(AiAgentExecutionPolicy policy, String tool, JsonNode arguments,
            AiChatResourceRegistry resources, Map<String, List<String>> parsedMembers) {
        if (!policy.proposalCapable() || !AiAgentMissionCatalog.confirmTools(policy.scope().kind()).contains(tool)) {
            throw new AiAssistantLoopException("mission_tool_refused", "mission_tool_refused");
        }
        var requestedTarget = resources.resolve(arguments.path("handle").asString());
        policy.scope().requireRecord(requestedTarget.kind(), requestedTarget.id());
        String semantic = policy.scope().kind() + ":" + policy.scope().id() + ":" + tool;
        AiAgentAction action = proposals.findSlot(policy.turn().workspaceId(), policy.runId(), semantic);
        if (action == null) {
            throw new ConflictException("mission_action_conflict");
        }
        Instant preparedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        AiChatToolCall existing = action.getPreparationId() == null ? null
                : chat.getMissionToolCall(policy.turn().workspaceId(), policy.runId(), action.getId(), policy.turn().privacyToken().organizationPrivacyGeneration());
        if (action.getPreparationId() != null && existing == null) {
            throw new ConflictException("mission_action_conflict");
        }
        AiAssistantPreparedWrite write = existing == null
                ? writes.prepare(tool, arguments, resources, policy.turn().restrictionEpoch(), parsedMembers)
                : writes.prepareReplay(tool, arguments, resources, policy.turn().restrictionEpoch(), existing.getArgumentsJson());
        policy.scope().requireRecord(write.targetKind(), write.targetId());
        if (write.tier() != ToolTier.CONFIRM) {
            throw new AiAssistantLoopException("mission_tool_refused", "mission_tool_refused");
        }
        byte[] intent = fingerprint(write);
        if (existing != null && !java.util.Arrays.equals(action.getIntentFingerprint(), intent)) {
            throw new ConflictException("action_intent_conflict");
        }
        if (existing == null) {
            AiAgentSourceObservations observations = AiAgentEvidenceService.currentAnchors();
            observations.requireComplete(write.targetKind(), write.targetId(), preparedAt);
            action.setPreparationId(UUID.randomUUID().toString());
            action.setIntentFingerprint(intent);
            action.setPreparedAt(LocalDateTime.ofInstant(preparedAt, ZoneOffset.UTC));
            action.setSourceObservedAt(LocalDateTime.ofInstant(observations.firstObservedAt(), ZoneOffset.UTC));
            action.setSourceVersionsJson(new AiAgentSourceObservationsCodec().encode(observations));
            action.setOriginSegmentId(policy.segmentId());
            action.setOriginExecutionGeneration(policy.executionGeneration());
        }
        AiAssistantToolProposal proposal = existing == null
                ? new AiAssistantToolProposal(0, "proposed", null, true)
                : new AiAssistantToolProposal(existing.getId(), existing.getStatus(), existing.getResultJson(), false);
        return new Prepared(action, write, writes.proposalResult(write, proposal));
    }

    /** Revalidates original source identities without treating a later observation time as a new version. */
    public void revalidate(AiAgentExecutionPolicy policy, List<AiAgentAction> actions) {
        for (AiAgentAction action : actions) {
            if (action.getPreparationId() == null) {
                continue;
            }
            var observations = new AiAgentSourceObservationsCodec().decode(action.getSourceVersionsJson());
            observations.requireComplete(action.getTargetKind(), action.getTargetId(), action.getPreparedAt().toInstant(ZoneOffset.UTC));
            AiAgentEvidenceService.carrySources(observations);
            AiChatResourceRegistry resources = new AiChatResourceRegistry();
            String handle = resources.register(action.getTargetKind(), action.getTargetId());
            evidence.revalidate(observations.sources().stream().map(AiAgentSourceObservations.Observation::reference).toList(), reference -> {
                ObjectNode args = json.createObjectNode().put("handle", handle);
                String source = reference.version().source();
                if (!"record".equals(source)) {
                    args.put("limit", reference.version().limit());
                }
                String tool = switch (source) {
                    case "record" -> "get_record";
                    case "activities" -> "list_activities";
                    case "tasks" -> "list_tasks";
                    default -> throw new IllegalArgumentException("mission_continuation_refused");
                };
                return policy.scope().evaluate(() -> tools.execute(tool, args, resources, false, policy.turn().scope()));
            });
        }
    }

    private byte[] fingerprint(AiAssistantPreparedWrite write) {
        JsonNode envelope = json.readTree(write.argumentsJson());
        ObjectNode canonical = json.createObjectNode();
        canonical.put("version", 1);
        canonical.put("tool", write.toolName());
        canonical.set("target", envelope.get("target"));
        if (!(envelope.get("request") instanceof ObjectNode original)) {
            throw new ConflictException("mission_action_conflict");
        }
        ObjectNode request = original.deepCopy();
        request.remove("handle");
        canonical.set("request", request);
        return AiAgentCapabilityGate.hash(json.writeValueAsString(canonical(canonical)));
    }

    private JsonNode canonical(JsonNode node) {
        if (node.isObject()) {
            var values = new TreeMap<String, JsonNode>();
            node.properties().forEach(entry -> values.put(entry.getKey(), entry.getValue()));
            ObjectNode result = json.createObjectNode();
            values.forEach((key, value) -> result.set(key, canonical(value)));
            return result;
        }
        if (node.isArray()) {
            var result = json.createArrayNode();
            node.forEach(value -> result.add(canonical(value)));
            return result;
        }
        return node;
    }
}
