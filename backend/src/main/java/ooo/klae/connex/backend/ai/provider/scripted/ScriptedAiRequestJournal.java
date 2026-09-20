package ooo.klae.connex.backend.ai.provider.scripted;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import ooo.klae.connex.backend.ai.provider.AiCompletionRequest;
import ooo.klae.connex.backend.ai.provider.AiCredentials;
import ooo.klae.connex.backend.ai.provider.AiProviderAttemptExecutor;

/**
 * Bounded in-JVM record of the requests the scripted provider was handed.
 *
 * <p>Exists so a same-JVM test can assert what would actually have left for a provider — that the
 * outbound prompt carries placeholders and handles rather than raw names, and that an injected tool
 * result travels inside the untrusted-data delimiters. Those are properties of the request, and
 * there is no other honest place to observe them.
 *
 * <p><b>What is retained is a redaction, not the live request.</b> The request the provider
 * receives carries the organization's decrypted BYOP credentials, any embedded image bytes and the
 * live attempt executor, which holds the invocation's budget lease and serialized prompt. None of
 * those are prompt material and none of them belong in a buffer that outlives the call, so
 * {@link #record(AiCompletionRequest)} stores a copy whose credentials are empty, whose images are
 * dropped and whose executor is the inert direct one. Target, system prompt, messages, schema,
 * native tools and reasoning mode — the prompt material the assertions are about — are retained
 * verbatim.
 *
 * <p><b>Every request is recorded; only some are dispatched.</b> The provider records before the
 * attempt executor's egress seam runs, because the restriction epoch, the feature gate, the
 * provider guard and the admission commitment can all still refuse the attempt inside that seam.
 * An entry therefore means "the provider was handed this", and {@link Entry#dispatched()} — set
 * beside {@code beforeSend()}, at the point the real adapter writes bytes — means "this left".
 * An assertion about egress must read {@link #dispatched()}; an assertion about a refusal that
 * happened above the provider reads that {@link #recorded()} is empty.
 *
 * <p>It is deliberately inert as a data surface: bounded to {@value #MAX_ENTRIES} entries with
 * oldest-eviction, never persisted, never logged, never projected into a DTO and never reachable
 * over any transport. {@code ScriptedAiProviderArchTest} fails the build if any of that changes,
 * and the whole class can only exist behind the scripted profile and its flag.
 */
public class ScriptedAiRequestJournal {

    /** Retained request ceiling; the oldest entry is evicted beyond it. */
    public static final int MAX_ENTRIES = 64;

    private final Deque<Entry> entries = new ArrayDeque<>();

    /**
     * Appends one received request as a redaction, evicting the oldest beyond the ceiling.
     * @param request the request the provider received
     * @return the appended entry, so the provider can mark it dispatched at the send point
     */
    public synchronized Entry record(AiCompletionRequest request) {
        Objects.requireNonNull(request, "request");
        Entry entry = new Entry(redact(request));
        entries.addLast(entry);
        while (entries.size() > MAX_ENTRIES) {
            entries.removeFirst();
        }
        return entry;
    }

    /** @return immutable copy of every retained entry, oldest first */
    public synchronized List<Entry> recorded() {
        return List.copyOf(entries);
    }

    /** @return the retained requests whose bytes were dispatched, oldest first */
    public synchronized List<AiCompletionRequest> dispatched() {
        return entries.stream().filter(Entry::dispatched).map(Entry::request).toList();
    }

    /** Discards every retained entry. */
    public synchronized void clear() {
        entries.clear();
    }

    private static AiCompletionRequest redact(AiCompletionRequest request) {
        return new AiCompletionRequest(
                request.target(),
                AiCredentials.of(Map.of()),
                request.systemPrompt(),
                request.messages(),
                List.of(),
                request.outputMode(),
                request.responseSchema(),
                request.nativeTools(),
                request.reasoningMode(),
                AiProviderAttemptExecutor.DIRECT,
                request.maxTokens(),
                request.temperature());
    }

    /** One journaled request and whether the provider went on to dispatch it. */
    public static final class Entry {

        private final AiCompletionRequest request;
        private volatile boolean dispatched;

        private Entry(AiCompletionRequest request) {
            this.request = request;
        }

        /** @return the redacted request the provider was handed */
        public AiCompletionRequest request() {
            return request;
        }

        /** @return whether the provider reached its send point for this request */
        public boolean dispatched() {
            return dispatched;
        }

        void markDispatched() {
            dispatched = true;
        }
    }
}
