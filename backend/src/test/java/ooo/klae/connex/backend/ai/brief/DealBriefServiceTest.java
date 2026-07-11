package ooo.klae.connex.backend.ai.brief;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.ai.AiFeatureGate;
import ooo.klae.connex.backend.ai.AiInvocation;
import ooo.klae.connex.backend.ai.AiInvocationService;
import ooo.klae.connex.backend.ai.AiStructuredOutcome;
import ooo.klae.connex.backend.ai.masking.EntityKind;
import ooo.klae.connex.backend.ai.masking.MaskedPrompt;
import ooo.klae.connex.backend.ai.masking.MaskingContext;
import ooo.klae.connex.backend.ai.masking.MaskingEngine;
import ooo.klae.connex.backend.ai.masking.MaskingLeakException;
import ooo.klae.connex.backend.ai.masking.PromptAssembly;
import ooo.klae.connex.backend.ai.provider.AiProviderException;
import ooo.klae.connex.backend.dto.DealBriefDto;
import ooo.klae.connex.backend.services.WorkspaceService;

@ExtendWith(MockitoExtension.class)
class DealBriefServiceTest {
    private static final int WORKSPACE_ID = 17;
    private static final int DEAL_ID = 29;
    private static final Instant NOW = Instant.parse("2026-07-09T18:30:00Z");

    @Mock private DealBriefAssembler dealBriefAssembler;
    @Mock private AiInvocationService aiInvocationService;
    @Mock private AiFeatureGate aiFeatureGate;
    @Mock private WorkspaceService workspaceService;

    private DealBriefService service;

