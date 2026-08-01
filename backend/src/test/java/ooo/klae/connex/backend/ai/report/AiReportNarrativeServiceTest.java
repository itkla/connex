package ooo.klae.connex.backend.ai.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.ai.AiFeature;
import ooo.klae.connex.backend.ai.AiFeatureGate;
import ooo.klae.connex.backend.ai.AiGenerationProfile;
import ooo.klae.connex.backend.ai.AiInvocation;
import ooo.klae.connex.backend.ai.AiInvocationAdmissionService;
import ooo.klae.connex.backend.ai.AiInvocationAdmissionService.Admission;
import ooo.klae.connex.backend.ai.AiInvocationAdmissionService.Decision;
import ooo.klae.connex.backend.ai.AiInvocationAdmissionService.LeaderOutcome;
import ooo.klae.connex.backend.ai.AiInvocationService;
import ooo.klae.connex.backend.ai.AiOutputCacheStore;
import ooo.klae.connex.backend.ai.AiProperties;
import ooo.klae.connex.backend.ai.AiRawOutputGuard;
import ooo.klae.connex.backend.ai.AiRestrictionEpoch;
import ooo.klae.connex.backend.ai.AiStructuredOutcome;
import ooo.klae.connex.backend.beans.AiOutputCache;
import ooo.klae.connex.backend.dto.ReportAppendixRowDto;
import ooo.klae.connex.backend.dto.ReportNarrativeDto;
import ooo.klae.connex.backend.mappers.AiOutputCacheMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.services.WorkspaceService;
import tools.jackson.databind.json.JsonMapper;

/**
 * Verifies the non-blocking cache-only narrative path never reaches the AI provider.
 */
class AiReportNarrativeServiceTest {
    private final AiReportAssembler assembler = mock(AiReportAssembler.class);
    private final AiInvocationService invocationService = mock(AiInvocationService.class);
    private final AiInvocationAdmissionService admissionService = mock(AiInvocationAdmissionService.class);
    private final AiFeatureGate featureGate = mock(AiFeatureGate.class);
    private final AiOutputCacheStore cacheStore = mock(AiOutputCacheStore.class);
    private final WorkspaceService workspaceService = mock(WorkspaceService.class);
    private final Clock clock = Clock.systemUTC();

    private final AiReportNarrativeService service = new AiReportNarrativeService(
            assembler, invocationService, admissionService, featureGate, cacheStore, workspaceService, clock);

    private static final List<ReportAppendixRowDto> SOURCES = List.of(
            new ReportAppendixRowDto("metric.0.0", "w1", "count · Total", BigDecimal.ONE, null, "count"));
    private static final AiGenerationProfile PROFILE = new AiGenerationProfile(
            "bedrock", "us-east-1", "anthropic.claude-3-sonnet-v1:0",
            null, null, null, null,
            AiReportNarrativeService.MAX_TOKENS, AiReportNarrativeService.TEMPERATURE);

    @Test
    void cachedNarrativeReturnsNotConfiguredWhenGateClosedWithoutTouchingProvider() {
        when(featureGate.generationProfileIfUsable(AiFeature.REPORT_NARRATIVE, AiReportNarrativeService.MAX_TOKENS, AiReportNarrativeService.TEMPERATURE)).thenReturn(Optional.empty());

        ReportNarrativeDto result = service.cachedNarrative(
                7, "Report", LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), SOURCES);

