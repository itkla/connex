package ooo.klae.connex.backend.ai.introrationale;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.ai.AiFeature;
import ooo.klae.connex.backend.ai.AiFeatureGate;
import ooo.klae.connex.backend.ai.AiGenerationProfile;
import ooo.klae.connex.backend.ai.AiInvocation;
import ooo.klae.connex.backend.ai.AiInvocationAdmissionService;
import ooo.klae.connex.backend.ai.AiInvocationAdmissionService.Admission;
import ooo.klae.connex.backend.ai.AiInvocationAdmissionService.CacheIdentity;
import ooo.klae.connex.backend.ai.AiInvocationAdmissionService.Decision;
import ooo.klae.connex.backend.ai.AiInvocationAdmissionService.LeaderOutcome;
import ooo.klae.connex.backend.ai.AiInvocationService;
import ooo.klae.connex.backend.ai.AiOutputCacheStore;
import ooo.klae.connex.backend.ai.AiStructuredOutcome;
import ooo.klae.connex.backend.beans.AiOutputCache;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.dto.IntroRationaleDto;
import ooo.klae.connex.backend.dto.IntroSuggestionDto;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.services.IntroductionService;
import ooo.klae.connex.backend.services.WorkspaceService;

/**
 * Generates presentation-only introduction rationales through the audited AI invocation boundary,
 * reusing a persisted output while the suggestion's assembled context is unchanged.
 */
@Service
@RequiredArgsConstructor
public class IntroRationaleService {
    static final int MAX_TOKENS = 512;
    static final int MAX_RATIONALE_CHARS = 400;
    static final double TEMPERATURE = 0.2;
    static final int RESOLVE_LIMIT = 50;

    private static final String NOT_CONFIGURED = "not_configured";
    private static final String PROVIDER_ERROR = "provider_error";
    private static final String NOT_A_SUGGESTION = "not_a_suggestion";
    private static final String RATE_LIMITED = "rate_limited";

    private final IntroRationaleAssembler introRationaleAssembler;
    private final AiInvocationService aiInvocationService;
    private final AiInvocationAdmissionService aiInvocationAdmissionService;
    private final AiFeatureGate aiFeatureGate;
    private final IntroductionService introductionService;
    private final AiOutputCacheStore aiOutputCacheStore;
    private final WorkspaceService workspaceService;
    private final PersonMapper personMapper;
    private final Clock clock;

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
        Optional<AiGenerationProfile> profile = aiFeatureGate.generationProfileIfUsable(
                AiFeature.INTRO_RATIONALE, MAX_TOKENS, TEMPERATURE);
        if (profile.isEmpty()) {
            return IntroRationaleDto.unavailable(lo, hi, NOT_CONFIGURED);
        }

        IntroSuggestionDto suggestion = introductionService.computeSuggestions(workspaceId, RESOLVE_LIMIT).stream()
                .filter(candidate -> candidate.getPersonAId() == lo && candidate.getPersonBId() == hi)
                .findFirst()
                .orElse(null);
        if (suggestion == null) {
            return IntroRationaleDto.unavailable(lo, hi, NOT_A_SUGGESTION);
        }
        if (isAiRestricted(personMapper.getPersonById(workspaceId, lo))
                || isAiRestricted(personMapper.getPersonById(workspaceId, hi))) {
            return IntroRationaleDto.unavailable(lo, hi, NOT_A_SUGGESTION);
        }

        IntroRationaleAssembly assembly = introRationaleAssembler.assemble(workspaceId, suggestion);
        String cacheFeature = cacheFeature();
        String contentHash = aiOutputCacheStore.contentHash(
                profile.get(), assembly.prompt(), assembly.context());
        IntroRationaleDto cached = cached(workspaceId, cacheFeature, lo, hi, contentHash);
        if (cached != null) {
            return cached;
        }

