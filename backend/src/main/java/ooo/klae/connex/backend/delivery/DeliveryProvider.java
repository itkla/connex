package ooo.klae.connex.backend.delivery;

import java.util.Set;

/**
 * Base contract for an installed outbound delivery provider. Narrow capability sub-interfaces
 * ({@link MessageDispatcher}, {@link AudienceSyncConnector}, {@link ProviderEventSource}) add the
 * behaviours a provider actually supports; this base only identifies the provider and declares its
 * channels and capabilities.
 */
public interface DeliveryProvider {

    /**
     * The stable provider id used to route sends to this adapter.
     * @return the provider id
     */
    String providerId();

    /**
     * The channels this provider serves.
     * @return the supported channels
     */
    Set<DeliveryChannel> channels();

    /**
     * The static capability declaration for this provider.
     * @return the provider capabilities
     */
    DeliveryCapabilities capabilities();
}
