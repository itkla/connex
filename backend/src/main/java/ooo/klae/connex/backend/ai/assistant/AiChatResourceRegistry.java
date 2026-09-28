package ooo.klae.connex.backend.ai.assistant;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import ooo.klae.connex.backend.ai.AiPrivacyMode;
import ooo.klae.connex.backend.ai.masking.MaskingContext;

/** Per-turn stable positional handles for tenant-local CRM records. */
public final class AiChatResourceRegistry {
    private static final Set<String> KINDS = Set.of("person", "company", "deal");

    private final Map<String, ResourceRef> resources = new LinkedHashMap<>();
    private final Map<ResourceRef, String> handles = new LinkedHashMap<>();
    private final MaskingContext maskingContext;
    private int lastIssued;

    /** Creates a registry without a shared masked request context. */
    public AiChatResourceRegistry() {
        this(new MaskingContext(AiPrivacyMode.UNMASKED));
    }

    AiChatResourceRegistry(MaskingContext maskingContext) {
        this.maskingContext = java.util.Objects.requireNonNull(maskingContext, "maskingContext");
    }

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
        lastIssued++;
        String handle = "r" + lastIssued;
        handles.put(resource, handle);
        resources.put(handle, resource);
        return handle;
    }

    /** Resolves a known handle before any record service may be called. */
    public ResourceRef resolve(String handle) {
        ResourceRef resource = resources.get(handle);
        if (resource == null) {
            throw AiAssistantLoopException.refusedArguments("unknown_handle");
        }
        return resource;
    }

    /** Resolves a handle and requires it to name one of the accepted record kinds. */
    public ResourceRef resolve(String handle, Set<String> acceptedKinds) {
        ResourceRef resource = resolve(handle);
        if (!acceptedKinds.contains(resource.kind())) {
            throw AiAssistantLoopException.refusedArguments("wrong_handle_kind");
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

    /**
     * Copies the handles issued so far into a registry later registrations do not reach.
     *
     * <p>A batched step's calls were all emitted against the handles the model had been shown
     * when it made the step's one decision. Resolving a later call's references against this copy
     * rather than against the live registry keeps a handle an earlier call of the same batch has
     * just minted — one the model never saw and could only have guessed — as unknown as it would be
     * for that call emitted alone.
     *
     * @return an independent copy resolving exactly the handles issued before this call
     */
    AiChatResourceRegistry issued() {
        AiChatResourceRegistry copy = new AiChatResourceRegistry(maskingContext);
        copy.resources.putAll(resources);
        copy.handles.putAll(handles);
        copy.lastIssued = lastIssued;
        return copy;
    }

    /**
     * Marks the handles issued so far, so {@link #restore} can later withdraw every handle issued
     * after this point.
     *
     * @return a checkpoint only this registry accepts
     */
    Checkpoint checkpoint() {
        return new Checkpoint(this, resources.size());
    }

    /**
     * Withdraws every handle issued after a checkpoint, so the registry resolves exactly the
     * handles it resolved when the checkpoint was taken.
     *
     * <p>A batched step abandoned part-way leaves the replay with none of its results, so the
     * handles its executed calls minted name records the model was never shown. Left issued, a
     * closing answer could cite or link one of them — they are short and sequential, so it only
     * has to guess — and final citation validation would accept it. Handles are issued in order and
     * never withdrawn any other way, so the handles issued after a checkpoint are exactly the
     * entries past its position.
     *
     * <p>The issue counter is deliberately not rewound. A withdrawn handle is still named by the
     * abandoned calls' committed durable rows and can still be guessed, so it must stay unknown for
     * the rest of the turn rather than be issued again for a different record.
     *
     * @param checkpoint a checkpoint this registry issued
     * @throws IllegalArgumentException when the checkpoint belongs to another registry, or names
     *     more handles than this registry still holds because an earlier restore withdrew them
     */
    void restore(Checkpoint checkpoint) {
        Objects.requireNonNull(checkpoint, "checkpoint");
        if (checkpoint.registry != this || checkpoint.issued > resources.size()) {
            throw new IllegalArgumentException(
                    "Assistant resource checkpoint does not belong to this registry");
        }
        Iterator<Map.Entry<String, ResourceRef>> issued = resources.entrySet().iterator();
        int kept = 0;
        while (issued.hasNext()) {
            Map.Entry<String, ResourceRef> entry = issued.next();
            if (kept < checkpoint.issued) {
                kept++;
                continue;
            }
            handles.remove(entry.getValue());
            issued.remove();
        }
    }

    MaskingContext maskingContext() {
        return maskingContext;
    }

    /** A position in one registry's issue order, which only that registry can restore to. */
    static final class Checkpoint {
        private final AiChatResourceRegistry registry;
        private final int issued;

        private Checkpoint(AiChatResourceRegistry registry, int issued) {
            this.registry = registry;
            this.issued = issued;
        }
    }
}
