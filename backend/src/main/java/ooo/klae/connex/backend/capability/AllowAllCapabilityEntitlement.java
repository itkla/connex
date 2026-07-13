package ooo.klae.connex.backend.capability;

import org.springframework.stereotype.Component;

/**
 * Default no-op entitlement that grants every capability, so the entitlement factor changes no
 * current behavior. A future licensing or billing implementation (issue #102) gates capabilities by
 * supplying its own {@link CapabilityEntitlement} bean annotated {@code @Primary}, which then wins
 * injection over this default.
 */
@Component
public class AllowAllCapabilityEntitlement implements CapabilityEntitlement {

    /**
     * Grants the requested capability.
     *
     * @param capability capability to evaluate
     * @return {@code true} for every capability
     */
    @Override
    public boolean isEntitled(Capability capability) {
        return true;
    }
}
