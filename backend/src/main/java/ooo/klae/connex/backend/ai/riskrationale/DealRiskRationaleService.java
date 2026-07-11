package ooo.klae.connex.backend.ai.riskrationale;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
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
import ooo.klae.connex.backend.dto.DealRationaleDto;
import ooo.klae.connex.backend.dto.DealRiskDto;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.services.DealRiskService;
import ooo.klae.connex.backend.services.WorkspaceService;

/**
 * Generates presentation-only deal-risk rationales through the audited AI invocation boundary.
 */
@Service
@RequiredArgsConstructor
public class DealRiskRationaleService {
    static final int MAX_TOKENS = 350;
    static final double TEMPERATURE = 0.2;
    static final int MAX_CACHE_ENTRIES = 256;
    static final Duration CACHE_TTL = Duration.ofMinutes(30);

    private static final String FEATURE = "deal.risk_rationale";
    private static final String NOT_CONFIGURED = "not_configured";
    private static final String PROVIDER_ERROR = "provider_error";
    private static final String NOT_AT_RISK = "not_at_risk";

    private final DealRiskRationaleAssembler dealRiskRationaleAssembler;
    private final AiInvocationService aiInvocationService;
    private final AiFeatureGate aiFeatureGate;
    private final DealRiskService dealRiskService;
    private final WorkspaceService workspaceService;
    private final Clock clock;
    private final ConcurrentHashMap<String, CachedRationale> cache = new ConcurrentHashMap<>();

    /**
     * Generates or reuses a fresh rationale for a workspace-scoped at-risk deal.
     * @param dealId deal whose deterministic risk signals should be explained
     * @return available rationale or a graceful unavailability response
     */
    public DealRationaleDto generate(int dealId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        if (!aiFeatureGate.isAiUsable()) {
            return DealRationaleDto.unavailable(dealId, NOT_CONFIGURED);
        }

        DealRiskDto risk = dealRiskService.assessDeal(workspaceId, dealId);
        if (risk == null || "none".equals(risk.getLevel())
                || risk.getFactors() == null || risk.getFactors().isEmpty()) {
            return DealRationaleDto.unavailable(dealId, NOT_AT_RISK);
        }

        RationaleAssembly assembly = dealRiskRationaleAssembler.assemble(workspaceId, dealId, risk);
        String cacheKey = cacheKey(workspaceId, dealId, assembly.prompt(), assembly.context());
        Instant now = Instant.now(clock);
        DealRationaleDto cached = cached(cacheKey, now);
        if (cached != null) {
            return cached;
        }

        try {
            AiCompletionOutcome outcome = aiInvocationService.complete(new AiInvocation(
                    FEATURE, assembly.context(), assembly.prompt(), MAX_TOKENS, TEMPERATURE));
            Instant generatedAt = Instant.now(clock);
            DealRationaleDto rationale = DealRationaleDto.of(
                    dealId, outcome.text(), generatedAt.toString(), outcome.demaskWarnings());
            cache(cacheKey, rationale, generatedAt.plus(CACHE_TTL));
            return rationale;
        } catch (MaskingLeakException | AiProviderException exception) {
            return DealRationaleDto.unavailable(dealId, PROVIDER_ERROR);
        } catch (ForbiddenException exception) {
            return DealRationaleDto.unavailable(dealId, NOT_CONFIGURED);
        }
    }

    private DealRationaleDto cached(String key, Instant now) {
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

    private void cache(String key, DealRationaleDto rationale, Instant expiresAt) {
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

    private static String cacheKey(int workspaceId, int dealId, MaskedPrompt prompt, MaskingContext context) {
        return workspaceId + ":" + dealId + ":" + contextHash(prompt, context);
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
        serialized.append(context.identifierDictionary().size()).append(':');
        context.identifierDictionary().stream().sorted().forEach(value -> appendPart(serialized, value));
        return serialized.toString();
    }

    private static void appendPart(StringBuilder serialized, String value) {
        if (value == null) {
            serialized.append("-1:");
            return;
        }
        serialized.append(value.length()).append(':').append(value);
    }

    private record CachedRationale(DealRationaleDto rationale, Instant expiresAt) {
    }
}
