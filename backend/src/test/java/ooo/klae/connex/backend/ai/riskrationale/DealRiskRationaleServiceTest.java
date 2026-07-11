package ooo.klae.connex.backend.ai.riskrationale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.ai.AiCompletionOutcome;
import ooo.klae.connex.backend.ai.AiFeatureGate;
import ooo.klae.connex.backend.ai.AiInvocation;
import ooo.klae.connex.backend.ai.AiInvocationService;
import ooo.klae.connex.backend.ai.masking.EntityKind;
import ooo.klae.connex.backend.ai.masking.MaskedPrompt;
import ooo.klae.connex.backend.ai.masking.MaskingContext;
import ooo.klae.connex.backend.ai.masking.MaskingEngine;
import ooo.klae.connex.backend.ai.masking.MaskingLeakException;
import ooo.klae.connex.backend.ai.masking.PromptAssembly;
import ooo.klae.connex.backend.ai.provider.AiProviderException;
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
    private static final Instant NOW = Instant.parse("2026-07-09T18:30:00Z");

    @Mock private DealRiskRationaleAssembler dealRiskRationaleAssembler;
    @Mock private AiInvocationService aiInvocationService;
    @Mock private AiFeatureGate aiFeatureGate;
    @Mock private DealRiskService dealRiskService;
    @Mock private WorkspaceService workspaceService;

    private DealRiskRationaleService service;

    @BeforeEach
    void setUp() {
        service = new DealRiskRationaleService(
                dealRiskRationaleAssembler,
                aiInvocationService,
                aiFeatureGate,
                dealRiskService,
                workspaceService,
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(WORKSPACE_ID);
    }

    @Test
    void generate_aiNotUsable_returnsNotConfiguredWithoutRiskOrInvocation() {
        when(aiFeatureGate.isAiUsable()).thenReturn(false);

        DealRationaleDto result = service.generate(DEAL_ID);

        assertUnavailable(result, "not_configured");
        verify(dealRiskService, never()).assessDeal(anyInt(), anyInt());
        verify(dealRiskRationaleAssembler, never()).assemble(anyInt(), anyInt(), any());
        verify(aiInvocationService, never()).complete(any());
    }

    @Test
    void generate_noneRisk_returnsNotAtRiskWithoutInvocation() {
        DealRiskDto risk = new DealRiskDto(
                DEAL_ID,
                125000,
                "USD",
                "none",
                0,
                List.of(new DealRiskFactor("stalled", "medium", Map.of("daysSinceTouch", 31))),
                "2026-07-09 18:30:00");
        when(aiFeatureGate.isAiUsable()).thenReturn(true);
        when(dealRiskService.assessDeal(WORKSPACE_ID, DEAL_ID)).thenReturn(risk);

        DealRationaleDto result = service.generate(DEAL_ID);

        assertUnavailable(result, "not_at_risk");
        verify(dealRiskRationaleAssembler, never()).assemble(anyInt(), anyInt(), any());
        verify(aiInvocationService, never()).complete(any());
    }

    @Test
    void generate_emptyFactors_returnsNotAtRiskWithoutInvocation() {
        DealRiskDto risk = new DealRiskDto(
                DEAL_ID, 125000, "USD", "medium", 25, List.of(), "2026-07-09 18:30:00");
        when(aiFeatureGate.isAiUsable()).thenReturn(true);
        when(dealRiskService.assessDeal(WORKSPACE_ID, DEAL_ID)).thenReturn(risk);

        DealRationaleDto result = service.generate(DEAL_ID);

        assertUnavailable(result, "not_at_risk");
        verify(dealRiskRationaleAssembler, never()).assemble(anyInt(), anyInt(), any());
        verify(aiInvocationService, never()).complete(any());
    }

    @Test
    void generate_happyPath_returnsDemaskedRationaleAndWarnings() {
        DealRiskDto risk = atRisk();
        RationaleAssembly assembly = assembly();
        when(aiFeatureGate.isAiUsable()).thenReturn(true);
        when(dealRiskService.assessDeal(WORKSPACE_ID, DEAL_ID)).thenReturn(risk);
        when(dealRiskRationaleAssembler.assemble(WORKSPACE_ID, DEAL_ID, risk)).thenReturn(assembly);
        when(aiInvocationService.complete(any(AiInvocation.class)))
                .thenReturn(new AiCompletionOutcome(
                        "The deal is overdue and quiet. Contact Mina Patel today.",
                        2,
                        120,
                        45,
                        "end_turn"));

        DealRationaleDto result = service.generate(DEAL_ID);

        assertTrue(result.isAvailable());
        assertEquals("The deal is overdue and quiet. Contact Mina Patel today.", result.getRationale());
        assertEquals(NOW.toString(), result.getGeneratedAt());
        assertEquals(2, result.getWarnings());
        assertNull(result.getReason());

        ArgumentCaptor<AiInvocation> invocation = ArgumentCaptor.forClass(AiInvocation.class);
        verify(aiInvocationService).complete(invocation.capture());
        assertEquals("deal.risk_rationale", invocation.getValue().feature());
        assertSame(assembly.context(), invocation.getValue().context());
        assertSame(assembly.prompt(), invocation.getValue().prompt());
        assertEquals(DealRiskRationaleService.MAX_TOKENS, invocation.getValue().maxTokens());
        assertEquals(DealRiskRationaleService.TEMPERATURE, invocation.getValue().temperature());
    }

    @Test
    void generate_sameContextHash_reusesCachedRationale() {
        DealRiskDto risk = atRisk();
        RationaleAssembly assembly = assembly();
        when(aiFeatureGate.isAiUsable()).thenReturn(true);
        when(dealRiskService.assessDeal(WORKSPACE_ID, DEAL_ID)).thenReturn(risk);
        when(dealRiskRationaleAssembler.assemble(WORKSPACE_ID, DEAL_ID, risk)).thenReturn(assembly);
        when(aiInvocationService.complete(any(AiInvocation.class)))
                .thenReturn(new AiCompletionOutcome("Cached rationale", 0, 20, 10, "end_turn"));

        DealRationaleDto first = service.generate(DEAL_ID);
        DealRationaleDto second = service.generate(DEAL_ID);

        assertSame(first, second);
        verify(aiInvocationService, times(1)).complete(any(AiInvocation.class));
    }

    @Test
    void generate_blankCompletion_returnsProviderErrorAndDoesNotCache() {
        DealRiskDto risk = atRisk();
        RationaleAssembly assembly = assembly();
        when(aiFeatureGate.isAiUsable()).thenReturn(true);
        when(dealRiskService.assessDeal(WORKSPACE_ID, DEAL_ID)).thenReturn(risk);
        when(dealRiskRationaleAssembler.assemble(WORKSPACE_ID, DEAL_ID, risk)).thenReturn(assembly);
        when(aiInvocationService.complete(any(AiInvocation.class)))
                .thenReturn(new AiCompletionOutcome("   ", 0, 5, 0, "end_turn"));

        DealRationaleDto first = service.generate(DEAL_ID);
        DealRationaleDto second = service.generate(DEAL_ID);

        assertUnavailable(first, "provider_error");
        verify(aiInvocationService, times(2)).complete(any(AiInvocation.class));
    }

    @Test
    void generate_identitySwapWithIdenticalMaskedText_doesNotCollideInCache() {
        DealRiskDto risk = atRisk();
        MaskingContext firstContext = new MaskingContext();
        MaskingEngine.maskField(EntityKind.PERSON, "Alice Ng", firstContext);
        MaskingEngine.maskField(EntityKind.PERSON, "Bob Lee", firstContext);
        MaskingContext swappedContext = new MaskingContext();
        MaskingEngine.maskField(EntityKind.PERSON, "Bob Lee", swappedContext);
        MaskingEngine.maskField(EntityKind.PERSON, "Alice Ng", swappedContext);
        RationaleAssembly first = new RationaleAssembly(firstContext, twoPersonPrompt());
        RationaleAssembly swapped = new RationaleAssembly(swappedContext, twoPersonPrompt());

        when(aiFeatureGate.isAiUsable()).thenReturn(true);
        when(dealRiskService.assessDeal(WORKSPACE_ID, DEAL_ID)).thenReturn(risk);
        when(dealRiskRationaleAssembler.assemble(WORKSPACE_ID, DEAL_ID, risk)).thenReturn(first, swapped);
        when(aiInvocationService.complete(any(AiInvocation.class)))
                .thenReturn(new AiCompletionOutcome("first", 0, 5, 5, "end_turn"),
                        new AiCompletionOutcome("second", 0, 5, 5, "end_turn"));

        DealRationaleDto firstResult = service.generate(DEAL_ID);
        DealRationaleDto secondResult = service.generate(DEAL_ID);

        assertEquals("first", firstResult.getRationale());
        assertEquals("second", secondResult.getRationale());
        verify(aiInvocationService, times(2)).complete(any(AiInvocation.class));
    }

    private static MaskedPrompt twoPersonPrompt() {
        return PromptAssembly.builder()
                .system("Use only the supplied risk factors.")
                .userTurn("Owner: {{P1}}; person={{P2}}")
                .build();
    }

    @Test
    void generate_maskingLeak_returnsProviderError() {
        arrangeInvocationFailure(new MaskingLeakException("blocked outbound identifier"));

        DealRationaleDto result = service.generate(DEAL_ID);

        assertUnavailable(result, "provider_error");
    }

    @Test
    void generate_providerFailure_returnsProviderError() {
        arrangeInvocationFailure(new AiProviderException("provider unavailable"));

        DealRationaleDto result = service.generate(DEAL_ID);

        assertUnavailable(result, "provider_error");
    }

    @Test
    void generate_forbiddenInvocation_returnsNotConfigured() {
        arrangeInvocationFailure(new ForbiddenException("AI features are not available"));

        DealRationaleDto result = service.generate(DEAL_ID);

        assertUnavailable(result, "not_configured");
    }

    private void arrangeInvocationFailure(RuntimeException exception) {
        DealRiskDto risk = atRisk();
        when(aiFeatureGate.isAiUsable()).thenReturn(true);
        when(dealRiskService.assessDeal(WORKSPACE_ID, DEAL_ID)).thenReturn(risk);
        when(dealRiskRationaleAssembler.assemble(WORKSPACE_ID, DEAL_ID, risk)).thenReturn(assembly());
        when(aiInvocationService.complete(any(AiInvocation.class))).thenThrow(exception);
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

    private static void assertUnavailable(DealRationaleDto result, String reason) {
        assertFalse(result.isAvailable());
        assertEquals(DEAL_ID, result.getDealId());
        assertEquals(reason, result.getReason());
        assertNull(result.getRationale());
        assertNull(result.getGeneratedAt());
        assertEquals(0, result.getWarnings());
    }
}
