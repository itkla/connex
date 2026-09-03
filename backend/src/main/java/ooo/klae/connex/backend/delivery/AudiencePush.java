package ooo.klae.connex.backend.delivery;

import java.util.List;
import java.util.Objects;

/**
 * An audience-sync request handed to a connector: the external list to synchronize into and the
 * eligible members to add. The members have already passed a fresh eligibility re-check at the export
 * choke point, so a connector pushes them verbatim.
 * @param externalListId the connector-side list identifier the members belong to
 * @param members the eligible members to push
 * @param idempotencyKey the export-attempt key a connector passes to its provider, or null for a
 *     legacy caller
 */
public record AudiencePush(String externalListId, List<AudienceMember> members, String idempotencyKey) {

    /**
     * Backward-compatible constructor for connector callers that predate provider idempotency.
     * @param externalListId the connector-side list identifier
     * @param members the eligible members to push
     */
    public AudiencePush(String externalListId, List<AudienceMember> members) {
        this(externalListId, members, null);
    }

    public AudiencePush {
        members = List.copyOf(Objects.requireNonNull(members, "members"));
        if (idempotencyKey != null && idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey must be non-blank when present");
        }
    }
}
