package ooo.klae.connex.backend.ai.brief;

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
        assertNull(result.getBrief());
        assertNull(result.getGeneratedAt());
        assertEquals(0, result.getWarnings());
        verify(aiInvocationService, never()).complete(any());
        verify(dealBriefAssembler, never()).assemble(anyInt(), anyInt());
    }

    @Test
    void generate_happyPath_returnsDemaskedBriefAndWarnings() {
        BriefAssembly assembly = assembly();
        when(aiFeatureGate.isAiUsable()).thenReturn(true);
        when(dealBriefAssembler.assemble(WORKSPACE_ID, DEAL_ID)).thenReturn(assembly);
        when(aiInvocationService.complete(any(AiInvocation.class)))
                .thenReturn(new AiCompletionOutcome("Mina Patel should lead the call.", 2, 120, 45, "end_turn"));

        DealBriefDto result = service.generate(DEAL_ID);

        assertTrue(result.isAvailable());
        assertEquals("Mina Patel should lead the call.", result.getBrief());
        assertEquals(NOW.toString(), result.getGeneratedAt());
        assertEquals(2, result.getWarnings());
        assertNull(result.getReason());

        ArgumentCaptor<AiInvocation> invocation = ArgumentCaptor.forClass(AiInvocation.class);
        verify(aiInvocationService).complete(invocation.capture());
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
        when(aiInvocationService.complete(any(AiInvocation.class)))
                .thenThrow(new AiProviderException("provider unavailable"));

        DealBriefDto result = service.generate(DEAL_ID);

        assertFalse(result.isAvailable());
        assertEquals("provider_error", result.getReason());
        assertNull(result.getBrief());
        assertNull(result.getGeneratedAt());
    }

    @Test
    void generate_maskingLeak_returnsProviderError() {
        when(aiFeatureGate.isAiUsable()).thenReturn(true);
        when(dealBriefAssembler.assemble(WORKSPACE_ID, DEAL_ID)).thenReturn(assembly());
        when(aiInvocationService.complete(any(AiInvocation.class)))
                .thenThrow(new MaskingLeakException("blocked outbound identifier"));

        DealBriefDto result = service.generate(DEAL_ID);

        assertFalse(result.isAvailable());
        assertEquals("provider_error", result.getReason());
        assertNull(result.getBrief());
        assertNull(result.getGeneratedAt());
    }

    @Test
    void generate_sameContextHash_reusesCachedBrief() {
        BriefAssembly assembly = assembly();
        when(aiFeatureGate.isAiUsable()).thenReturn(true);
        when(dealBriefAssembler.assemble(WORKSPACE_ID, DEAL_ID)).thenReturn(assembly);
        when(aiInvocationService.complete(any(AiInvocation.class)))
                .thenReturn(new AiCompletionOutcome("Cached brief", 0, 20, 10, "end_turn"));

        DealBriefDto first = service.generate(DEAL_ID);
        DealBriefDto second = service.generate(DEAL_ID);

        assertSame(first, second);
        verify(aiInvocationService, times(1)).complete(any(AiInvocation.class));
    }

    @Test
    void generate_changedIdentifierDictionary_doesNotReuseCachedBrief() {
        when(aiFeatureGate.isAiUsable()).thenReturn(true);
        when(dealBriefAssembler.assemble(WORKSPACE_ID, DEAL_ID))
                .thenReturn(assembly("Mina Patel"), assembly("Mina Shah"));
        when(aiInvocationService.complete(any(AiInvocation.class)))
                .thenReturn(new AiCompletionOutcome("First brief", 0, 20, 10, "end_turn"),
                        new AiCompletionOutcome("Second brief", 0, 20, 10, "end_turn"));

        DealBriefDto first = service.generate(DEAL_ID);
        DealBriefDto second = service.generate(DEAL_ID);

        assertEquals("First brief", first.getBrief());
        assertEquals("Second brief", second.getBrief());
        verify(aiInvocationService, times(2)).complete(any(AiInvocation.class));
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