    @BeforeEach
    void setUp() {
        service = new DealBriefService(
                dealBriefAssembler,
                aiInvocationService,
                aiFeatureGate,
                workspaceService,
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(WORKSPACE_ID);
    }

    @Test
    void generate_aiNotUsable_returnsNotConfiguredWithoutInvocation() {
        when(aiFeatureGate.isAiUsable()).thenReturn(false);

        DealBriefDto result = service.generate(DEAL_ID);

        assertFalse(result.isAvailable());
        assertEquals(DEAL_ID, result.getDealId());
        assertEquals("not_configured", result.getReason());
        assertNull(result.getSections());
        assertNull(result.getBrief());
        assertNull(result.getGeneratedAt());
        assertEquals(0, result.getWarnings());
        verify(aiInvocationService, never()).completeStructured(any(), any());
        verify(dealBriefAssembler, never()).assemble(anyInt(), anyInt());
    }

    @Test
    void generate_happyPath_returnsDemaskedSectionsAndWarnings() {
        BriefAssembly assembly = assembly();
        when(aiFeatureGate.isAiUsable()).thenReturn(true);
        when(dealBriefAssembler.assemble(WORKSPACE_ID, DEAL_ID)).thenReturn(assembly);
        when(aiInvocationService.completeStructured(any(AiInvocation.class), eq(DealBriefContent.class)))
                .thenReturn(new AiStructuredOutcome.Parsed<>(
                        new DealBriefContent(List.of(
                                new DealBriefContent.Section("Who they are", "Mina Patel leads the call."),
                                new DealBriefContent.Section("Deal status", "Proposal sent."))),
                        2,
                        120,
                        45,
                        "end_turn"));

        DealBriefDto result = service.generate(DEAL_ID);

        assertTrue(result.isAvailable());
        assertEquals(2, result.getSections().size());
        assertEquals("Who they are", result.getSections().get(0).title());
        assertEquals("Mina Patel leads the call.", result.getSections().get(0).body());
        assertEquals(
                "Who they are\nMina Patel leads the call.\n\nDeal status\nProposal sent.",
                result.getBrief());
        assertEquals(NOW.toString(), result.getGeneratedAt());
        assertEquals(2, result.getWarnings());
        assertNull(result.getReason());

        ArgumentCaptor<AiInvocation> invocation = ArgumentCaptor.forClass(AiInvocation.class);
        verify(aiInvocationService).completeStructured(invocation.capture(), eq(DealBriefContent.class));
        assertEquals("deal.brief", invocation.getValue().feature());
        assertSame(assembly.context(), invocation.getValue().context());
        assertSame(assembly.prompt(), invocation.getValue().prompt());
        assertEquals(DealBriefService.MAX_TOKENS, invocation.getValue().maxTokens());
        assertEquals(DealBriefService.TEMPERATURE, invocation.getValue().temperature());
    }

    @Test
    void generate_providerFailure_returnsProviderError() {
        when(aiFeatureGate.isAiUsable()).thenReturn(true);
        when(dealBriefAssembler.assemble(WORKSPACE_ID, DEAL_ID)).thenReturn(assembly());
        when(aiInvocationService.completeStructured(any(AiInvocation.class), eq(DealBriefContent.class)))
                .thenThrow(new AiProviderException("provider unavailable"));

        DealBriefDto result = service.generate(DEAL_ID);

        assertFalse(result.isAvailable());
        assertEquals("provider_error", result.getReason());
        assertNull(result.getSections());
        assertNull(result.getBrief());
        assertNull(result.getGeneratedAt());
    }

    @Test
    void generate_maskingLeak_returnsProviderError() {
        when(aiFeatureGate.isAiUsable()).thenReturn(true);
        when(dealBriefAssembler.assemble(WORKSPACE_ID, DEAL_ID)).thenReturn(assembly());
        when(aiInvocationService.completeStructured(any(AiInvocation.class), eq(DealBriefContent.class)))
                .thenThrow(new MaskingLeakException("blocked outbound identifier"));

        DealBriefDto result = service.generate(DEAL_ID);

        assertFalse(result.isAvailable());
        assertEquals("provider_error", result.getReason());
        assertNull(result.getSections());
        assertNull(result.getBrief());
        assertNull(result.getGeneratedAt());
    }

    @Test
    void generate_malformedOutcome_returnsProviderErrorAndDoesNotCache() {
        when(aiFeatureGate.isAiUsable()).thenReturn(true);
        when(dealBriefAssembler.assemble(WORKSPACE_ID, DEAL_ID)).thenReturn(assembly());
        when(aiInvocationService.completeStructured(any(AiInvocation.class), eq(DealBriefContent.class)))
                .thenReturn(new AiStructuredOutcome.Malformed<>(
                        AiStructuredOutcome.REASON_MALFORMED, 200, 120, "end_turn"));

        DealBriefDto first = service.generate(DEAL_ID);
        service.generate(DEAL_ID);

        assertFalse(first.isAvailable());
        assertEquals("provider_error", first.getReason());
        verify(aiInvocationService, times(2))
                .completeStructured(any(AiInvocation.class), eq(DealBriefContent.class));
    }

    @Test
    void generate_noValidSections_returnsProviderError() {
        when(aiFeatureGate.isAiUsable()).thenReturn(true);
        when(dealBriefAssembler.assemble(WORKSPACE_ID, DEAL_ID)).thenReturn(assembly());
        when(aiInvocationService.completeStructured(any(AiInvocation.class), eq(DealBriefContent.class)))
                .thenReturn(new AiStructuredOutcome.Parsed<>(
                        new DealBriefContent(List.of(new DealBriefContent.Section("  ", "  "))),
                        0, 20, 5, "end_turn"));

        DealBriefDto result = service.generate(DEAL_ID);

        assertFalse(result.isAvailable());
        assertEquals("provider_error", result.getReason());
    }

    @Test
    void generate_sameContextHash_reusesCachedBrief() {
        BriefAssembly assembly = assembly();
        when(aiFeatureGate.isAiUsable()).thenReturn(true);
        when(dealBriefAssembler.assemble(WORKSPACE_ID, DEAL_ID)).thenReturn(assembly);
        when(aiInvocationService.completeStructured(any(AiInvocation.class), eq(DealBriefContent.class)))
                .thenReturn(new AiStructuredOutcome.Parsed<>(
                        new DealBriefContent(List.of(new DealBriefContent.Section("Who they are", "Cached."))),
                        0, 20, 10, "end_turn"));

        DealBriefDto first = service.generate(DEAL_ID);
        DealBriefDto second = service.generate(DEAL_ID);

        assertSame(first, second);
        verify(aiInvocationService, times(1))
                .completeStructured(any(AiInvocation.class), eq(DealBriefContent.class));
    }

    @Test
    void generate_changedIdentifierDictionary_doesNotReuseCachedBrief() {
        when(aiFeatureGate.isAiUsable()).thenReturn(true);
        when(dealBriefAssembler.assemble(WORKSPACE_ID, DEAL_ID))
                .thenReturn(assembly("Mina Patel"), assembly("Mina Shah"));
        when(aiInvocationService.completeStructured(any(AiInvocation.class), eq(DealBriefContent.class)))
                .thenReturn(
                        new AiStructuredOutcome.Parsed<>(
                                new DealBriefContent(List.of(new DealBriefContent.Section("Who they are", "First."))),
                                0, 20, 10, "end_turn"),
                        new AiStructuredOutcome.Parsed<>(
                                new DealBriefContent(List.of(new DealBriefContent.Section("Who they are", "Second."))),
                                0, 20, 10, "end_turn"));

        DealBriefDto first = service.generate(DEAL_ID);
        DealBriefDto second = service.generate(DEAL_ID);

        assertEquals("First.", first.getSections().get(0).body());
        assertEquals("Second.", second.getSections().get(0).body());
        verify(aiInvocationService, times(2))
                .completeStructured(any(AiInvocation.class), eq(DealBriefContent.class));
    }

    private static BriefAssembly assembly() {
        return assembly("Mina Patel");
    }

    private static BriefAssembly assembly(String personName) {
        MaskingContext context = new MaskingContext();
        String person = MaskingEngine.maskField(EntityKind.PERSON, personName, context);
        MaskedPrompt prompt = PromptAssembly.builder()
                .system("Use only the supplied context.")
                .userTurn("Stakeholder: " + person)
                .build();
        return new BriefAssembly(context, prompt);
    }
}
