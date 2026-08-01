package ooo.klae.connex.backend.ai.brief;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

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
import ooo.klae.connex.backend.dto.DealBriefDto;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.services.WorkspaceService;

/**
 * Generates presentation-only deal briefs through the audited AI invocation boundary, reusing a
 * persisted output while the deal's assembled context is unchanged.
 */
@Service
@RequiredArgsConstructor
public class DealBriefService {
    static final int MAX_TOKENS = 2048;
    static final int MIN_SECTIONS = 3;
    static final int MAX_SECTIONS = 4;
    static final int MAX_TITLE_CHARS = 160;
    static final int MAX_BODY_CHARS = 2000;
    static final int MIN_EVIDENCE_SOURCES = 2;
    static final double TEMPERATURE = 0.2;

    private static final String NOT_CONFIGURED = "not_configured";
    private static final String PROVIDER_ERROR = "provider_error";
    private static final String RATE_LIMITED = "rate_limited";
    private static final String INSUFFICIENT_DATA = "insufficient_data";

    private final DealBriefAssembler dealBriefAssembler;
    private final AiInvocationService aiInvocationService;
    private final AiInvocationAdmissionService aiInvocationAdmissionService;
    private final AiFeatureGate aiFeatureGate;
    private final AiOutputCacheStore aiOutputCacheStore;
    private final WorkspaceService workspaceService;
    private final Clock clock;

    /**
     * Generates or reuses a fresh brief for a workspace-scoped deal.
     * @param dealId deal to summarize
     * @return available brief or a graceful unavailability response
     */
    public DealBriefDto generate(int dealId) {
        return generate(dealId, false);
    }

    /**
     * Generates or reuses a brief for a workspace-scoped deal.
     * @param dealId deal to summarize
     * @param refresh when true, bypass any stored output and force a fresh generation
     * @return available brief or a graceful unavailability response
     */
    public DealBriefDto generate(int dealId, boolean refresh) {
        Optional<AiGenerationProfile> profile = aiFeatureGate.generationProfileIfUsable(
                AiFeature.DEAL_BRIEF, MAX_TOKENS, TEMPERATURE);
        if (profile.isEmpty()) {
            return DealBriefDto.unavailable(dealId, NOT_CONFIGURED);
        }

        int workspaceId = workspaceService.getCurrentWorkspaceId();
        BriefAssembly assembly = dealBriefAssembler.assemble(workspaceId, dealId);
        if (!hasSufficientEvidence(assembly.sourceRegistry(), dealId)) {
            return DealBriefDto.unavailable(dealId, INSUFFICIENT_DATA);
        }
        String cacheFeature = cacheFeature();
        String contentHash = aiOutputCacheStore.contentHash(
                profile.get(), assembly.prompt(), assembly.context(),
                sourceRegistryHashMaterial(assembly.sourceRegistry()));
        if (!refresh) {
            DealBriefDto cached = cached(
                    workspaceId, cacheFeature, dealId, contentHash, assembly);
            if (cached != null) {
                return cached;
            }
        }

        CacheIdentity identity = CacheIdentity.forSubject(
                workspaceId, AiFeature.DEAL_BRIEF, dealId, LocaleContextHolder.getLocale());
        boolean admissionRefresh = refresh;
        while (true) {
            try (Admission admission = aiInvocationAdmissionService.acquire(
                    identity, contentHash, admissionRefresh)) {
                if (admission.decision() == Decision.RATE_LIMITED) {
                    return DealBriefDto.unavailable(dealId, RATE_LIMITED);
                }
                if (admission.decision() == Decision.FOLLOWER) {
                    LeaderOutcome leaderOutcome = admission.awaitLeader();
                    if (leaderOutcome == LeaderOutcome.FAILED) {
                        admissionRefresh = false;
                        continue;
                    }
                    DealBriefDto joined = cached(
                            workspaceId, cacheFeature, dealId, contentHash, assembly);
                    return joined != null ? joined : DealBriefDto.unavailable(dealId, PROVIDER_ERROR);
                }
                if (!refresh) {
                    DealBriefDto rechecked = cached(
                            workspaceId, cacheFeature, dealId, contentHash, assembly);
                    if (rechecked != null) {
                        admission.completeLeader(LeaderOutcome.CACHE_READY);
                        return rechecked;
                    }
                }
                try {
                    AiStructuredOutcome<DealBriefContent> outcome = aiInvocationService.completeStructured(
                            new AiInvocation(
                                    AiFeature.DEAL_BRIEF, assembly.context(), assembly.prompt(),
                                    MAX_TOKENS, TEMPERATURE),
                            DealBriefContent.class,
                            admission);
                    if (!(outcome instanceof AiStructuredOutcome.Parsed<DealBriefContent> parsed)) {
                        return DealBriefDto.unavailable(dealId, PROVIDER_ERROR);
                    }
                    Optional<DealBriefContent> validated = DealBriefValidator.validate(
                            parsed.value(), assembly.sourceRegistry());
                    if (validated.isEmpty()) {
                        return DealBriefDto.unavailable(dealId, PROVIDER_ERROR);
                    }
                    String generatedAt = Instant.now(clock).toString();
                    boolean safeToServe = aiOutputCacheStore.saveForPersons(
                            workspaceId, cacheFeature, dealId, AiOutputCacheStore.NO_SUBJECT,
                            contentHash, validated.get(), parsed.demaskWarnings(), generatedAt,
                            assembly.contributorPersonIds());
                    if (!safeToServe) {
                        return DealBriefDto.unavailable(dealId, PROVIDER_ERROR);
                    }
                    admission.completeLeader(LeaderOutcome.CACHE_READY);
                    return DealBriefDto.of(
                            dealId,
                            toDtoSections(validated.get().sections(), assembly.sourceRegistry()),
                            generatedAt,
                            parsed.demaskWarnings(),
                            assembly.degraded());
                } catch (ForbiddenException exception) {
                    return DealBriefDto.unavailable(dealId, NOT_CONFIGURED);
                } catch (RuntimeException exception) {
                    return DealBriefDto.unavailable(dealId, PROVIDER_ERROR);
                }
            }
        }
    }

