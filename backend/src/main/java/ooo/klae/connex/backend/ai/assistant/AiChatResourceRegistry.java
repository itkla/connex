package ooo.klae.connex.backend.ai.assistant;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Per-turn stable positional handles for tenant-local CRM records. */
public final class AiChatResourceRegistry {
    private static final Set<String> KINDS = Set.of("person", "company", "deal");

    private final Map<String, ResourceRef> resources = new LinkedHashMap<>();
    private final Map<ResourceRef, String> handles = new LinkedHashMap<>();

    /** One server-only record identity behind a provider-visible handle. */
    public record ResourceRef(String kind, int id) {
        public ResourceRef {
            if (kind == null || !KINDS.contains(kind) || id <= 0) {
                throw new IllegalArgumentException("Assistant resource identity is invalid");
            }
        }
    }

    /** Allocates or returns the stable per-turn handle for one resource. */
    public String register(String kind, int id) {
        ResourceRef resource = new ResourceRef(kind, id);
        String existing = handles.get(resource);
        if (existing != null) {
            return existing;
        }
        String handle = "r" + (resources.size() + 1);
        handles.put(resource, handle);
        resources.put(handle, resource);
        return handle;
    }

    /** Resolves a known handle before any record service may be called. */
    public ResourceRef resolve(String handle) {
        ResourceRef resource = resources.get(handle);
        if (resource == null) {
            throw AiAssistantLoopException.malformed("unknown_handle");
        }
        return resource;
    }

    /** Resolves a handle and requires it to name one of the accepted record kinds. */
    public ResourceRef resolve(String handle, Set<String> acceptedKinds) {
        ResourceRef resource = resolve(handle);
        if (!acceptedKinds.contains(resource.kind())) {
            throw AiAssistantLoopException.malformed("wrong_handle_kind");
        }
        return resource;
    }

    /** Returns the fresh turn handle for one already-authorized resource identity. */
    public Optional<String> handleFor(String kind, int id) {
        return Optional.ofNullable(handles.get(new ResourceRef(kind, id)));
    }

    /** Requires every cited handle to resolve in this turn. */
    public void requireKnownCitations(Iterable<String> citations) {
        for (String citation : citations) {
            resolve(citation);
        }
    }

    /** @return immutable handle-to-resource snapshot for durable citation metadata */
    public Map<String, ResourceRef> snapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(resources));
    }
}
