package ooo.klae.connex.backend.delivery;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.mappers.CampaignDeliveryMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;

/**
 * Reserves a contact/channel window under the workspace root before provider egress. The short
 * transaction orders workspace before delivery and commits before dispatch resumes. Reservations
 * survive ambiguous outcomes and every provider rejection that did not prove absence of submission;
 * only a rejection the adapter proved happened before egress releases one.
 * The reservation includes the full provider deadline so an uncertain submission cannot expire
 * from the frequency window while its bounded provider call could still have been in flight.
 */
@Service
@RequiredArgsConstructor
public class CampaignFrequencyAdmissionService {

    private final WorkspaceMapper workspaceMapper;
    private final CampaignDeliveryMapper deliveryMapper;
    private final DeliveryProperties deliveryProperties;

    /**
     * Result of checking the frequency policy and the still-owned delivery claim.
     *
     * <p>{@link #CLAIM_LOST} and {@link #REFUSED} are deliberately distinct. A lost claim belongs to
     * another owner or has already reached a terminal state, so this worker must not write to it. A
     * refusal is a claim this worker still owns that cannot be admitted — a suspended workspace, a
     * send that stopped being dispatchable, or a recipient that is no longer resolvable — and the
     * caller must terminate the row instead of leaving it {@code dispatching} forever, because an
     * audience delivery has no lease and its only recovery sweep keys on an expired reservation,
     * which a refused claim never wrote, while its unique send/person key blocks any replacement row.
     * The sweep runs in its own auto-commit statement, never inside this transaction.
     */
    public enum Admission {
        RESERVED, CAPPED, CLAIM_LOST, REFUSED
    }

    /** Atomically checks history and reserves a still-owned claim within the requested window. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Admission reserve(int workspaceId, int deliveryId, int personId, String channel,
            String leaseOwner, int windowHours) {
        if (workspaceMapper.lockActiveIdentity(workspaceId) == null) {
            return unreserved(workspaceId, deliveryId, leaseOwner);
        }
        if (deliveryMapper.frequencyConflictCount(workspaceId, personId, channel, deliveryId,
                LocalDateTime.now(ZoneOffset.UTC).minusHours(windowHours)) > 0) {
            return Admission.CAPPED;
        }
        if (deliveryMapper.reserveFrequencyWindow(
                workspaceId, deliveryId, personId, channel, leaseOwner,
                deliveryProperties.providerCallDeadline().toNanos() / 1_000L) == 1) {
            return Admission.RESERVED;
        }
        return unreserved(workspaceId, deliveryId, leaseOwner);
    }

    private Admission unreserved(int workspaceId, int deliveryId, String leaseOwner) {
        return deliveryMapper.claimStillOwned(workspaceId, deliveryId, leaseOwner)
                ? Admission.REFUSED : Admission.CLAIM_LOST;
    }
}