        CacheIdentity identity = CacheIdentity.forPair(
                workspaceId, AiFeature.INTRO_RATIONALE, lo, hi, LocaleContextHolder.getLocale());
        while (true) {
            try (Admission admission = aiInvocationAdmissionService.acquire(identity, contentHash, false)) {
                if (admission.decision() == Decision.RATE_LIMITED) {
                    return IntroRationaleDto.unavailable(lo, hi, RATE_LIMITED);
                }
                if (admission.decision() == Decision.FOLLOWER) {
                    LeaderOutcome leaderOutcome = admission.awaitLeader();
                    if (leaderOutcome == LeaderOutcome.FAILED) {
                        continue;
                    }
                    IntroRationaleDto joined = cached(workspaceId, cacheFeature, lo, hi, contentHash);
                    return joined != null
                            ? joined
                            : IntroRationaleDto.unavailable(lo, hi, PROVIDER_ERROR);
                }
                IntroRationaleDto rechecked = cached(workspaceId, cacheFeature, lo, hi, contentHash);
                if (rechecked != null) {
                    admission.completeLeader(LeaderOutcome.CACHE_READY);
                    return rechecked;
                }
                try {
                    AiStructuredOutcome<IntroRationaleContent> outcome =
                            aiInvocationService.completeStructured(
                                    new AiInvocation(
                                            AiFeature.INTRO_RATIONALE,
                                            assembly.context(),
                                            assembly.prompt(),
                                            MAX_TOKENS,
                                            TEMPERATURE),
                                    IntroRationaleContent.class,
                                    admission);
                    if (!(outcome instanceof AiStructuredOutcome.Parsed<IntroRationaleContent> parsed)) {
                        return IntroRationaleDto.unavailable(lo, hi, PROVIDER_ERROR);
                    }
                    IntroRationaleContent content = parsed.value();
                    if (content == null || isBlank(content.rationale())) {
                        return IntroRationaleDto.unavailable(lo, hi, PROVIDER_ERROR);
                    }
                    String rationale = truncate(content.rationale().strip(), MAX_RATIONALE_CHARS);
                    String generatedAt = Instant.now(clock).toString();
                    boolean safeToServe = aiOutputCacheStore.saveForPersons(
                            workspaceId, cacheFeature, lo, hi, contentHash,
                            new IntroRationaleContent(rationale), parsed.demaskWarnings(), generatedAt,
                            List.of(lo, hi));
                    if (!safeToServe) {
                        return IntroRationaleDto.unavailable(lo, hi, NOT_A_SUGGESTION);
                    }
                    admission.completeLeader(LeaderOutcome.CACHE_READY);
                    return IntroRationaleDto.of(
                            lo, hi, rationale, generatedAt, parsed.demaskWarnings());
                } catch (ForbiddenException exception) {
                    return IntroRationaleDto.unavailable(lo, hi, NOT_CONFIGURED);
                } catch (RuntimeException exception) {
                    return IntroRationaleDto.unavailable(lo, hi, PROVIDER_ERROR);
                }
            }
        }
    }

    private IntroRationaleDto cached(int workspaceId, String cacheFeature, int lo, int hi, String contentHash) {
        Optional<AiOutputCache> row = aiOutputCacheStore.find(workspaceId, cacheFeature, lo, hi);
        if (row.isEmpty() || !contentHash.equals(row.get().getContentHash())) {
            return null;
        }
        Optional<IntroRationaleContent> content = aiOutputCacheStore.read(
                row.get().getPayload(), IntroRationaleContent.class);
        if (content.isEmpty() || isBlank(content.get().rationale())) {
            return null;
        }
        return IntroRationaleDto.of(
                lo,
                hi,
                truncate(content.get().rationale().strip(), MAX_RATIONALE_CHARS),
                row.get().getGeneratedAt(),
                row.get().getWarnings());
    }

    private static String cacheFeature() {
        String language = LocaleContextHolder.getLocale().getLanguage();
        return AiFeature.INTRO_RATIONALE.wireKey() + ':' + (language.isBlank() ? "en" : language);
    }

    private static boolean isAiRestricted(Person person) {
        return person == null || person.getSuspendedAt() != null || person.getProvisionCeasedAt() != null;
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
}
