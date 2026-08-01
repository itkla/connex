package ooo.klae.connex.backend.ai.report;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
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
import ooo.klae.connex.backend.dto.ReportAppendixRowDto;
import ooo.klae.connex.backend.dto.ReportNarrativeClaimDto;
import ooo.klae.connex.backend.dto.ReportNarrativeDto;
import ooo.klae.connex.backend.dto.ReportNarrativeSectionDto;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.services.AiProviderConfigService;
import ooo.klae.connex.backend.services.WorkspaceService;

/**
 * Generates and caches report prose only after every claim and numeric value validates against the
 * caller's deterministic source registry.
 */
@Service
@RequiredArgsConstructor
public class AiReportNarrativeService {
    static final int MAX_TOKENS = 4096;
    static final double TEMPERATURE = 0.1;

    private static final String NOT_CONFIGURED = "not_configured";
    private static final String PROVIDER_ERROR = "provider_error";
    private static final String INVALID_GROUNDING = "invalid_grounding";
    private static final String INSUFFICIENT_DATA = "insufficient_data";
    private static final String NOT_CACHED = "not_cached";
    private static final String RATE_LIMITED = "rate_limited";

    private final AiReportAssembler aiReportAssembler;
    private final AiInvocationService aiInvocationService;
    private final AiInvocationAdmissionService aiInvocationAdmissionService;
    private final AiFeatureGate aiFeatureGate;
    private final AiOutputCacheStore aiOutputCacheStore;
    private final AiProviderConfigService aiProviderConfigService;
    private final WorkspaceService workspaceService;
    private final Clock clock;

    /**
     * Returns a previously generated narrative for deterministic report sources without invoking the
     * provider or consuming a rate-limit slot. Used by the interactive figures-first response so a
     * report never blocks on the model call; a cache miss yields a {@code not_cached} result the
     * client resolves with a follow-up full generation.
     * @param reportId saved report definition id
     * @param reportName report display name
     * @param periodStart first included date
     * @param periodEnd last included date
     * @param sources deterministic appendix rows and citation registry
     * @return cached grounded narrative, or an unavailable result (including {@code not_cached})
     */
    public ReportNarrativeDto cachedNarrative(
            int reportId,
            String reportName,
            LocalDate periodStart,
            LocalDate periodEnd,
            List<ReportAppendixRowDto> sources) {
        NarrativePrep prep = prepare(reportId, reportName, periodStart, periodEnd, sources);
        if (prep.terminal() != null) {
            return prep.terminal();
        }
        ReportNarrativeDto cached = cached(
                prep.workspaceId(), prep.cacheFeature(), reportId, prep.contentHash(), prep.context());
        return cached != null ? cached : ReportNarrativeDto.unavailable(NOT_CACHED);
    }

    /**
     * Generates or reuses a narrative for deterministic report sources.
     * @param reportId saved report definition id
     * @param reportName report display name
     * @param periodStart first included date
     * @param periodEnd last included date
     * @param sources deterministic appendix rows and citation registry
     * @return grounded narrative or a graceful unavailable result
     */
    public ReportNarrativeDto generate(
            int reportId,
            String reportName,
            LocalDate periodStart,
            LocalDate periodEnd,
            List<ReportAppendixRowDto> sources) {
        NarrativePrep prep = prepare(reportId, reportName, periodStart, periodEnd, sources);
        if (prep.terminal() != null) {
            return prep.terminal();
        }
        int workspaceId = prep.workspaceId();
        AiReportContext reportContext = prep.context();
        AiReportAssembly assembly = prep.assembly();
        String cacheFeature = prep.cacheFeature();
        String contentHash = prep.contentHash();
        ReportNarrativeDto cached = cached(
                workspaceId, cacheFeature, reportId, contentHash, reportContext);
        if (cached != null) {
            return cached;
        }

        CacheIdentity identity = CacheIdentity.forSubject(
                workspaceId, AiFeature.REPORT_NARRATIVE, reportId, LocaleContextHolder.getLocale());
        try (Admission admission = aiInvocationAdmissionService.acquire(identity, false)) {
            if (admission.decision() == Decision.RATE_LIMITED) {
                return ReportNarrativeDto.unavailable(RATE_LIMITED);
            }
            if (admission.decision() == Decision.FOLLOWER) {
                admission.awaitLeader();
                ReportNarrativeDto joined = cached(
                        workspaceId, cacheFeature, reportId, contentHash, reportContext);
                return joined != null ? joined : ReportNarrativeDto.unavailable(PROVIDER_ERROR);
            }
            cached = cached(workspaceId, cacheFeature, reportId, contentHash, reportContext);
            if (cached != null) {
                admission.completeLeader(LeaderOutcome.CACHE_READY);
                return cached;
            }
            try {
                AiStructuredOutcome<AiReportNarrativeContent> outcome = aiInvocationService.completeStructured(
                        new AiInvocation(AiFeature.REPORT_NARRATIVE, assembly.context(), assembly.prompt(),
                                MAX_TOKENS, TEMPERATURE),
                        AiReportNarrativeContent.class,
                        AiReportProseResolver.noLiteralFigures(),
                        admission);
                if (!(outcome instanceof AiStructuredOutcome.Parsed<AiReportNarrativeContent> parsed)) {
                    return ReportNarrativeDto.unavailable(PROVIDER_ERROR);
                }
                AiReportFigures figures = AiReportFigures.from(
                        reportContext.sources(), LocaleContextHolder.getLocale());
                Optional<AiReportNarrativeContent> resolved =
                        AiReportProseResolver.resolve(parsed.value(), reportContext, figures);
                if (resolved.isEmpty()) {
                    return ReportNarrativeDto.unavailable(INVALID_GROUNDING);
                }
                AiReportNarrativeContent content = resolved.get();
                int warnings = parsed.demaskWarnings();
                String generatedAt = Instant.now(clock).toString();
                aiOutputCacheStore.save(
                        workspaceId,
                        cacheFeature,
                        reportId,
                        AiOutputCacheStore.NO_SUBJECT,
                        contentHash,
                        content,
                        warnings,
                        generatedAt);
                admission.completeLeader(LeaderOutcome.CACHE_READY);
                return toDto(content, generatedAt, warnings);
            } catch (ForbiddenException exception) {
                return ReportNarrativeDto.unavailable(NOT_CONFIGURED);
            } catch (RuntimeException exception) {
                return ReportNarrativeDto.unavailable(PROVIDER_ERROR);
            }
        }
    }

