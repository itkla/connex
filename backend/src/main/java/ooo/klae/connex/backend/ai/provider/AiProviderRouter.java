package ooo.klae.connex.backend.ai.provider;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Service;

/**
 * Routes an organization-resolved provider id to its installed adapter.
 */
@Service
public class AiProviderRouter {
    private final Map<String, AiProvider> adapters;

    /**
     * Builds the provider adapter index and rejects duplicate provider ids.
     * @param providers installed provider adapters
     */
    public AiProviderRouter(List<AiProvider> providers) {
        Objects.requireNonNull(providers, "providers");
        Map<String, AiProvider> indexed = new LinkedHashMap<>();
        for (AiProvider provider : providers) {
            Objects.requireNonNull(provider, "provider");
            String providerId = provider.providerId();
            if (providerId == null || providerId.isBlank()) {
                throw new IllegalStateException("AI provider adapter id is required");
            }
            if (indexed.putIfAbsent(providerId, provider) != null) {
                throw new IllegalStateException("Duplicate AI provider adapter id " + providerId);
            }
        }
        adapters = Map.copyOf(indexed);
    }

    /**
     * Returns the adapter installed for a provider id.
     * @param provider provider id
     * @return installed provider adapter
     * @throws AiProviderException when no adapter is installed
     */
    public AiProvider adapterFor(String provider) {
        if (provider == null || provider.isBlank()) {
            throw new AiProviderException("No adapter for provider " + provider);
        }
        AiProvider adapter = adapters.get(provider);
        if (adapter == null) {
            throw new AiProviderException("No adapter for provider " + provider);
        }
        return adapter;
    }
}