        assertFalse(result.available());
        assertEquals("not_configured", result.reason());
        verifyNoInteractions(invocationService, assembler, cacheStore);
    }

    @Test
    void cachedNarrativeReturnsNotCachedOnCacheMissWithoutTouchingProvider() {
        when(featureGate.generationProfileIfUsable(AiFeature.REPORT_NARRATIVE, AiReportNarrativeService.MAX_TOKENS, AiReportNarrativeService.TEMPERATURE)).thenReturn(Optional.of(PROFILE));
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(1);
        when(workspaceService.getCurrentOrgId()).thenReturn(2);
        when(assembler.assemble(any())).thenReturn(mock(AiReportAssembly.class));
        when(cacheStore.contentHash(any(), any(), any())).thenReturn("hash");
        when(cacheStore.find(anyInt(), anyString(), anyInt(), anyInt())).thenReturn(Optional.empty());

        ReportNarrativeDto result = service.cachedNarrative(
                7, "Report", LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), SOURCES);

        assertFalse(result.available());
        assertEquals("not_cached", result.reason());
        verifyNoInteractions(invocationService);
        verify(cacheStore, never()).save(
                anyInt(), anyString(), anyInt(), anyInt(), anyString(), any(), anyInt(), anyString(), anyLong());
    }

    @Test
    void generate_followerReadsCachePublishedByLeader() {
        AiReportAssembly assembly = mock(AiReportAssembly.class);
        Admission admission = mock(Admission.class);
        AiOutputCache row = cacheRow("hash", "payload");
        AiReportNarrativeContent content = resolvedContent("Leader narrative.");
        when(featureGate.generationProfileIfUsable(
                AiFeature.REPORT_NARRATIVE,
                AiReportNarrativeService.MAX_TOKENS,
                AiReportNarrativeService.TEMPERATURE)).thenReturn(Optional.of(PROFILE));
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(1);
        when(assembler.assemble(any())).thenReturn(assembly);
        when(cacheStore.contentHash(any(), any(), any())).thenReturn("hash");
        when(cacheStore.find(anyInt(), anyString(), anyInt(), anyInt()))
                .thenReturn(Optional.empty(), Optional.of(row));
        when(cacheStore.read("payload", AiReportNarrativeContent.class)).thenReturn(Optional.of(content));
        when(admissionService.acquire(any(), eq("hash"), eq(false))).thenReturn(admission);
        when(admission.decision()).thenReturn(Decision.FOLLOWER);
        when(admission.awaitLeader()).thenReturn(LeaderOutcome.CACHE_READY);

        ReportNarrativeDto result = service.generate(
                7, "Report", LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), SOURCES, 0);

        assertTrue(result.available());
        assertEquals("Leader narrative.", result.sections().getFirst().claims().getFirst().text());
        verifyNoInteractions(invocationService);
    }

    @Test
    void generate_hashDivergentFlightProceedsAsIndependentLeader() {
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(1);
        when(workspaceService.getCurrentOrgId()).thenReturn(2);
        AiProperties properties = new AiProperties();
        AiInvocationAdmissionService realAdmissionService = new AiInvocationAdmissionService(
                properties, workspaceService, clock);
        AiReportNarrativeService realService = new AiReportNarrativeService(
                new AiReportAssembler(clock), invocationService, realAdmissionService, featureGate,
                cacheStore, workspaceService, clock);
        when(featureGate.generationProfileIfUsable(
                AiFeature.REPORT_NARRATIVE,
                AiReportNarrativeService.MAX_TOKENS,
                AiReportNarrativeService.TEMPERATURE)).thenReturn(Optional.of(PROFILE));
        when(cacheStore.contentHash(any(), any(), any())).thenReturn("current-hash");
        when(cacheStore.find(anyInt(), anyString(), anyInt(), anyInt())).thenReturn(Optional.empty());
        AiReportNarrativeContent.Claim claim = new AiReportNarrativeContent.Claim(
                "Total is {{num:metric.0.0.current}}.", List.of("metric.0.0"));
        AiReportNarrativeContent providerContent = new AiReportNarrativeContent(
                List.of(new AiReportNarrativeContent.Section(
                        AiReportFacts.titles().getFirst(), List.of(claim))),
                List.of(claim));
        when(invocationService.completeStructured(
                any(AiInvocation.class), eq(AiReportNarrativeContent.class),
                any(AiRawOutputGuard.class), any(Admission.class)))
                .thenReturn(new AiStructuredOutcome.Parsed<>(
                        providerContent, 0, 20, 10, "end_turn"));
        when(cacheStore.save(
                anyInt(), anyString(), anyInt(), anyInt(), anyString(), any(),
                anyInt(), anyString(), anyLong())).thenReturn(true);
        AiInvocationAdmissionService.CacheIdentity identity =
                AiInvocationAdmissionService.CacheIdentity.forSubject(
                        1, AiFeature.REPORT_NARRATIVE, 7, Locale.ENGLISH);

        try (Admission otherHash = realAdmissionService.acquire(identity, "other-hash", false)) {
            ReportNarrativeDto result = realService.generate(
                    7, "Report", LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), SOURCES, 0);

            assertEquals(Decision.LEADER, otherHash.decision());
            assertTrue(result.available());
        }
    }

    @Test
    void generate_restrictionPurgeMidGenerationReturnsUnavailableAndPublishesFailure() {
        int workspaceId = 1;
        int orgId = 2;
        Instant now = Instant.parse("2026-07-31T10:15:30Z");
        Clock fixedClock = Clock.fixed(now, ZoneOffset.UTC);
        AiRestrictionEpoch epoch = new AiRestrictionEpoch();
        AiOutputCacheMapper mapper = mock(AiOutputCacheMapper.class);
        AiOutputCacheStore guardedStore = new AiOutputCacheStore(
                mapper, mock(PersonMapper.class), epoch, JsonMapper.builder().build(), workspaceService);
        Admission admission = mock(Admission.class);
        AiReportNarrativeService guardedService = new AiReportNarrativeService(
                new AiReportAssembler(fixedClock), invocationService, admissionService, featureGate,
                guardedStore, workspaceService, fixedClock);
        when(featureGate.generationProfileIfUsable(AiFeature.REPORT_NARRATIVE, AiReportNarrativeService.MAX_TOKENS, AiReportNarrativeService.TEMPERATURE)).thenReturn(Optional.of(PROFILE));
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(workspaceId);
        when(workspaceService.getCurrentOrgId()).thenReturn(orgId);
        when(admissionService.acquire(any(), anyString(), eq(false))).thenReturn(admission);
        when(admission.decision()).thenReturn(Decision.LEADER);
        AiReportNarrativeContent.Claim claim = new AiReportNarrativeContent.Claim(
                "Total is {{num:metric.0.0.current}}.", List.of("metric.0.0"));
        AiReportNarrativeContent content = new AiReportNarrativeContent(
                List.of(new AiReportNarrativeContent.Section(
                        AiReportFacts.titles().getFirst(), List.of(claim))),
                List.of(claim));
        when(invocationService.completeStructured(
                any(AiInvocation.class), eq(AiReportNarrativeContent.class),
                any(AiRawOutputGuard.class), eq(admission)))
                .thenAnswer(invocation -> {
                    epoch.bump(workspaceId);
                    return new AiStructuredOutcome.Parsed<>(content, 0, 20, 10, "end_turn");
                });

        long capturedEpoch = epoch.current(workspaceId);
        ReportNarrativeDto result = guardedService.generate(
                7, "Report", LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), SOURCES,
                capturedEpoch);

        assertFalse(result.available());
        assertEquals("provider_error", result.reason());
        verify(mapper, never()).upsert(any());
        verify(admission).completeLeader(LeaderOutcome.FAILED);
        verify(admission, never()).completeLeader(LeaderOutcome.CACHE_READY);
    }

    private static AiReportNarrativeContent resolvedContent(String text) {
        AiReportNarrativeContent.Claim claim = new AiReportNarrativeContent.Claim(
                text, List.of("metric.0.0"));
        return new AiReportNarrativeContent(
                List.of(new AiReportNarrativeContent.Section(
                        AiReportFacts.titles().getFirst(), List.of(claim))),
                List.of(claim));
    }

    private static AiOutputCache cacheRow(String contentHash, String payload) {
        AiOutputCache row = new AiOutputCache();
        row.setContentHash(contentHash);
        row.setPayload(payload);
        row.setGeneratedAt("2026-07-01T09:00:00Z");
        return row;
    }
}
