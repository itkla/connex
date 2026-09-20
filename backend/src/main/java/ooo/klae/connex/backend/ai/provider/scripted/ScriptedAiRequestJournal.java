package ooo.klae.connex.backend.ai.provider.scripted;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

import ooo.klae.connex.backend.ai.provider.AiCompletionRequest;

/**
 * Bounded in-JVM record of the requests the scripted provider received.
 *
 * <p>Exists so a same-JVM test can assert what would actually have left for a provider — that the
 * outbound prompt carries placeholders and handles rather than raw names, that an injected tool
 * result travels inside the untrusted-data delimiters, that a refusal happened before any egress.
 * Those are properties of the request, and there is no other honest place to observe them.
 *
 * <p>It is deliberately inert as a data surface: bounded to {@value #MAX_ENTRIES} entries with
 * oldest-eviction, never persisted, never logged, never projected into a DTO and never reachable
 * over any transport. {@code ScriptedAiProviderArchTest} fails the build if any of that changes,
 * and the whole class can only exist behind the scripted profile and its flag.
 */
public class ScriptedAiRequestJournal {

    /** Retained request ceiling; the oldest entry is evicted beyond it. */
    public static final int MAX_ENTRIES = 64;

    private final Deque<AiCompletionRequest> entries = new ArrayDeque<>();

    /**
     * Appends one received request, evicting the oldest beyond the retention ceiling.
     * @param request the request the provider received
     */
    public synchronized void record(AiCompletionRequest request) {
        Objects.requireNonNull(request, "request");
        entries.addLast(request);
        while (entries.size() > MAX_ENTRIES) {
            entries.removeFirst();
        }
    }

    /** @return immutable copy of the retained requests, oldest first */
    public synchronized List<AiCompletionRequest> recorded() {
        return List.copyOf(entries);
    }

    /** Discards every retained request. */
    public synchronized void clear() {
        entries.clear();
    }
}
