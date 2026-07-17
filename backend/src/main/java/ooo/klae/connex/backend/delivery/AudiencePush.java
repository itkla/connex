package ooo.klae.connex.backend.delivery;

import java.util.List;
import java.util.Objects;

/**
 * An audience-sync request handed to a connector: the external list to synchronize into and the
 * eligible members to add. The members have already passed a fresh eligibility re-check at the export
 * choke point, so a connector pushes them verbatim.
 * @param externalListId the connector-side list identifier the members belong to
 * @param members the eligible members to push
 */
public record AudiencePush(String externalListId, List<AudienceMember> members) {

    public AudiencePush {
        members = List.copyOf(Objects.requireNonNull(members, "members"));
    }
}
