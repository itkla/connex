package ooo.klae.connex.backend.delivery;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.mappers.CampaignDeliveryMapper;

/**
 * Commits an attempt's digest before provider submission. The short transaction locks only the
 * delivery, revalidates its claim on the database clock, then inserts the child digest. Recovery
 * and cascade deletion cannot pass the parent lock until registration commits or rolls back.
 * Raw unsubscribe bearers never enter this service.
 */
@Service
@RequiredArgsConstructor
public class CampaignUnsubscribeTokenRegistrationService {

    private final CampaignDeliveryMapper deliveryMapper;

    /**
     * Registers a SHA-256 digest for a still-owned delivery claim without replacing earlier digests.
     *
     * @param workspaceId the resolved workspace
     * @param deliveryId the claimed delivery
     * @param leaseOwner the triggered owner, or null for an audience claim
     * @param tokenHash the lowercase hexadecimal SHA-256 digest, never the bearer
     * @return false if recovery, expiry, or deletion has taken the claim
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public boolean register(int workspaceId, int deliveryId, String leaseOwner, String tokenHash) {
        if (tokenHash == null || !tokenHash.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("Invalid unsubscribe token digest");
        }
        if (deliveryMapper.getDeliveryForUpdate(workspaceId, deliveryId) == null
                || !deliveryMapper.claimStillOwned(workspaceId, deliveryId, leaseOwner)) {
            return false;
        }
        if (deliveryMapper.insertUnsubscribeTokenHash(workspaceId, deliveryId, tokenHash) != 1) {
            throw new IllegalStateException("Unsubscribe token registration failed");
        }
        return true;
    }
}