    private NarrativePrep prepare(
            int reportId,
            String reportName,
            LocalDate periodStart,
            LocalDate periodEnd,
            List<ReportAppendixRowDto> sources) {
        if (reportId <= 0 || sources == null || sources.isEmpty()) {
            return NarrativePrep.terminal(ReportNarrativeDto.unavailable(INSUFFICIENT_DATA));
        }
        if (!aiFeatureGate.isAiUsable(AiFeature.REPORT_NARRATIVE)) {
            return NarrativePrep.terminal(ReportNarrativeDto.unavailable(NOT_CONFIGURED));
        }
        AiReportContext reportContext;
        try {
            reportContext = new AiReportContext(reportName, periodStart, periodEnd, sources);
        } catch (IllegalArgumentException | NullPointerException exception) {
            return NarrativePrep.terminal(ReportNarrativeDto.unavailable(INVALID_GROUNDING));
        }
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        AiReportAssembly assembly = aiReportAssembler.assemble(reportContext);
        AiGenerationProfile profile;
        try {
            profile = aiProviderConfigService.profileForOrg(
                    workspaceService.getCurrentOrgId(), MAX_TOKENS, TEMPERATURE);
        } catch (ForbiddenException exception) {
            return NarrativePrep.terminal(ReportNarrativeDto.unavailable(NOT_CONFIGURED));
        }
        String contentHash = aiOutputCacheStore.contentHash(profile, assembly.prompt(), assembly.context());
        return NarrativePrep.ready(workspaceId, reportContext, assembly, cacheFeature(), contentHash);
    }

    private record NarrativePrep(
            ReportNarrativeDto terminal,
            int workspaceId,
            AiReportContext context,
            AiReportAssembly assembly,
            String cacheFeature,
            String contentHash) {

        static NarrativePrep terminal(ReportNarrativeDto result) {
            return new NarrativePrep(result, 0, null, null, null, null);
        }

        static NarrativePrep ready(
                int workspaceId,
                AiReportContext context,
                AiReportAssembly assembly,
                String cacheFeature,
                String contentHash) {
            return new NarrativePrep(null, workspaceId, context, assembly, cacheFeature, contentHash);
        }
    }

    private ReportNarrativeDto cached(
            int workspaceId,
            String cacheFeature,
            int reportId,
            String contentHash,
            AiReportContext reportContext) {
        Optional<AiOutputCache> row = aiOutputCacheStore.find(
                workspaceId, cacheFeature, reportId, AiOutputCacheStore.NO_SUBJECT);
        if (row.isEmpty() || !contentHash.equals(row.get().getContentHash())) {
            return null;
        }
        Optional<AiReportNarrativeContent> content = aiOutputCacheStore.read(
                row.get().getPayload(), AiReportNarrativeContent.class);
        if (content.isEmpty()) {
            return null;
        }
        Optional<AiReportNarrativeContent> validated =
                AiReportNarrativeValidator.validate(content.get(), reportContext);
        return validated.map(value -> toDto(
                value, row.get().getGeneratedAt(), row.get().getWarnings())).orElse(null);
    }

    private static ReportNarrativeDto toDto(
            AiReportNarrativeContent content, String generatedAt, int warnings) {
        List<ReportNarrativeSectionDto> sections = content.sections().stream()
                .map(section -> new ReportNarrativeSectionDto(
                        section.title(), toDtoClaims(section.claims())))
                .toList();
        return new ReportNarrativeDto(
                true, sections, toDtoClaims(content.findings()), null, generatedAt, warnings);
    }

    private static List<ReportNarrativeClaimDto> toDtoClaims(
            List<AiReportNarrativeContent.Claim> claims) {
        return claims.stream()
                .map(claim -> new ReportNarrativeClaimDto(claim.text(), claim.sourceIds()))
                .toList();
    }

    private static String cacheFeature() {
        Locale locale = LocaleContextHolder.getLocale();
        String language = locale.getLanguage();
        return AiFeature.REPORT_NARRATIVE.wireKey()
                + ":v4:" + (language.isBlank() ? Locale.ENGLISH.getLanguage() : language);
    }
}
