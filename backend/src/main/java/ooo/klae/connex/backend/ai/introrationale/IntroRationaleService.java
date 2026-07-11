package ooo.klae.connex.backend.ai.introrationale;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.ai.AiCompletionOutcome;
import ooo.klae.connex.backend.ai.AiFeatureGate;
import ooo.klae.connex.backend.ai.AiInvocation;
import ooo.klae.connex.backend.ai.AiInvocationService;
import ooo.klae.connex.backend.ai.masking.MaskedMessage;
import ooo.klae.connex.backend.ai.masking.MaskedPrompt;
import ooo.klae.connex.backend.ai.masking.MaskingContext;
import ooo.klae.connex.backend.ai.masking.MaskingLeakException;
import ooo.klae.connex.backend.ai.provider.AiProviderException;
import ooo.klae.connex.backend.dto.IntroRationaleDto;
import ooo.klae.connex.backend.dto.IntroSuggestionDto;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.services.IntroductionService;
import ooo.klae.connex.backend.services.WorkspaceService;

/**
 * Generates presentation-only introduction rationales through the audited AI invocation boundary.
 */
@Service
@RequiredArgsConstructor
public class IntroRationaleService {
    static final int MAX_TOKENS = 200;
    static final double TEMPERATURE = 0.2;
    static final int MAX_CACHE_ENTRIES = 256;
    static final Duration CACHE_TTL = Duration.ofMinutes(30);
    static final int RESOLVE_LIMIT = 50;

    private static final String FEATURE = "intro.rationale";
    private static final String NOT_CONFIGURED = "not_configured";
    private static final String PROVIDER_ERROR = "provider_error";
    private static final String NOT_A_SUGGESTION = "not_a_suggestion";

    private final IntroRationaleAssembler introRationaleAssembler;
    private final AiInvocationService aiInvocationService;
    private final AiFeatureGate aiFeatureGate;
    private final IntroductionService introductionService;
    private final WorkspaceService workspaceService;
    private final Clock clock;
    private final ConcurrentHashMap<String, CachedRationale> cache = new ConcurrentHashMap<>();

    /**
     * Generates or reuses a fresh rationale for a workspace-scoped introduction suggestion.
     * @param personAId first requested person id
     * @param personBId second requested person id
     * @return available rationale or a graceful unavailability response
     */
    public IntroRationaleDto generate(int personAId, int personBId) {
        int lo = Math.min(personAId, personBId);
        int hi = Math.max(personAId, personBId);
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        if (!aiFeatureGate.isAiUsable()) {
            return IntroRationaleDto.unavailable(lo, hi, NOT_CONFIGURED);
        }

        IntroSuggestionDto suggestion = introductionService.computeSuggestions(workspaceId, RESOLVE_LIMIT).stream()
                .filter(candidate -> candidate.getPersonAId() == lo && candidate.getPersonBId() == hi)
                .findFirst()
                .orElse(null);
        if (suggestion == null) {
            return IntroRationaleDto.unavailable(lo, hi, NOT_A_SUGGESTION);
        }

        IntroRationaleAssembly assembly = introRationaleAssembler.assemble(workspaceId, suggestion);
        String cacheKey = cacheKey(workspaceId, lo, hi, assembly.prompt(), assembly.context());
        Instant now = Instant.now(clock);
        IntroRationaleDto cached = cached(cacheKey, now);
        if (cached != null) {
            return cached;
        }

        try {
            AiCompletionOutcome outcome = aiInvocationService.complete(new AiInvocation(
                    FEATURE, assembly.context(), assembly.prompt(), MAX_TOKENS, TEMPERATURE));
            if (outcome.text().isBlank()) {
                return IntroRationaleDto.unavailable(lo, hi, PROVIDER_ERROR);
            }
            Instant generatedAt = Instant.now(clock);
            IntroRationaleDto rationale = IntroRationaleDto.of(
                    lo, hi, outcome.text(), generatedAt.toString(), outcome.demaskWarnings());
            cache(cacheKey, rationale, generatedAt.plus(CACHE_TTL));
            return rationale;
        } catch (MaskingLeakException | AiProviderException exception) {
            return IntroRationaleDto.unavailable(lo, hi, PROVIDER_ERROR);
        } catch (ForbiddenException exception) {
            return IntroRationaleDto.unavailable(lo, hi, NOT_CONFIGURED);
        }
    }

    private IntroRationaleDto cached(String key, Instant now) {
        CachedRationale cached = cache.get(key);
        if (cached == null) {
            return null;
        }
        if (now.isBefore(cached.expiresAt())) {
            return cached.rationale();
        }
        cache.remove(key, cached);
        return null;
    }

    private void cache(String key, IntroRationaleDto rationale, Instant expiresAt) {
        synchronized (cache) {
            cache.entrySet().removeIf(entry -> !Instant.now(clock).isBefore(entry.getValue().expiresAt()));
            if (!cache.containsKey(key) && cache.size() >= MAX_CACHE_ENTRIES) {
                cache.entrySet().stream()
                        .min(Map.Entry.comparingByValue(Comparator.comparing(CachedRationale::expiresAt)))
                        .ifPresent(entry -> cache.remove(entry.getKey(), entry.getValue()));
            }
            cache.put(key, new CachedRationale(rationale, expiresAt));
        }
    }

    private static String cacheKey(
            int workspaceId,
            int personAId,
            int personBId,
            MaskedPrompt prompt,
            MaskingContext context) {
        return workspaceId + ":" + personAId + ":" + personBId + ":" + contextHash(prompt, context);
    }

    private static String contextHash(MaskedPrompt prompt, MaskingContext context) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(serialized(prompt, context).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private static String serialized(MaskedPrompt prompt, MaskingContext context) {
        StringBuilder serialized = new StringBuilder();
        appendPart(serialized, prompt.getSystemPrompt());
        serialized.append(prompt.getMessages().size()).append(':');
        for (MaskedMessage message : prompt.getMessages()) {
            appendPart(serialized, message.getRole());
            appendPart(serialized, message.getContent());
        }
        List<Map.Entry<String, String>> bindings = context.tokenBindings();
        serialized.append(bindings.size()).append(':');
        for (Map.Entry<String, String> binding : bindings) {
            appendPart(serialized, binding.getKey());
            appendPart(serialized, binding.getValue());
        }
        return serialized.toString();
    }

    private static void appendPart(StringBuilder serialized, String value) {
        if (value == null) {
            serialized.append("-1:");
            return;
        }
        serialized.append(value.length()).append(':').append(value);
    }

    private record CachedRationale(IntroRationaleDto rationale, Instant expiresAt) {
    }
}
