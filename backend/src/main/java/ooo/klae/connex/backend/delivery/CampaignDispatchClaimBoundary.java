package ooo.klae.connex.backend.delivery;

import org.springframework.stereotype.Component;

/** Boundary invoked after a delivery claim and before any provider-visible work. */
@Component
public class CampaignDispatchClaimBoundary {

    /**
     * Separates the durable claim from the final rollout-fence and provider-egress checks.
     *
     * @param workspaceId owning workspace
     * @param deliveryId claimed delivery
     */
    public void afterClaim(int workspaceId, int deliveryId) {
    }

    /**
     * Separates capture of the absolute provider deadline from the lease-renewal write.
     * @param workspaceId owning workspace
     * @param deliveryId claimed delivery
     */
    public void beforeProviderLeaseRenewal(int workspaceId, int deliveryId) {
    }
}
