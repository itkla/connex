package ooo.klae.connex.backend.ai.assistant;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.ai.assistant.AiSkillCatalog.PlanStep;
import ooo.klae.connex.backend.ai.assistant.AiSkillCatalog.SkillSpec;
import ooo.klae.connex.backend.dto.AiChatStepFrameDto;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.notifications.AiChatRealtimeDispatcher;
import ooo.klae.connex.backend.services.WorkspaceService;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Executes a selected skill's server-owned retrieval plan before the model sees the turn.
 *
 * <p>Each step becomes an ordinary durable tool call, so the answer's evidence trail, the viewer's
 * progress vocabulary, and the citation projection all behave exactly as they do for a model-chosen
 * step. What changes is who decided: the plan is declared, so a routine job costs a fixed, bounded
 * number of reads instead of however many the model improvises.
 *
 * <p>A non-required step that fails is recorded as failed and the plan continues, because a bounded
 * partial answer is more useful than destroying a turn over one unavailable source. A required step
 * that fails ends the plan and the caller falls back to the generic loop. "Fails" means any runtime
 * fault at all: a step that proposed a durable tool call always reaches a terminal state, so the
 * bounded-partial contract cannot be broken by a failure mode the plan did not anticipate.
 */
@Service
@RequiredArgsConstructor
public class AiSkillPlanRunner {
    private static final int MAX_ROW_BYTES_ESTIMATE = 220;
    private static final int MIN_ADAPTIVE_ROWS = 5;

    private final AiChatTurnPersistenceService persistenceService;
    private final AiAssistantToolExecutor toolExecutor;
    private final AiAssistantScopeReadService scopeReadService;
    private final AiAssistantPromptAssembler promptAssembler;
    private final AiChatRealtimeDispatcher realtimeDispatcher;
    private final WorkspaceService workspaceService;
    private final ObjectMapper objectMapper;

    /**
     * The outcome of one server-owned plan.
     *
     * @param executed whether any step ran and produced evidence for the model
     * @param evidence masked-on-assembly evidence payload handed to the model as CRM data
     * @param lastStepNumber highest durable step number the plan consumed, which the caller must
     *     honour even when the plan produced nothing, because those step keys are already durable
     * @param degraded whether a declared step failed or was bounded below its declared limit
     */
    public record Execution(
            boolean executed,
            Map<String, Object> evidence,
            int lastStepNumber,
            boolean degraded) {

        public Execution {
            evidence = Map.copyOf(evidence);
        }

        /** Returns the outcome used when no plan ran. */
        public static Execution none() {
            return new Execution(false, Map.of(), 0, false);
        }
    }

    /**
     * Runs one declared plan.
     *
     * @param turn committed durable turn
     * @param routing the routing decision that selected the skill
     * @param scope validated declared query scope
     * @param resources per-turn handle registry
     * @param evidenceByteBudget bytes the plan's evidence may occupy in one model step
     * @param guard revalidation to run immediately before each step executes
     * @return the plan outcome
     */
    public Execution run(
            AiChatQueuedTurn turn,
            AiSkillRouter.Routing routing,
            AiChatQueryScope scope,
            AiChatResourceRegistry resources,
            int evidenceByteBudget,
            Runnable guard) {
        SkillSpec skill = routing.skill();
        if (skill == null || skill.plan().isEmpty()) {
            return Execution.none();
        }
        requireSkillPermissions(turn, skill);
        String subjectHandle = null;
        if (routing.subject() != null) {
            subjectHandle = resources
                    .handleFor(routing.subject().kind(), routing.subject().id())
                    .orElse(null);
        }
        if (skill.needsSubject() && subjectHandle == null) {
            return Execution.none();
        }
        List<Map<String, Object>> evidence = new ArrayList<>();
        boolean degraded = false;
        int stepNumber = 0;
        for (PlanStep step : skill.plan()) {
            stepNumber++;
            guard.run();
            String toolName = step.kind().toolName();
            int toolCallId = persistenceService.proposeTool(
                    turn, stepNumber, toolName, planArguments(step, routing, scope));
            publish(turn, stepNumber, toolName, "proposed", null);
            try {
                AiAssistantToolResult result = execute(
                        step, routing, scope, resources, subjectHandle,
                        evidenceByteBudget, turn.includePrivateNotes());
                persistenceService.finishTool(
                        turn, toolCallId, "executed",
                        promptAssembler.durableToolResult(result));
                publish(turn, stepNumber, toolName, "executed", null);
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("kind", step.kind().name().toLowerCase(java.util.Locale.ROOT));
                entry.put("status", "ok");
                entry.put("data", result.data());
                evidence.add(entry);
                result.identifiers().forEach(
                        identifier -> identifier.seed(resources.maskingContext()));
            } catch (RuntimeException exception) {
                // Every failure mode of a declared step lands here, not just the three the plan can
                // anticipate: a malformed saved view, an unreadable segment, or any other runtime
                // fault must still close the durable tool call it already proposed. Leaving that row
                // proposed would strand the turn's progress trail and silently break the declared
                // bounded-partial contract.
                String reason = failureReason(exception);
                persistenceService.failTool(
                        turn, toolCallId, serialize(Map.of("reason", reason)));
                publish(turn, stepNumber, toolName, "failed", reason);
                degraded = true;
                if (step.required()) {
                    return new Execution(
                            !evidence.isEmpty(),
                            evidence.isEmpty() ? Map.of() : payload(skill, evidence),
                            stepNumber,
                            true);
                }
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("kind", step.kind().name().toLowerCase(java.util.Locale.ROOT));
                entry.put("status", "unavailable");
                evidence.add(entry);
            }
        }
        if (evidence.isEmpty()) {
            return new Execution(false, Map.of(), stepNumber, true);
        }
        return new Execution(true, payload(skill, evidence), stepNumber, degraded);
    }

