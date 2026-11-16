package ooo.klae.connex.backend.services;

import java.util.Locale;
import java.util.Set;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import ooo.klae.connex.backend.dto.WorkflowDiagnosticCode;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;

/** Bounded execution evidence stored in the existing rule execution detail, without payloads. */
record RuleExecutionEvidence(Long workflowVersionId, ActionFailure failure) {
    private static final Set<String> ACTION_TYPES = Set.of(
        "create_task", "log_activity", "add_tag", "remove_tag", "create_note",
        "assign_owner", "set_response_due", "change_stage", "notify", "send_message");
    private static final Set<String> FAILURE_CODES = Set.of(
        "actor_unavailable", "record_unavailable", "permission_denied", "reference_unavailable",
        "configuration_invalid", "state_conflict", "execution_failed");

    static RuleExecutionEvidence read(ObjectMapper mapper, String detail) {
        if (detail == null || detail.length() > 4096) {
            return new RuleExecutionEvidence(null, null);
        }
        try {
            JsonNode root = mapper.readTree(detail);
            if (root == null || !root.isObject()) {
                return new RuleExecutionEvidence(null, null);
            }
            JsonNode version = root.path("workflowVersionId");
            Long versionId = version.isIntegralNumber() && version.canConvertToLong()
                && version.longValue() > 0 ? version.longValue() : null;
            JsonNode failure = root.path("failure");
            int number = failure.path("actionNumber").asInt(0);
            String type = failure.path("actionType").asText("");
            String code = failure.path("code").asText("");
            ActionFailure safeFailure = number >= 1 && number <= 16
                    && ACTION_TYPES.contains(type) && safeCode(code)
                ? new ActionFailure(number, type, code) : null;
            return new RuleExecutionEvidence(versionId, safeFailure);
        } catch (RuntimeException exception) {
            return new RuleExecutionEvidence(null, null);
        }
    }

    static ActionFailure classify(int actionNumber, String actionType, Exception failure) {
        String code = switch (failure) {
            case WorkflowExecutionException workflowFailure -> workflowFailure.code();
            case ForbiddenException ignored -> "permission_denied";
            case ResourceNotFoundException ignored -> "reference_unavailable";
            case BadRequestException ignored -> "configuration_invalid";
            case ConflictException ignored -> "state_conflict";
            default -> "execution_failed";
        };
        String type = actionType == null ? "" : actionType.trim().toLowerCase(Locale.ROOT);
        return new ActionFailure(actionNumber, ACTION_TYPES.contains(type) ? type : null,
            safeCode(code) ? code : "execution_failed");
    }

    static boolean safeCode(String code) {
        if (code == null) {
            return false;
        }
        if (FAILURE_CODES.contains(code)) {
            return true;
        }
        for (WorkflowDiagnosticCode diagnostic : WorkflowDiagnosticCode.values()) {
            if (diagnostic.value().startsWith("action_") && diagnostic.value().equals(code)) {
                return true;
            }
        }
        return false;
    }

    /** One-based position in the executed action list, independent of mutable draft node ids. */
    record ActionFailure(int actionNumber, String actionType, String code) { }
}
