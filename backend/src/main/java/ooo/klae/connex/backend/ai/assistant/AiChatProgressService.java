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
    private static final Pattern TURN_STEP = Pattern.compile("turn-([1-9][0-9]*)-step-([1-9][0-9]*)");
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

    /** Returns a safe milestone snapshot with an explicit terminal status for final persistence. */
    public List<AiChatProgressItemDto> project(
            int workspaceId, int sessionId, int turnId, String turnStatus) {
        Map<String, ProgressAccumulator> milestones = new LinkedHashMap<>();
        milestones.put(SCOPE, new ProgressAccumulator(
                0, SCOPE, "queued".equals(turnStatus) ? "running" : "complete"));
        String prefix = "turn-" + turnId + "-step-";
        for (AiChatToolCall toolCall : chatMapper.listToolCallsByTurn(
                workspaceId, sessionId, prefix, AiChatAgentLoopService.HARD_MAX_STEPS)) {
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

    /** Downgrades model coverage claims when durable execution proves failure or truncation. */
    public static AiAssistantStep.Coverage reconcileCoverage(
            AiAssistantStep.Coverage claimed,
            List<AiChatProgressItemDto> progress,
            AiAssistantPromptAssembler.ToolBudgetAudit toolBudgetAudit) {
        Objects.requireNonNull(claimed, "claimed");
        Objects.requireNonNull(progress, "progress");
        Objects.requireNonNull(toolBudgetAudit, "toolBudgetAudit");
        boolean failed = progress.stream().anyMatch(item -> "failed".equals(item.status()));
        boolean truncated = claimed.truncated()
                || toolBudgetAudit.degraded()
                || progress.stream().anyMatch(AiChatProgressItemDto::truncated);
        LinkedHashSet<String> exclusions = new LinkedHashSet<>(claimed.exclusions());
        if (failed) {
            exclusions.add("tool_failure");
        }
        if (truncated) {
            exclusions.add("bounded_results");
        }
        String status = claimed.status();
        if ("complete".equals(status) && (failed || truncated || !exclusions.isEmpty())) {
            status = "partial";
        }
        return new AiAssistantStep.Coverage(
                status,
                claimed.asOf(),
                claimed.periodStart(),
                claimed.periodEnd(),
                claimed.sources(),
                List.copyOf(exclusions),
                truncated);
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

    private static int step(String idempotencyKey, int turnId) {
        Matcher matcher = TURN_STEP.matcher(idempotencyKey == null ? "" : idempotencyKey);
        if (!matcher.matches()) {
            return AiChatAgentLoopService.HARD_MAX_STEPS;
        }
        try {
            if (Integer.parseInt(matcher.group(1)) != turnId) {
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
