package ooo.klae.connex.backend.ai.riskrationale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.i18n.LocaleContextHolder;

import ooo.klae.connex.backend.ai.AiFeatureGate;
import ooo.klae.connex.backend.ai.AiInvocation;
import ooo.klae.connex.backend.ai.AiInvocationService;
import ooo.klae.connex.backend.ai.AiOutputCacheStore;
import ooo.klae.connex.backend.ai.AiStructuredOutcome;
import ooo.klae.connex.backend.ai.masking.EntityKind;
import ooo.klae.connex.backend.ai.masking.MaskedPrompt;
import ooo.klae.connex.backend.ai.masking.MaskingContext;
import ooo.klae.connex.backend.ai.masking.MaskingEngine;
import ooo.klae.connex.backend.ai.masking.MaskingLeakException;
import ooo.klae.connex.backend.ai.masking.PromptAssembly;
import ooo.klae.connex.backend.ai.provider.AiProviderException;
import ooo.klae.connex.backend.beans.AiOutputCache;
import ooo.klae.connex.backend.dto.DealRationaleDto;
import ooo.klae.connex.backend.dto.DealRiskDto;
import ooo.klae.connex.backend.dto.DealRiskFactor;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.services.DealRiskService;
import ooo.klae.connex.backend.services.WorkspaceService;

@ExtendWith(MockitoExtension.class)
class DealRiskRationaleServiceTest {
    private static final int WORKSPACE_ID = 17;
    private static final int DEAL_ID = 29;
    private static final String CACHE_FEATURE = "deal.risk_rationale:en";
    private static final String HASH = "content-hash-1";
    private static final Instant NOW = Instant.parse("2026-07-09T18:30:00Z");

    @Mock private DealRiskRationaleAssembler dealRiskRationaleAssembler;
    @Mock private AiInvocationService aiInvocationService;
    @Mock private AiFeatureGate aiFeatureGate;
    @Mock private DealRiskService dealRiskService;
    @Mock private AiOutputCacheStore aiOutputCacheStore;
    @Mock private WorkspaceService workspaceService;

    private DealRiskRationaleService service;

