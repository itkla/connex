package ooo.klae.connex.backend.ai.report;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.ai.AiFeatureGate;
import ooo.klae.connex.backend.ai.AiInvocation;
import ooo.klae.connex.backend.ai.AiInvocationService;
import ooo.klae.connex.backend.ai.AiOutputCacheStore;
import ooo.klae.connex.backend.ai.AiStructuredOutcome;
import ooo.klae.connex.backend.ai.masking.MaskingLeakException;
import ooo.klae.connex.backend.ai.provider.AiProviderException;
import ooo.klae.connex.backend.beans.AiOutputCache;
import ooo.klae.connex.backend.dto.ReportAppendixRowDto;
import ooo.klae.connex.backend.dto.ReportNarrativeClaimDto;
import ooo.klae.connex.backend.dto.ReportNarrativeDto;
import ooo.klae.connex.backend.dto.ReportNarrativeSectionDto;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.services.AuthService;
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

    private static final String FEATURE = "report.narrative";
    private static final String NOT_CONFIGURED = "not_configured";
    private static final String PROVIDER_ERROR = "provider_error";
    private static final String INVALID_GROUNDING = "invalid_grounding";
    private static final String INSUFFICIENT_DATA = "insufficient_data";
    private static final String RATE_LIMITED = "rate_limited";
    private static final int MAX_CACHE_MISSES_PER_WINDOW = 10;
    private static final long RATE_WINDOW_MINUTES = 10;

    private final AiReportAssembler aiReportAssembler;
    private final AiInvocationService aiInvocationService;
    private final AiFeatureGate aiFeatureGate;
    private final AiOutputCacheStore aiOutputCacheStore;
    private final WorkspaceService workspaceService;
    private final AuthService authService;
    private final Clock clock;
    private final ConcurrentMap<String, Object> invocationLocks = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ArrayDeque<Instant>> invocationWindows = new ConcurrentHashMap<>();

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
        if (reportId <= 0 || sources == null || sources.isEmpty()) {
            return ReportNarrativeDto.unavailable(INSUFFICIENT_DATA);
        }
        if (!aiFeatureGate.isAiUsable()) {
            return ReportNarrativeDto.unavailable(NOT_CONFIGURED);
        }

        AiReportContext reportContext;
        try {
            reportContext = new AiReportContext(reportName, periodStart, periodEnd, sources);
        } catch (IllegalArgumentException | NullPointerException exception) {
            return ReportNarrativeDto.unavailable(INVALID_GROUNDING);
        }
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        AiReportAssembly assembly = aiReportAssembler.assemble(reportContext);
        String cacheFeature = cacheFeature();
        String contentHash = aiOutputCacheStore.contentHash(assembly.prompt(), assembly.context());
        ReportNarrativeDto cached = cached(
                workspaceId, cacheFeature, reportId, contentHash, reportContext);
        if (cached != null) {
            return cached;
        }

        String invocationKey = workspaceId + ":" + reportId + ":" + contentHash;
        Object invocationLock = invocationLocks.computeIfAbsent(invocationKey, ignored -> new Object());
        try {
            synchronized (invocationLock) {
                cached = cached(workspaceId, cacheFeature, reportId, contentHash, reportContext);
                if (cached != null) {
                    return cached;
                }
                if (!acquireInvocationSlot(workspaceId, authService.getCurrentUser().getId())) {
                    return ReportNarrativeDto.unavailable(RATE_LIMITED);
                }
                try {
                    AiStructuredOutcome<AiReportNarrativeContent> outcome = aiInvocationService.completeStructured(
                            new AiInvocation(FEATURE, assembly.context(), assembly.prompt(), MAX_TOKENS, TEMPERATURE),
                            AiReportNarrativeContent.class);
                    if (!(outcome instanceof AiStructuredOutcome.Parsed<AiReportNarrativeContent> parsed)) {
                        return ReportNarrativeDto.unavailable(PROVIDER_ERROR);
                    }
                    Optional<AiReportNarrativeContent> validated =
                            AiReportNarrativeValidator.validate(parsed.value(), reportContext);
                    if (validated.isEmpty()) {
                        return ReportNarrativeDto.unavailable(INVALID_GROUNDING);
                    }
                    String generatedAt = Instant.now(clock).toString();
                    aiOutputCacheStore.save(
                            workspaceId,
                            cacheFeature,
                            reportId,
                            AiOutputCacheStore.NO_SUBJECT,
                            contentHash,
                            validated.get(),
                            parsed.demaskWarnings(),
                            generatedAt);
                    return toDto(validated.get(), generatedAt, parsed.demaskWarnings());
                } catch (MaskingLeakException | AiProviderException exception) {
                    return ReportNarrativeDto.unavailable(PROVIDER_ERROR);
                } catch (ForbiddenException exception) {
                    return ReportNarrativeDto.unavailable(NOT_CONFIGURED);
                }
            }
        } finally {
            invocationLocks.remove(invocationKey, invocationLock);
        }
    }

    private boolean acquireInvocationSlot(int workspaceId, int userId) {
        String key = workspaceId + ":" + userId;
        ArrayDeque<Instant> window = invocationWindows.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        Instant now = Instant.now(clock);
        Instant cutoff = now.minus(RATE_WINDOW_MINUTES, ChronoUnit.MINUTES);
        synchronized (window) {
            while (!window.isEmpty() && window.getFirst().isBefore(cutoff)) {
                window.removeFirst();
            }
            if (window.size() >= MAX_CACHE_MISSES_PER_WINDOW) {
                return false;
            }
            window.addLast(now);
            return true;
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
        return FEATURE + ":v2:" + (language.isBlank() ? Locale.ENGLISH.getLanguage() : language);
    }
}