    private DealBriefDto cached(
            int workspaceId,
            String cacheFeature,
            int dealId,
            String contentHash,
            BriefAssembly assembly) {
        Optional<AiOutputCache> row = aiOutputCacheStore.find(
                workspaceId, cacheFeature, dealId, AiOutputCacheStore.NO_SUBJECT);
        if (row.isEmpty() || !contentHash.equals(row.get().getContentHash())) {
            return null;
        }
        Optional<DealBriefContent> content = aiOutputCacheStore.read(row.get().getPayload(), DealBriefContent.class);
        Optional<DealBriefContent> validated = content.flatMap(
                value -> DealBriefValidator.validate(value, assembly.sourceRegistry()));
        if (validated.isEmpty()) {
            aiOutputCacheStore.deleteIfContentHashMatches(
                    workspaceId,
                    cacheFeature,
                    dealId,
                    AiOutputCacheStore.NO_SUBJECT,
                    row.get().getContentHash());
            return null;
        }
        return DealBriefDto.of(
                dealId,
                toDtoSections(validated.get().sections(), assembly.sourceRegistry()),
                row.get().getGeneratedAt(),
                row.get().getWarnings(),
                assembly.degraded());
    }

    private static List<DealBriefDto.Section> toDtoSections(
            List<DealBriefContent.Section> sections,
            Map<String, DealBriefSource> sourceRegistry) {
        return sections.stream()
                .map(section -> new DealBriefDto.Section(
                        section.title(),
                        section.body(),
                        section.sourceIds().stream()
                                .map(sourceRegistry::get)
                                .map(source -> new DealBriefDto.Citation(source.kind(), source.id()))
                                .toList()))
                .toList();
    }

    private static boolean hasSufficientEvidence(
            Map<String, DealBriefSource> sourceRegistry, int currentDealId) {
        if (sourceRegistry.size() < MIN_EVIDENCE_SOURCES) {
            return false;
        }
        DealBriefSource currentDeal = new DealBriefSource("deal", currentDealId);
        boolean hasCurrentDeal = sourceRegistry.containsValue(currentDeal);
        boolean hasSupportingSource = sourceRegistry.values().stream()
                .anyMatch(source -> !currentDeal.equals(source));
        return hasCurrentDeal && hasSupportingSource;
    }

    private static List<String> sourceRegistryHashMaterial(
            Map<String, DealBriefSource> sourceRegistry) {
        return sourceRegistry.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .flatMap(entry -> Stream.of(
                        entry.getKey(), entry.getValue().kind(), Integer.toString(entry.getValue().id())))
                .toList();
    }

    private static String cacheFeature() {
        String language = LocaleContextHolder.getLocale().getLanguage();
        return AiFeature.DEAL_BRIEF.wireKey() + ':' + (language.isBlank() ? "en" : language);
    }

}