    @BeforeEach
    void setUp() {
        service = new DealRiskRationaleService(
                dealRiskRationaleAssembler,
                aiInvocationService,
                aiFeatureGate,
                dealRiskService,
                aiOutputCacheStore,
                workspaceService,
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(WORKSPACE_ID);
        LocaleContextHolder.setLocale(Locale.ENGLISH);
    }

    @AfterEach
    void tearDown() {
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    void generate_aiNotUsable_returnsNotConfiguredWithoutRiskOrInvocation() {
        when(aiFeatureGate.isAiUsable()).thenReturn(false);

        DealRationaleDto result = service.generate(DEAL_ID);

        assertUnavailable(result, "not_configured");
        verify(dealRiskService, never()).assessDeal(anyInt(), anyInt());
        verify(aiInvocationService, never()).completeStructured(any(), any());
    }

    @Test
    void generate_noneRisk_returnsNotAtRiskWithoutInvocation() {
        DealRiskDto risk = new DealRiskDto(
                DEAL_ID, 125000, "USD", "none", 0,
                List.of(new DealRiskFactor("stalled", "medium", Map.of("daysSinceTouch", 31))),
                "2026-07-09 18:30:00");
        when(aiFeatureGate.isAiUsable()).thenReturn(true);
        when(dealRiskService.assessDeal(WORKSPACE_ID, DEAL_ID)).thenReturn(risk);

        DealRationaleDto result = service.generate(DEAL_ID);

        assertUnavailable(result, "not_at_risk");
        verify(dealRiskRationaleAssembler, never()).assemble(anyInt(), anyInt(), any());
        verify(aiInvocationService, never()).completeStructured(any(), any());
    }

    @Test
    void generate_emptyFactors_returnsNotAtRiskWithoutInvocation() {
        DealRiskDto risk = new DealRiskDto(
                DEAL_ID, 125000, "USD", "medium", 25, List.of(), "2026-07-09 18:30:00");
        when(aiFeatureGate.isAiUsable()).thenReturn(true);
        when(dealRiskService.assessDeal(WORKSPACE_ID, DEAL_ID)).thenReturn(risk);

        DealRationaleDto result = service.generate(DEAL_ID);

        assertUnavailable(result, "not_at_risk");
        verify(aiInvocationService, never()).completeStructured(any(), any());
    }

    @Test
    void generate_happyPath_returnsDemaskedNarrativeActionsPersistsAndWarnings() {
        arrangeMiss(assembly());
        when(aiInvocationService.completeStructured(any(AiInvocation.class), eq(DealRiskRationaleContent.class)))
                .thenReturn(new AiStructuredOutcome.Parsed<>(
                        new DealRiskRationaleContent(
                                "The deal is overdue and quiet.",
                                List.of("Contact Mina Patel today.", "Confirm the budget.")),
                        2, 120, 45, "end_turn"));

        DealRationaleDto result = service.generate(DEAL_ID);

        assertTrue(result.isAvailable());
        assertEquals("The deal is overdue and quiet.", result.getNarrative());
        assertEquals(List.of("Contact Mina Patel today.", "Confirm the budget."), result.getActions());
        assertEquals(
                "The deal is overdue and quiet.\n• Contact Mina Patel today.\n• Confirm the budget.",
                result.getRationale());
        assertEquals(NOW.toString(), result.getGeneratedAt());
        assertEquals(2, result.getWarnings());
        verify(aiOutputCacheStore).save(eq(WORKSPACE_ID), eq(CACHE_FEATURE), eq(DEAL_ID),
                eq(AiOutputCacheStore.NO_SUBJECT), eq(HASH), any(DealRiskRationaleContent.class), eq(2),
                eq(NOW.toString()));
    }

    @Test
    void generate_cacheHit_reusesStoredRationaleWithoutInvocation() {
        RationaleAssembly assembly = assembly();
        DealRiskDto risk = atRisk();
        when(aiFeatureGate.isAiUsable()).thenReturn(true);
        when(dealRiskService.assessDeal(WORKSPACE_ID, DEAL_ID)).thenReturn(risk);
        when(dealRiskRationaleAssembler.assemble(WORKSPACE_ID, DEAL_ID, risk)).thenReturn(assembly);
        when(aiOutputCacheStore.contentHash(assembly.prompt(), assembly.context())).thenReturn(HASH);
        when(aiOutputCacheStore.find(WORKSPACE_ID, CACHE_FEATURE, DEAL_ID, AiOutputCacheStore.NO_SUBJECT))
                .thenReturn(Optional.of(row(HASH, 1, "2026-07-01T09:00:00Z")));
        when(aiOutputCacheStore.read("payload", DealRiskRationaleContent.class))
                .thenReturn(Optional.of(new DealRiskRationaleContent("Stored narrative.", List.of("Stored action."))));

        DealRationaleDto result = service.generate(DEAL_ID);

        assertTrue(result.isAvailable());
        assertEquals("Stored narrative.", result.getNarrative());
        assertEquals(List.of("Stored action."), result.getActions());
        assertEquals("2026-07-01T09:00:00Z", result.getGeneratedAt());
        assertEquals(1, result.getWarnings());
        verify(aiInvocationService, never()).completeStructured(any(), any());
        verify(aiOutputCacheStore, never()).save(anyInt(), any(), anyInt(), anyInt(), any(), any(), anyInt(), any());
    }

    @Test
    void generate_contentHashMismatch_regenerates() {
        RationaleAssembly assembly = assembly();
        DealRiskDto risk = atRisk();
        when(aiFeatureGate.isAiUsable()).thenReturn(true);
        when(dealRiskService.assessDeal(WORKSPACE_ID, DEAL_ID)).thenReturn(risk);
        when(dealRiskRationaleAssembler.assemble(WORKSPACE_ID, DEAL_ID, risk)).thenReturn(assembly);
        when(aiOutputCacheStore.contentHash(assembly.prompt(), assembly.context())).thenReturn(HASH);
        when(aiOutputCacheStore.find(WORKSPACE_ID, CACHE_FEATURE, DEAL_ID, AiOutputCacheStore.NO_SUBJECT))
                .thenReturn(Optional.of(row("stale-hash", 0, "2026-07-01T09:00:00Z")));
        when(aiInvocationService.completeStructured(any(AiInvocation.class), eq(DealRiskRationaleContent.class)))
                .thenReturn(new AiStructuredOutcome.Parsed<>(
                        new DealRiskRationaleContent("Fresh narrative.", List.of()), 0, 20, 10, "end_turn"));

        DealRationaleDto result = service.generate(DEAL_ID);

        assertEquals("Fresh narrative.", result.getNarrative());
        assertEquals(NOW.toString(), result.getGeneratedAt());
        verify(aiInvocationService).completeStructured(any(AiInvocation.class), eq(DealRiskRationaleContent.class));
        verify(aiOutputCacheStore).save(eq(WORKSPACE_ID), eq(CACHE_FEATURE), eq(DEAL_ID),
                eq(AiOutputCacheStore.NO_SUBJECT), eq(HASH), any(DealRiskRationaleContent.class), eq(0),
                eq(NOW.toString()));
    }

    @Test
    void generate_refresh_bypassesCacheAndRegenerates() {
        RationaleAssembly assembly = assembly();
        DealRiskDto risk = atRisk();
        when(aiFeatureGate.isAiUsable()).thenReturn(true);
        when(dealRiskService.assessDeal(WORKSPACE_ID, DEAL_ID)).thenReturn(risk);
        when(dealRiskRationaleAssembler.assemble(WORKSPACE_ID, DEAL_ID, risk)).thenReturn(assembly);
        when(aiOutputCacheStore.contentHash(assembly.prompt(), assembly.context())).thenReturn(HASH);
        when(aiInvocationService.completeStructured(any(AiInvocation.class), eq(DealRiskRationaleContent.class)))
                .thenReturn(new AiStructuredOutcome.Parsed<>(
                        new DealRiskRationaleContent("Fresh narrative.", List.of()), 0, 20, 10, "end_turn"));

        DealRationaleDto result = service.generate(DEAL_ID, true);

        assertEquals("Fresh narrative.", result.getNarrative());
        verify(aiInvocationService).completeStructured(any(AiInvocation.class), eq(DealRiskRationaleContent.class));
        verify(aiOutputCacheStore, never()).find(anyInt(), any(), anyInt(), anyInt());
        verify(aiOutputCacheStore).save(eq(WORKSPACE_ID), eq(CACHE_FEATURE), eq(DEAL_ID),
                eq(AiOutputCacheStore.NO_SUBJECT), eq(HASH), any(DealRiskRationaleContent.class), eq(0),
                eq(NOW.toString()));
    }

    @Test
    void generate_malformedOutcome_returnsProviderErrorAndDoesNotPersist() {
        arrangeMiss(assembly());
        when(aiInvocationService.completeStructured(any(AiInvocation.class), eq(DealRiskRationaleContent.class)))
                .thenReturn(new AiStructuredOutcome.Malformed<>(
                        AiStructuredOutcome.REASON_TRUNCATED, 200, 200, "length"));

        DealRationaleDto result = service.generate(DEAL_ID);

        assertUnavailable(result, "provider_error");
        verify(aiOutputCacheStore, never()).save(anyInt(), any(), anyInt(), anyInt(), any(), any(), anyInt(), any());
    }

    @Test
    void generate_blankNarrative_returnsProviderErrorAndDoesNotPersist() {
        arrangeMiss(assembly());
        when(aiInvocationService.completeStructured(any(AiInvocation.class), eq(DealRiskRationaleContent.class)))
                .thenReturn(new AiStructuredOutcome.Parsed<>(
                        new DealRiskRationaleContent("   ", List.of("Do something.")), 0, 20, 5, "end_turn"));

        DealRationaleDto result = service.generate(DEAL_ID);

        assertUnavailable(result, "provider_error");
        verify(aiOutputCacheStore, never()).save(anyInt(), any(), anyInt(), anyInt(), any(), any(), anyInt(), any());
    }

    @Test
    void generate_maskingLeak_returnsProviderError() {
        arrangeInvocationFailure(new MaskingLeakException("blocked outbound identifier"));

        assertUnavailable(service.generate(DEAL_ID), "provider_error");
    }

    @Test
    void generate_providerFailure_returnsProviderError() {
        arrangeInvocationFailure(new AiProviderException("provider unavailable"));

        assertUnavailable(service.generate(DEAL_ID), "provider_error");
    }

    @Test
    void generate_forbiddenInvocation_returnsNotConfigured() {
        arrangeInvocationFailure(new ForbiddenException("AI features are not available"));

        assertUnavailable(service.generate(DEAL_ID), "not_configured");
    }

    private void arrangeMiss(RationaleAssembly assembly) {
        DealRiskDto risk = atRisk();
        when(aiFeatureGate.isAiUsable()).thenReturn(true);
        when(dealRiskService.assessDeal(WORKSPACE_ID, DEAL_ID)).thenReturn(risk);
        when(dealRiskRationaleAssembler.assemble(WORKSPACE_ID, DEAL_ID, risk)).thenReturn(assembly);
        when(aiOutputCacheStore.contentHash(assembly.prompt(), assembly.context())).thenReturn(HASH);
        when(aiOutputCacheStore.find(WORKSPACE_ID, CACHE_FEATURE, DEAL_ID, AiOutputCacheStore.NO_SUBJECT))
                .thenReturn(Optional.empty());
    }

    private void arrangeInvocationFailure(RuntimeException exception) {
        arrangeMiss(assembly());
        when(aiInvocationService.completeStructured(any(AiInvocation.class), eq(DealRiskRationaleContent.class)))
                .thenThrow(exception);
    }

    private static DealRiskDto atRisk() {
        DealRiskFactor factor = new DealRiskFactor("stalled", "medium", Map.of("daysSinceTouch", 31));
        return new DealRiskDto(
                DEAL_ID, 125000, "USD", "medium", 25, List.of(factor), "2026-07-09 18:30:00");
    }

    private static RationaleAssembly assembly() {
        MaskingContext context = new MaskingContext();
        String person = MaskingEngine.maskField(EntityKind.PERSON, "Mina Patel", context);
        MaskedPrompt prompt = PromptAssembly.builder()
                .system("Use only the supplied risk factors.")
                .userTurn("Stakeholder: " + person)
                .build();
        return new RationaleAssembly(context, prompt);
    }

    private static AiOutputCache row(String contentHash, int warnings, String generatedAt) {
        AiOutputCache row = new AiOutputCache();
        row.setContentHash(contentHash);
        row.setPayload("payload");
        row.setWarnings(warnings);
        row.setGeneratedAt(generatedAt);
        return row;
    }

    private static void assertUnavailable(DealRationaleDto result, String reason) {
        assertFalse(result.isAvailable());
        assertEquals(DEAL_ID, result.getDealId());
        assertEquals(reason, result.getReason());
        assertNull(result.getNarrative());
        assertNull(result.getActions());
        assertNull(result.getRationale());
    }
}
