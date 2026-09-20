package ooo.klae.connex.backend.ai.assistant;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.ai.provider.AiProviderCapabilities;
import ooo.klae.connex.backend.beans.AiChatToolCall;
import ooo.klae.connex.backend.beans.AiChatTurn;
import ooo.klae.connex.backend.dto.AiChatProgressItemDto;
import ooo.klae.connex.backend.dto.AiChatStepFrameDto;
import ooo.klae.connex.backend.mappers.AiChatMapper;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Projects durable internal tool state into a small viewer-safe progress vocabulary. */
@Service
@RequiredArgsConstructor
public class AiChatProgressService {
    /**
     * One turn's tool-call idempotency key, with the optional ordinal a shared step renders.
     *
     * <p>The suffix group is optional because a call that was the only one its step made still
     * writes the unsuffixed key. Without the group a suffixed row would not match at all and would
     * fall through to the {@code HARD_MAX_STEPS} placeholder, sorting a real milestone to the very
     * end of the turn.
     */
    private static final Pattern TURN_STEP = Pattern.compile(
            "turn-([1-9][0-9]*)-step-([1-9][0-9]*)(?:-call-([1-9][0-9]*))?");
    private static final int MAX_PROGRESS_COUNT = 1_000;
    private static final String SCOPE = "scope";
    private static final String ANSWER = "answer";

    /**
     * The progress vocabulary is the shared answer-coverage vocabulary plus the two synthetic
     * milestones that bracket every turn, so "What I checked" and "Sources checked" never name the
     * same category differently.
     */
    static final Set<String> PROGRESS_SOURCES = union(
            AiAssistantStepGuard.COVERAGE_SOURCES, SCOPE, ANSWER);
    static final Set<String> PROGRESS_STATUSES = Set.of(
            "running", "proposed", "complete", "failed", "skipped", "timed_out", "cancelled");

    private final AiChatMapper chatMapper;
    private final ObjectMapper objectMapper;

    /** Returns the current safe milestone snapshot for one authorized turn. */
    public List<AiChatProgressItemDto> project(AiChatTurn turn) {
        return project(turn.getWorkspaceId(), turn.getSessionId(), turn.getId(), turn.getStatus());
    }

    /**
     * Returns a safe milestone snapshot with an explicit terminal status for final persistence.
     *
     * <p>A {@code find_tools} row is skipped rather than mapped: the loop publishes no step frame
     * for it, so projecting one would raise an {@code other} milestone that appeared live and
     * vanished on reload. It reads nothing, so there is no coverage for it to claim.
     *
     * <p>The row limit is {@link AiChatAgentLoopService#MAX_TOOL_CALL_ROWS_PER_TURN} rather than the
     * step ceiling, because a step is no longer guaranteed to write exactly one
     * {@code ai_chat_tool_call} row. The step ceiling was an exact bound only while that equality
     * held; a turn whose steps may each carry several calls would silently lose real milestones
     * under it, while every suffixed key it wrote still parsed and looked healthy.
     */
    public List<AiChatProgressItemDto> project(
            int workspaceId, int sessionId, int turnId, String turnStatus) {
        Map<String, ProgressAccumulator> milestones = new LinkedHashMap<>();
        milestones.put(SCOPE, new ProgressAccumulator(
                0, SCOPE, "queued".equals(turnStatus) ? "running" : "complete"));
        String prefix = "turn-" + turnId + "-step-";
        for (AiChatToolCall toolCall : chatMapper.listToolCallsByTurn(
                workspaceId, sessionId, prefix,
                AiChatAgentLoopService.MAX_TOOL_CALL_ROWS_PER_TURN)) {
            if (AiAssistantToolCatalog.FIND_TOOLS.equals(toolCall.getToolName())) {
                continue;
            }
            String source = sourceForTool(toolCall.getToolName());
            int seq = step(toolCall.getIdempotencyKey(), turnId);
            ProgressAccumulator current = milestones.get(source);
            if (current == null) {
                current = new ProgressAccumulator(
                        seq, source, publicStatus(toolCall.getStatus(), turnStatus));
                milestones.put(source, current);
            } else {
                current.status = mergeStatus(
                        current.status, publicStatus(toolCall.getStatus(), turnStatus));
            }
            ProgressResult result = result(toolCall);
            current.addCount(result.count());
            current.truncated |= result.truncated();
        }
        milestones.put(ANSWER, new ProgressAccumulator(
                AiChatAgentLoopService.HARD_MAX_STEPS + 1,
                ANSWER, answerStatus(turnStatus)));
        return milestones.values().stream()
                .map(ProgressAccumulator::toDto)
                .toList();
    }

