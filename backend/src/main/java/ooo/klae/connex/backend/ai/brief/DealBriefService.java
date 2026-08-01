package ooo.klae.connex.backend.ai.brief;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
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
    static final int MAX_SECTIONS = 8;
    static final int MAX_TITLE_CHARS = 160;
    static final int MAX_BODY_CHARS = 2000;
    static final double TEMPERATURE = 0.2;

    private static final String NOT_CONFIGURED = "not_configured";
    private static final String PROVIDER_ERROR = "provider_error";
    private static final String RATE_LIMITED = "rate_limited";

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
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Optional<AiGenerationProfile> profile = aiFeatureGate.generationProfileIfUsable(
                AiFeature.DEAL_BRIEF, MAX_TOKENS, TEMPERATURE);
        if (profile.isEmpty()) {
            return DealBriefDto.unavailable(dealId, NOT_CONFIGURED);
        }

        BriefAssembly assembly = dealBriefAssembler.assemble(workspaceId, dealId);
        String cacheFeature = cacheFeature();
        String contentHash = aiOutputCacheStore.contentHash(
                profile.get(), assembly.prompt(), assembly.context());
        if (!refresh) {
            DealBriefDto cached = cached(workspaceId, cacheFeature, dealId, contentHash);
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
                    DealBriefDto joined = cached(workspaceId, cacheFeature, dealId, contentHash);
                    return joined != null ? joined : DealBriefDto.unavailable(dealId, PROVIDER_ERROR);
                }
                if (!refresh) {
                    DealBriefDto rechecked = cached(workspaceId, cacheFeature, dealId, contentHash);
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
                    List<DealBriefContent.Section> sections = sections(parsed.value());
                    if (sections.isEmpty()) {
                        return DealBriefDto.unavailable(dealId, PROVIDER_ERROR);
                    }
                    String generatedAt = Instant.now(clock).toString();
                    boolean safeToServe = aiOutputCacheStore.saveForPersons(
                            workspaceId, cacheFeature, dealId, AiOutputCacheStore.NO_SUBJECT,
                            contentHash, new DealBriefContent(sections), parsed.demaskWarnings(), generatedAt,
                            assembly.contributorPersonIds());
                    if (!safeToServe) {
                        return DealBriefDto.unavailable(dealId, PROVIDER_ERROR);
                    }
                    admission.completeLeader(LeaderOutcome.CACHE_READY);
                    return DealBriefDto.of(
                            dealId, toDtoSections(sections), generatedAt, parsed.demaskWarnings());
                } catch (ForbiddenException exception) {
                    return DealBriefDto.unavailable(dealId, NOT_CONFIGURED);
                } catch (RuntimeException exception) {
                    return DealBriefDto.unavailable(dealId, PROVIDER_ERROR);
                }
            }
        }
    }

    private DealBriefDto cached(int workspaceId, String cacheFeature, int dealId, String contentHash) {
        Optional<AiOutputCache> row = aiOutputCacheStore.find(
                workspaceId, cacheFeature, dealId, AiOutputCacheStore.NO_SUBJECT);
        if (row.isEmpty() || !contentHash.equals(row.get().getContentHash())) {
            return null;
        }
        Optional<DealBriefContent> content = aiOutputCacheStore.read(row.get().getPayload(), DealBriefContent.class);
        if (content.isEmpty()) {
            return null;
        }
        List<DealBriefContent.Section> sections = sections(content.get());
        if (sections.isEmpty()) {
            return null;
        }
        return DealBriefDto.of(
                dealId, toDtoSections(sections), row.get().getGeneratedAt(), row.get().getWarnings());
    }

    private static List<DealBriefContent.Section> sections(DealBriefContent content) {
        if (content == null || content.sections() == null) {
            return List.of();
        }
        List<DealBriefContent.Section> sections = new ArrayList<>();
        for (DealBriefContent.Section section : content.sections()) {
            if (section == null || isBlank(section.title()) || isBlank(section.body())) {
                continue;
            }
            sections.add(new DealBriefContent.Section(
                    truncate(section.title().strip(), MAX_TITLE_CHARS),
                    truncate(section.body().strip(), MAX_BODY_CHARS)));
            if (sections.size() == MAX_SECTIONS) {
                break;
            }
        }
        return List.copyOf(sections);
    }

    private static List<DealBriefDto.Section> toDtoSections(List<DealBriefContent.Section> sections) {
        return sections.stream()
                .map(section -> new DealBriefDto.Section(section.title(), section.body()))
                .toList();
    }

    private static String cacheFeature() {
        String language = LocaleContextHolder.getLocale().getLanguage();
        return AiFeature.DEAL_BRIEF.wireKey() + ':' + (language.isBlank() ? "en" : language);
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
