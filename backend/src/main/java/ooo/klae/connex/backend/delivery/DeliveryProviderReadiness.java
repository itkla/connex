package ooo.klae.connex.backend.delivery;

/**
 * Returns true only when a workspace has a usable delivery configuration for a channel. Callers
 * treat missing readiness as false and fail closed.
 */
public interface DeliveryProviderReadiness {

    /**
     * Whether the workspace can deliver on the given channel.
     * @param workspaceId the workspace
     * @param channel the delivery channel
     * @return true when a usable delivery configuration exists
     */
    boolean isReady(int workspaceId, DeliveryChannel channel);
}
