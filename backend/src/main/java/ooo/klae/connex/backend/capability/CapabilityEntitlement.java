package ooo.klae.connex.backend.capability;

/**
 * Determines whether a capability is licensed or paid for on this instance. The default grants
 * every capability so the entitlement factor denies nothing and preserves current behavior.
 * Future SaaS billing and on-premises signed-license deployments may supply alternative beans as
 * specified by issue #102.
 */
@FunctionalInterface
public interface CapabilityEntitlement {

    /**
     * Returns whether the instance is entitled to the requested capability.
     *
     * @param capability capability to evaluate
     * @return {@code true} when the capability is licensed or paid for on this instance
     */
    boolean isEntitled(Capability capability);
}
