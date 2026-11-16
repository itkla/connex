package ooo.klae.connex.backend.services;

import java.util.List;
import java.util.Set;

/**
 * What a role edit's confinement check remembers from before the write (#1534): the organization
 * the edit grants privilege in, the accounts it can affect, and which of them were privileged there
 * already. An empty baseline watches nothing, as when attestation is not enforced.
 *
 * @param orgId the organization the edit grants privilege in
 * @param userIds the accounts the edit can affect
 * @param privilegedBefore those of them privileged in the organization before the write
 */
public record ConfinementBaseline(int orgId, List<Integer> userIds, Set<Integer> privilegedBefore) {
    /** A baseline that watches nothing. */
    public static final ConfinementBaseline NONE = new ConfinementBaseline(0, List.of(), Set.of());

    /** @return true when the check has nothing to compare */
    public boolean watchesNothing() {
        return userIds.isEmpty();
    }
}
