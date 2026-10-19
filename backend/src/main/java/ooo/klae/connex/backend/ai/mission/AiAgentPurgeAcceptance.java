package ooo.klae.connex.backend.ai.mission;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import ooo.klae.connex.backend.beans.AuditLog;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Immutable, metadata-only C1 authority retained by the strict organization audit ledger. */
public record AiAgentPurgeAcceptance(int auditId, int orgId, long requestId, String operationId,
        String idempotencyKey, List<Integer> workspaceIds, String acceptedAt) {
    public static final String SCOPE = "organization_mission_holdings_v1";
    public static final String CUTOFF_POLICY = "contents_at_each_workspace_first_invalidation_v1";
    public static final String ACCEPTED = "appi.subject_request.mission_purge.accepted";
    public static final String COMPLETED = "appi.subject_request.mission_purge.completed";

    public AiAgentPurgeAcceptance {
        workspaceIds = List.copyOf(workspaceIds);
        if (workspaceIds.isEmpty() || workspaceIds.stream().anyMatch(id -> id == null || id <= 0)
                || !workspaceIds.equals(workspaceIds.stream().distinct().sorted().toList())) {
            throw new IllegalArgumentException("Invalid mission purge acceptance workspace list");
        }
        UUID.fromString(operationId);
        UUID.fromString(idempotencyKey);
    }

    /** Supplies the exact envelope that must survive strict audit sanitation unchanged. */
    public Map<String, Object> metadata() {
        return Map.of("schemaVersion", 1, "operationId", operationId, "idempotencyKey", idempotencyKey,
                "requestId", requestId, "scope", SCOPE, "cutoffPolicy", CUTOFF_POLICY,
                "workspaceIds", workspaceIds, "acceptedAt", acceptedAt);
    }

    /** Parses only the closed accepted envelope, rejecting corrupt authority instead of guessing. */
    public static AiAgentPurgeAcceptance from(AuditLog row, ObjectMapper json) {
        if (row == null || row.getOrgId() == null || !ACCEPTED.equals(row.getAction())) {
            throw new IllegalStateException("Mission purge acceptance is unavailable");
        }
        JsonNode value = json.readTree(row.getChanges());
        if (!value.isObject() || value.size() != 8 || value.path("schemaVersion").asInt() != 1
                || !SCOPE.equals(value.path("scope").asString())
                || !CUTOFF_POLICY.equals(value.path("cutoffPolicy").asString())
                || !value.path("workspaceIds").isArray() || !value.path("requestId").isIntegralNumber()) {
            throw new IllegalStateException("Mission purge acceptance has an unknown contract");
        }
        java.util.ArrayList<Integer> workspaces = new java.util.ArrayList<>();
        for (JsonNode id : value.path("workspaceIds")) {
            if (!id.isIntegralNumber() || !id.canConvertToInt() || id.asInt() <= 0) {
                throw new IllegalStateException("Mission purge acceptance has invalid placement");
            }
            workspaces.add(id.asInt());
        }
        return new AiAgentPurgeAcceptance(row.getId(), row.getOrgId(), value.path("requestId").asLong(),
                value.path("operationId").asString(), value.path("idempotencyKey").asString(),
                workspaces, value.path("acceptedAt").asString());
    }
}
