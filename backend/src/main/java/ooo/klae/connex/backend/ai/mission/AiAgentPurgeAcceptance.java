package ooo.klae.connex.backend.ai.mission;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import ooo.klae.connex.backend.beans.AuditLog;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Immutable, versioned C1 authority retained by the strict organization audit ledger. */
public record AiAgentPurgeAcceptance(int auditId, int orgId, long requestId, String operationId,
        String idempotencyKey, List<Integer> workspaceIds, String acceptedAt, int schemaVersion,
        String scope, String cutoffPolicy, Long cutoffGeneration, Long invalidatedGeneration) {
    public static final String SCOPE = "organization_mission_holdings_v1";
    public static final String CUTOFF_POLICY = "contents_at_each_workspace_first_invalidation_v1";
    public static final String ASSISTANT_SCOPE = "organization_assistant_holdings_v2";
    public static final String ASSISTANT_CUTOFF_POLICY = "organization_generation_at_acceptance_v2";
    public static final String ACCEPTED = "appi.subject_request.mission_purge.accepted";
    public static final String COMPLETED = "appi.subject_request.mission_purge.completed";

    /** Preserves the original v1 constructor and its exact eight-field metadata envelope. */
    public AiAgentPurgeAcceptance(int auditId, int orgId, long requestId, String operationId,
            String idempotencyKey, List<Integer> workspaceIds, String acceptedAt) {
        this(auditId, orgId, requestId, operationId, idempotencyKey, workspaceIds, acceptedAt,
                1, SCOPE, CUTOFF_POLICY, null, null);
    }

    public AiAgentPurgeAcceptance {
        workspaceIds = List.copyOf(workspaceIds);
        if (workspaceIds.isEmpty() || workspaceIds.stream().anyMatch(id -> id == null || id <= 0)
                || !workspaceIds.equals(workspaceIds.stream().distinct().sorted().toList())) {
            throw new IllegalArgumentException("Invalid assistant erasure acceptance workspace list");
        }
        UUID.fromString(operationId);
        UUID.fromString(idempotencyKey);
        if (!acknowledges(scope, cutoffPolicy) || (schemaVersion == 1
                ? !SCOPE.equals(scope) || cutoffGeneration != null || invalidatedGeneration != null
                : schemaVersion != 2 || !ASSISTANT_SCOPE.equals(scope) || cutoffGeneration == null
                    || cutoffGeneration < 0 || cutoffGeneration == Long.MAX_VALUE
                    || invalidatedGeneration == null || invalidatedGeneration != cutoffGeneration + 1)) {
            throw new IllegalArgumentException("Invalid assistant erasure acceptance contract");
        }
    }

    /** Accepts only the two explicitly authorized scope and cutoff-policy pairs. */
    public static boolean acknowledges(String scope, String policy) {
        return SCOPE.equals(scope) && CUTOFF_POLICY.equals(policy)
                || ASSISTANT_SCOPE.equals(scope) && ASSISTANT_CUTOFF_POLICY.equals(policy);
    }

    /** Prevents same-key replay from silently broadening or changing accepted authority. */
    public void requireAcknowledgments(String requestedScope, String requestedPolicy) {
        if (!scope.equals(requestedScope) || !cutoffPolicy.equals(requestedPolicy)) {
            throw new ooo.klae.connex.backend.exceptions.ConflictException("dsr_scope_conflict");
        }
    }

    /** Supplies the exact envelope that must survive strict audit sanitation unchanged. */
    public Map<String, Object> metadata() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("schemaVersion", schemaVersion);
        value.put("operationId", operationId);
        value.put("idempotencyKey", idempotencyKey);
        value.put("requestId", requestId);
        value.put("scope", scope);
        value.put("cutoffPolicy", cutoffPolicy);
        value.put("workspaceIds", workspaceIds);
        value.put("acceptedAt", acceptedAt);
        if (schemaVersion == 2) {
            value.put("cutoffGeneration", Long.toString(cutoffGeneration));
            value.put("invalidatedGeneration", Long.toString(invalidatedGeneration));
        }
        return Map.copyOf(value);
    }

    /** Parses only the closed accepted envelopes, rejecting corrupt authority instead of guessing. */
    public static AiAgentPurgeAcceptance from(AuditLog row, ObjectMapper json) {
        if (row == null || row.getOrgId() == null || !ACCEPTED.equals(row.getAction())) {
            throw new IllegalStateException("Assistant erasure acceptance is unavailable");
        }
        JsonNode value = json.readTree(row.getChanges());
        int version = value.path("schemaVersion").asInt();
        if (!value.isObject() || !value.path("schemaVersion").isIntegralNumber()
                || (version != 1 && version != 2) || value.size() != (version == 1 ? 8 : 10)
                || !value.path("workspaceIds").isArray() || !value.path("requestId").isIntegralNumber()
                || !value.path("requestId").canConvertToLong()
                || !value.path("operationId").isString() || !value.path("idempotencyKey").isString()
                || !value.path("acceptedAt").isString()) {
            throw new IllegalStateException("Assistant erasure acceptance has an unknown contract");
        }
        java.util.ArrayList<Integer> workspaces = new java.util.ArrayList<>();
        for (JsonNode id : value.path("workspaceIds")) {
            if (!id.isIntegralNumber() || !id.canConvertToInt() || id.asInt() <= 0) {
                throw new IllegalStateException("Assistant erasure acceptance has invalid placement");
            }
            workspaces.add(id.asInt());
        }
        return new AiAgentPurgeAcceptance(row.getId(), row.getOrgId(), value.path("requestId").asLong(),
                value.path("operationId").asString(), value.path("idempotencyKey").asString(),
                workspaces, value.path("acceptedAt").asString(), version, value.path("scope").asString(),
                value.path("cutoffPolicy").asString(), version == 2 ? generation(value, "cutoffGeneration") : null,
                version == 2 ? generation(value, "invalidatedGeneration") : null);
    }

    private static long generation(JsonNode value, String field) {
        JsonNode number = value.path(field);
        if (!number.isString() || !number.asString().matches("0|[1-9][0-9]{0,18}")) {
            throw new IllegalStateException("Assistant erasure acceptance has an invalid generation");
        }
        try {
            return Long.parseLong(number.asString());
        } catch (NumberFormatException failure) {
            throw new IllegalStateException("Assistant erasure acceptance generation is out of range", failure);
        }
    }
}