    private AiAssistantToolResult execute(
            PlanStep step,
            AiSkillRouter.Routing routing,
            AiChatQueryScope scope,
            AiChatResourceRegistry resources,
            String subjectHandle,
            int evidenceByteBudget,
            boolean includePrivateNotes) {
        return switch (step.kind()) {
            case GET_RECORD -> toolExecutor.execute(
                    "get_record", handleArguments(subjectHandle, null),
                    resources, includePrivateNotes);
            case LIST_ACTIVITIES -> toolExecutor.execute(
                    "list_activities", handleArguments(subjectHandle, step.rowLimit()),
                    resources, includePrivateNotes);
            case LIST_TASKS -> toolExecutor.execute(
                    "list_tasks", handleArguments(subjectHandle, step.rowLimit()),
                    resources, includePrivateNotes);
            case RELATIONSHIP_METRICS -> scopeReadService.relationshipMetrics(
                    routing.subject().kind(), routing.subject().id(), subjectHandle);
            case SCOPE_ACTIVITIES -> scopeReadService.scopeActivities(
                    scope,
                    null,
                    contextKind(routing),
                    List.of(),
                    scope.periodDays(),
                    adaptiveRows(step.rowLimit(), evidenceByteBudget),
                    step.perRecordLimit(),
                    resources);
            case DEAL_ATTENTION -> scopeReadService.dealAttention(
                    scope, step.rowLimit(), resources);
        };
    }

    /**
     * The declared row cap, lowered when the model step cannot carry it. A model whose context
     * window forces a two-kilobyte tool allocation cannot be handed a hundred rows: sizing the read
     * to the budget returns a smaller honest sample instead of an evidence blob the assembler has
     * to discard whole.
     */
    private static int adaptiveRows(int declaredRows, int evidenceByteBudget) {
        if (evidenceByteBudget <= 0) {
            return declaredRows;
        }
        int affordable = evidenceByteBudget / MAX_ROW_BYTES_ESTIMATE;
        return Math.max(MIN_ADAPTIVE_ROWS, Math.min(declaredRows, affordable));
    }

    /**
     * The anchoring record's kind, offered only as context. It is never a narrowing argument: the
     * declared scope decides the cohort wherever it states one, and a page anchor may not override
     * the interpretation the requester was shown.
     */
    private static String contextKind(AiSkillRouter.Routing routing) {
        return routing.subject() == null ? null : routing.subject().kind();
    }

    private ObjectNode handleArguments(String handle, Integer limit) {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("handle", handle);
        if (limit != null && limit > 0) {
            args.put("limit", limit);
        }
        return args;
    }

    private String planArguments(
            PlanStep step, AiSkillRouter.Routing routing, AiChatQueryScope scope) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("plan", step.kind().name().toLowerCase(java.util.Locale.ROOT));
        arguments.put("skill", routing.skill().key());
        arguments.put("skillVersion", routing.skill().version());
        if (step.rowLimit() > 0) {
            arguments.put("limit", step.rowLimit());
        }
        if (step.perRecordLimit() > 0) {
            arguments.put("perRecord", step.perRecordLimit());
        }
        if (scope.declared()) {
            arguments.put("scopeDeclared", true);
        }
        return serialize(arguments);
    }

    private static Map<String, Object> payload(
            SkillSpec skill, List<Map<String, Object>> evidence) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("skill", skill.key());
        payload.put("skillVersion", skill.version());
        payload.put("evidence", List.copyOf(evidence));
        return payload;
    }

    /**
     * Re-asserts the skill's declared permissions immediately before its first step.
     *
     * <p>The routing-time check ran on the request thread against a pre-lock snapshot. This plan
     * executes later, on the generation thread, so the permission the router saw may already have
     * been withdrawn by the time any durable read happens.
     */
    private void requireSkillPermissions(AiChatQueuedTurn turn, SkillSpec skill) {
        if (skill.permissions().isEmpty()) {
            return;
        }
        if (!workspaceService.permissionsFor(turn.workspaceId(), turn.userId())
                .containsAll(skill.permissions())) {
            throw new ForbiddenException(
                    "Assistant skill permissions are no longer held in this workspace");
        }
    }

    private static String failureReason(RuntimeException exception) {
        if (exception instanceof AiAssistantLoopException loopException) {
            return Optional.ofNullable(loopException.detailReason()).orElse("tool_failure");
        }
        if (exception instanceof ForbiddenException) {
            return "access_revoked";
        }
        if (exception instanceof ResourceNotFoundException) {
            return "inaccessible_resource";
        }
        return "tool_failure";
    }

    private void publish(
            AiChatQueuedTurn turn, int stepNumber, String toolName, String status, String reason) {
        realtimeDispatcher.sessionNow(
                turn.workspaceId(),
                turn.sessionId(),
                AiChatProgressService.sharedFrame(new AiChatStepFrameDto(
                        turn.workspaceId(), turn.sessionId(), turn.turnId(),
                        stepNumber, "step", toolName, status, reason)));
    }

    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException(
                    "Assistant skill plan metadata could not be serialized", exception);
        }
    }
}