    /** Removes internal tool names and failure details from a browser-facing realtime frame. */
    public static AiChatStepFrameDto viewerFrame(AiChatStepFrameDto frame) {
        if (!"step".equals(frame.kind())) {
            return frame;
        }
        return new AiChatStepFrameDto(
                frame.workspaceId(), frame.sessionId(), frame.turnId(), frame.seq(),
                frame.kind(), sourceForTool(frame.tool()), frame.status(), null,
                frame.toolCallId(), null);
    }

    /** Removes requester-only tool-call identifiers from a shared-session milestone. */
    public static AiChatStepFrameDto sharedFrame(AiChatStepFrameDto frame) {
        AiChatStepFrameDto projected = viewerFrame(frame);
        return new AiChatStepFrameDto(
                projected.workspaceId(), projected.sessionId(), projected.turnId(),
                projected.seq(), projected.kind(), projected.tool(), projected.status(),
                projected.reason(), null, projected.text());
    }

    /**
     * Maps one closed internal tool key to a stable, localized source category drawn from the
     * shared coverage vocabulary. An unmapped tool reports the explicit {@code other} category
     * rather than claiming a record check it did not perform.
     * @param tool internal tool key, never shown to a viewer
     * @return viewer-safe source category
     */
    public static String sourceForTool(String tool) {
        return switch (tool == null ? "" : tool) {
            case "search_records", "get_record", "get_records" -> "records";
            case "get_deal_brief" -> "deals";
            case "list_activities", "create_activity", "list_scope_activities" -> "activities";
            case "relationship_metrics" -> "metrics";
            case "deal_attention" -> "deals";
            case "find_schedule_conflicts" -> "schedule";
            case "list_tasks", "create_task" -> "tasks";
            case "aggregate_metric" -> "metrics";
            case "create_note" -> "notes";
            case "add_tag", "change_deal_stage", "assign_owner" -> "actions";
            default -> "other";
        };
    }

    private ProgressResult result(AiChatToolCall toolCall) {
        if (!"executed".equals(toolCall.getStatus()) || toolCall.getResultJson() == null) {
            return ProgressResult.EMPTY;
        }
        try {
            JsonNode result = objectMapper.readTree(toolCall.getResultJson());
            Integer count = switch (toolCall.getToolName()) {
                case "search_records", "get_records" -> arraySize(result, "records");
                case "get_record", "create_activity", "create_task", "create_note",
                        "add_tag", "change_deal_stage", "assign_owner",
                        "relationship_metrics" -> 1;
                case "list_activities", "list_scope_activities" ->
                        arraySize(result, "activities");
                case "deal_attention" -> arraySize(result, "deals");
                case "list_tasks" -> arraySize(result, "tasks");
                case "find_schedule_conflicts" -> arraySize(result, "conflicts");
                default -> null;
            };
            return new ProgressResult(count, containsTruncation(result));
        } catch (JacksonException exception) {
            return ProgressResult.EMPTY;
        }
    }

    private static Integer arraySize(JsonNode node, String field) {
        JsonNode values = node == null ? null : node.get(field);
        return values != null && values.isArray()
                ? Math.min(values.size(), MAX_PROGRESS_COUNT)
                : null;
    }

