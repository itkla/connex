package ooo.klae.connex.backend.delivery;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Service;

/**
 * Routes a resolved provider id to its installed adapter and narrows it to a requested capability,
 * failing closed when no adapter is installed or the adapter lacks the capability.
 */
@Service
public class DeliveryProviderRouter {
    private final Map<String, DeliveryProvider> adapters;

    /**
     * Builds the provider adapter index and rejects duplicate provider ids.
     * @param providers installed provider adapters
     */
    public DeliveryProviderRouter(List<DeliveryProvider> providers) {
        Objects.requireNonNull(providers, "providers");
        Map<String, DeliveryProvider> indexed = new LinkedHashMap<>();
        for (DeliveryProvider provider : providers) {
            Objects.requireNonNull(provider, "provider");
            String providerId = provider.providerId();
            if (providerId == null || providerId.isBlank()) {
                throw new IllegalStateException("Delivery provider adapter id is required");
            }
            if (indexed.putIfAbsent(providerId, provider) != null) {
                throw new IllegalStateException("Duplicate delivery provider adapter id " + providerId);
            }
        }
        adapters = Map.copyOf(indexed);
    }

    /**
     * Returns the adapter installed for a provider id.
     * @param provider provider id
     * @return installed provider adapter
     * @throws DeliveryProviderException when no adapter is installed
     */
    public DeliveryProvider adapterFor(String provider) {
        if (provider == null || provider.isBlank()) {
            throw new DeliveryProviderException("No adapter for provider " + provider);
        }
        DeliveryProvider adapter = adapters.get(provider);
        if (adapter == null) {
            throw new DeliveryProviderException("No adapter for provider " + provider);
        }
        return adapter;
    }

    /**
     * Returns the message dispatcher installed for a provider id.
     * @param provider provider id
     * @return installed message dispatcher
     * @throws DeliveryProviderException when no adapter is installed or it cannot dispatch messages
     */
    public MessageDispatcher dispatcherFor(String provider) {
        DeliveryProvider adapter = adapterFor(provider);
        if (!(adapter instanceof MessageDispatcher dispatcher)) {
            throw new DeliveryProviderException("Provider " + provider + " cannot dispatch messages");
        }
        return dispatcher;
    }

    /**
     * Returns the event source installed for a provider id.
     * @param provider provider id
     * @return installed provider event source
     * @throws DeliveryProviderException when no adapter is installed or it emits no provider events
     */
    public ProviderEventSource eventSourceFor(String provider) {
        DeliveryProvider adapter = adapterFor(provider);
        if (!(adapter instanceof ProviderEventSource eventSource)) {
            throw new DeliveryProviderException("Provider " + provider + " emits no delivery events");
        }
        return eventSource;
    }
}
