package ooo.klae.connex.backend.ai.brief;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.ai.AiFeatureGate;
import ooo.klae.connex.backend.ai.AiInvocation;
import ooo.klae.connex.backend.ai.AiInvocationService;
import ooo.klae.connex.backend.ai.AiStructuredOutcome;
import ooo.klae.connex.backend.ai.masking.MaskedMessage;
import ooo.klae.connex.backend.ai.masking.MaskedPrompt;
import ooo.klae.connex.backend.ai.masking.MaskingContext;
import ooo.klae.connex.backend.ai.masking.MaskingLeakException;
import ooo.klae.connex.backend.ai.provider.AiProviderException;
import ooo.klae.connex.backend.dto.DealBriefDto;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.services.WorkspaceService;

/**
 * Generates presentation-only deal briefs through the audited AI invocation boundary.
 */
@Service
@RequiredArgsConstructor
public class DealBriefService {
    static final int MAX_TOKENS = 2048;
    static final int MAX_SECTIONS = 8;
    static final int MAX_TITLE_CHARS = 160;
    static final int MAX_BODY_CHARS = 2000;
    static final double TEMPERATURE = 0.2;
    static final int MAX_CACHE_ENTRIES = 256;
    static final Duration CACHE_TTL = Duration.ofMinutes(30);

    private static final String FEATURE = "deal.brief";
    private static final String NOT_CONFIGURED = "not_configured";
    private static final String PROVIDER_ERROR = "provider_error";

    private final DealBriefAssembler dealBriefAssembler;
    private final AiInvocationService aiInvocationService;
    private final AiFeatureGate aiFeatureGate;
    private final WorkspaceService workspaceService;
    private final Clock clock;
    private final ConcurrentHashMap<String, CachedBrief> cache = new ConcurrentHashMap<>();

    /**
     * Generates or reuses a fresh brief for a workspace-scoped deal.
     * @param dealId deal to summarize
     * @return available brief or a graceful unavailability response
     */
    public DealBriefDto generate(int dealId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        if (!aiFeatureGate.isAiUsable()) {
            return DealBriefDto.unavailable(dealId, NOT_CONFIGURED);
        }

        BriefAssembly assembly = dealBriefAssembler.assemble(workspaceId, dealId);
        String cacheKey = cacheKey(workspaceId, dealId, assembly.prompt(), assembly.context());
        Instant now = Instant.now(clock);
        DealBriefDto cached = cached(cacheKey, now);
        if (cached != null) {
            return cached;
        }

        try {
            AiStructuredOutcome<DealBriefContent> outcome = aiInvocationService.completeStructured(
                    new AiInvocation(FEATURE, assembly.context(), assembly.prompt(), MAX_TOKENS, TEMPERATURE),
                    DealBriefContent.class);
            if (!(outcome instanceof AiStructuredOutcome.Parsed<DealBriefContent> parsed)) {
                return DealBriefDto.unavailable(dealId, PROVIDER_ERROR);
            }
            List<DealBriefDto.Section> sections = sections(parsed.value());
            if (sections.isEmpty()) {
                return DealBriefDto.unavailable(dealId, PROVIDER_ERROR);
            }
            Instant generatedAt = Instant.now(clock);
            DealBriefDto brief = DealBriefDto.of(
                    dealId, sections, generatedAt.toString(), parsed.demaskWarnings());
            cache(cacheKey, brief, generatedAt.plus(CACHE_TTL));
            return brief;
        } catch (MaskingLeakException | AiProviderException exception) {
            return DealBriefDto.unavailable(dealId, PROVIDER_ERROR);
        } catch (ForbiddenException exception) {
            return DealBriefDto.unavailable(dealId, NOT_CONFIGURED);
        }
    }

    private static List<DealBriefDto.Section> sections(DealBriefContent content) {
        if (content == null || content.sections() == null) {
            return List.of();
        }
        List<DealBriefDto.Section> sections = new ArrayList<>();
        for (DealBriefContent.Section section : content.sections()) {
            if (section == null || isBlank(section.title()) || isBlank(section.body())) {
                continue;
            }
            sections.add(new DealBriefDto.Section(
                    truncate(section.title().strip(), MAX_TITLE_CHARS),
                    truncate(section.body().strip(), MAX_BODY_CHARS)));
            if (sections.size() == MAX_SECTIONS) {
                break;
            }
        }
        return List.copyOf(sections);
    }

    private static String truncate(String value, int maxCodePoints) {
        int codePoints = value.codePointCount(0, value.length());
        if (codePoints <= maxCodePoints) {
            return value;
        }
        int end = value.offsetByCodePoints(0, maxCodePoints - 1);
        return value.substring(0, end) + "…";
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private DealBriefDto cached(String key, Instant now) {
        CachedBrief cached = cache.get(key);
        if (cached == null) {
            return null;
        }
        if (now.isBefore(cached.expiresAt())) {
            return cached.brief();
        }
        cache.remove(key, cached);
        return null;
    }

    private void cache(String key, DealBriefDto brief, Instant expiresAt) {
        synchronized (cache) {
            cache.entrySet().removeIf(entry -> !Instant.now(clock).isBefore(entry.getValue().expiresAt()));
            if (!cache.containsKey(key) && cache.size() >= MAX_CACHE_ENTRIES) {
                cache.entrySet().stream()
                        .min(Map.Entry.comparingByValue(Comparator.comparing(CachedBrief::expiresAt)))
                        .ifPresent(entry -> cache.remove(entry.getKey(), entry.getValue()));
            }
            cache.put(key, new CachedBrief(brief, expiresAt));
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

    private record CachedBrief(DealBriefDto brief, Instant expiresAt) {
    }
}