    /**
     * Detects the exact truncation signals the tool executor emits: the literal {@code truncated}
     * flag and the {@code <field>Truncated} companion written by its bounded-text helpers. Matching
     * those names exactly, rather than any property containing "truncat", keeps a CRM field value
     * from being read as an execution bound.
     */
    private static boolean containsTruncation(JsonNode node) {
        if (node == null || node.isNull()) {
            return false;
        }
        if (node.isObject()) {
            for (String name : node.propertyNames()) {
                JsonNode value = node.get(name);
                if (isTruncationSignal(name)
                        && value != null && value.isBoolean() && value.asBoolean()) {
                    return true;
                }
                if (containsTruncation(value)) {
                    return true;
                }
            }
            return false;
        }
        if (node.isArray()) {
            for (JsonNode value : node) {
                if (containsTruncation(value)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isTruncationSignal(String propertyName) {
        return "truncated".equals(propertyName)
                || (propertyName.length() > "Truncated".length()
                        && propertyName.endsWith("Truncated"));
    }

    private static Set<String> union(Set<String> values, String... additional) {
        LinkedHashSet<String> combined = new LinkedHashSet<>(values);
        combined.addAll(List.of(additional));
        return Set.copyOf(combined);
    }

    /**
     * Reads the model step a durable key names, or the placeholder that sorts it last.
     *
     * <p>The call ordinal is parsed only to be bounded. A key claiming an ordinal past
     * {@link AiProviderCapabilities#MAX_PARALLEL_TOOL_CALLS} is one no step of this loop could have
     * written, so it is treated exactly as a malformed key rather than trusted for its step number.
     *
     * @param idempotencyKey the durable row's idempotency key
     * @param turnId the turn the projection is reading
     * @return the milestone's ordering step number
     */
    private static int step(String idempotencyKey, int turnId) {
        Matcher matcher = TURN_STEP.matcher(idempotencyKey == null ? "" : idempotencyKey);
        if (!matcher.matches()) {
            return AiChatAgentLoopService.HARD_MAX_STEPS;
        }
        try {
            if (Integer.parseInt(matcher.group(1)) != turnId) {
                return AiChatAgentLoopService.HARD_MAX_STEPS;
            }
            String ordinal = matcher.group(3);
            if (ordinal != null
                    && Integer.parseInt(ordinal)
                            > AiProviderCapabilities.MAX_PARALLEL_TOOL_CALLS) {
                return AiChatAgentLoopService.HARD_MAX_STEPS;
            }
            return Math.min(
                    Integer.parseInt(matcher.group(2)), AiChatAgentLoopService.HARD_MAX_STEPS);
        } catch (NumberFormatException exception) {
            return AiChatAgentLoopService.HARD_MAX_STEPS;
        }
    }

    /**
     * A durable {@code proposed} tool call is a CONFIRM write awaiting a human decision. While the
     * turn runs it reads as in-progress; once the turn is terminal it stays awaiting approval and
     * must never be reported as a completed action.
     */
    private static String publicStatus(String status, String turnStatus) {
        return switch (status == null ? "" : status) {
            case "executed" -> "complete";
            case "failed" -> "failed";
            case "rejected" -> "skipped";
            case "proposed" -> "queued".equals(turnStatus) || "running".equals(turnStatus)
                    ? "running"
                    : "proposed";
            default -> "running";
        };
    }

    private static String mergeStatus(String current, String next) {
        if ("failed".equals(current) || "failed".equals(next)) {
            return "failed";
        }
        if ("running".equals(current) || "running".equals(next)) {
            return "running";
        }
        if ("proposed".equals(current) || "proposed".equals(next)) {
            return "proposed";
        }
        if ("complete".equals(current) || "complete".equals(next)) {
            return "complete";
        }
        return "skipped";
    }

    private static String answerStatus(String status) {
        return switch (status == null ? "" : status) {
            case "resolved" -> "complete";
            case "failed" -> "failed";
            case "timed_out" -> "timed_out";
            case "cancelled" -> "cancelled";
            default -> "running";
        };
    }

    private static final class ProgressAccumulator {
        private final int seq;
        private final String source;
        private String status;
        private Integer count;
        private boolean truncated;

        private ProgressAccumulator(int seq, String source, String status) {
            this.seq = seq;
            this.source = source;
            this.status = status;
        }

        private void addCount(Integer additional) {
            if (additional == null) {
                return;
            }
            count = Math.min(MAX_PROGRESS_COUNT, (count == null ? 0 : count) + additional);
        }

        private AiChatProgressItemDto toDto() {
            return new AiChatProgressItemDto(seq, source, status, count, truncated);
        }
    }

    private record ProgressResult(Integer count, boolean truncated) {
        private static final ProgressResult EMPTY = new ProgressResult(null, false);
    }
}
