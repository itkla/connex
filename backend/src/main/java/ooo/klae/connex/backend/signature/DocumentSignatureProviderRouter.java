package ooo.klae.connex.backend.signature;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.springframework.stereotype.Service;

import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;

/** Resolves a document-signature provider key to exactly one installed adapter. */
@Service
public class DocumentSignatureProviderRouter {
    private final Map<String, DocumentSignatureProvider> adapters;

    /** Builds the provider index and refuses duplicate or blank keys. */
    public DocumentSignatureProviderRouter(List<DocumentSignatureProvider> providers) {
        Objects.requireNonNull(providers, "providers");
        Map<String, DocumentSignatureProvider> indexed = new LinkedHashMap<>();
        for (DocumentSignatureProvider provider : providers) {
            Objects.requireNonNull(provider, "provider");
            String key = provider.key();
            if (key == null || key.isBlank()) {
                throw new IllegalStateException("Document-signature provider key is required");
            }
            if (indexed.putIfAbsent(key, provider) != null) {
                throw new IllegalStateException("Duplicate document-signature provider " + key);
            }
        }
        adapters = Map.copyOf(indexed);
    }

    /** Returns the installed adapter or fails closed without provider fallback. */
    public DocumentSignatureProvider adapterFor(String provider) {
        DocumentSignatureProvider adapter = provider == null ? null : adapters.get(provider);
        if (adapter == null) {
            throw new ResourceNotFoundException("Document-signature provider was not found");
        }
        return adapter;
    }

    /** Routes authenticated webhook parsing to the requested adapter. */
    public Optional<ProviderEvent> parseWebhook(
            String provider, Map<String, String> headers, byte[] body) {
        return adapterFor(provider).parseWebhook(provider, headers, body);
    }
}
