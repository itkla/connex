package ooo.klae.connex.backend.ai.brief;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
import ooo.klae.connex.backend.dto.DealBriefDto;
import ooo.klae.connex.backend.services.WorkspaceService;

@ExtendWith(MockitoExtension.class)
class DealBriefServiceTest {
    private static final int WORKSPACE_ID = 17;
    private static final int DEAL_ID = 29;
    private static final String FEATURE = "deal.brief";
    private static final String HASH = "content-hash-1";
    private static final Instant NOW = Instant.parse("2026-07-09T18:30:00Z");

    @Mock private DealBriefAssembler dealBriefAssembler;
    @Mock private AiInvocationService aiInvocationService;
    @Mock private AiFeatureGate aiFeatureGate;
    @Mock private AiOutputCacheStore aiOutputCacheStore;
    @Mock private WorkspaceService workspaceService;

    private DealBriefService service;

    @BeforeEach
    void setUp() {
        service = new DealBriefService(
                dealBriefAssembler,
                aiInvocationService,
                aiFeatureGate,
                aiOutputCacheStore,
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
        verify(aiInvocationService, never()).completeStructured(any(), any());
        verify(dealBriefAssembler, never()).assemble(anyInt(), anyInt());
    }

    @Test
    void generate_happyPath_returnsDemaskedSectionsPersistsAndWarnings() {
        BriefAssembly assembly = assembly();
        arrangeMiss(assembly);
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
        assertEquals(NOW.toString(), result.getGeneratedAt());
        assertEquals(2, result.getWarnings());

        ArgumentCaptor<AiInvocation> invocation = ArgumentCaptor.forClass(AiInvocation.class);
        verify(aiInvocationService).completeStructured(invocation.capture(), eq(DealBriefContent.class));
        assertEquals(FEATURE, invocation.getValue().feature());
        assertEquals(DealBriefService.MAX_TOKENS, invocation.getValue().maxTokens());
        verify(aiOutputCacheStore).save(eq(WORKSPACE_ID), eq(FEATURE), eq(DEAL_ID),
                eq(AiOutputCacheStore.NO_SUBJECT), eq(HASH), any(DealBriefContent.class), eq(2), eq(NOW.toString()));
    }

    @Test
    void generate_cacheHit_reusesStoredBriefWithoutInvocation() {
        BriefAssembly assembly = assembly();
        when(aiFeatureGate.isAiUsable()).thenReturn(true);
        when(dealBriefAssembler.assemble(WORKSPACE_ID, DEAL_ID)).thenReturn(assembly);
        when(aiOutputCacheStore.contentHash(assembly.prompt(), assembly.context())).thenReturn(HASH);
        when(aiOutputCacheStore.find(WORKSPACE_ID, FEATURE, DEAL_ID, AiOutputCacheStore.NO_SUBJECT))
                .thenReturn(Optional.of(row(HASH, 3, "2026-07-01T09:00:00Z")));
        when(aiOutputCacheStore.read("payload", DealBriefContent.class))
                .thenReturn(Optional.of(new DealBriefContent(List.of(
                        new DealBriefContent.Section("Who they are", "Stored brief.")))));

        DealBriefDto result = service.generate(DEAL_ID);

        assertTrue(result.isAvailable());
        assertEquals("Stored brief.", result.getSections().get(0).body());
        assertEquals("2026-07-01T09:00:00Z", result.getGeneratedAt());
        assertEquals(3, result.getWarnings());
        verify(aiInvocationService, never()).completeStructured(any(), any());
        verify(aiOutputCacheStore, never()).save(anyInt(), any(), anyInt(), anyInt(), any(), any(), anyInt(), any());
    }

    @Test
    void generate_contentHashMismatch_regenerates() {
        BriefAssembly assembly = assembly();
        when(aiFeatureGate.isAiUsable()).thenReturn(true);
        when(dealBriefAssembler.assemble(WORKSPACE_ID, DEAL_ID)).thenReturn(assembly);
        when(aiOutputCacheStore.contentHash(assembly.prompt(), assembly.context())).thenReturn(HASH);
        when(aiOutputCacheStore.find(WORKSPACE_ID, FEATURE, DEAL_ID, AiOutputCacheStore.NO_SUBJECT))
                .thenReturn(Optional.of(row("stale-hash", 0, "2026-07-01T09:00:00Z")));
        when(aiInvocationService.completeStructured(any(AiInvocation.class), eq(DealBriefContent.class)))
                .thenReturn(new AiStructuredOutcome.Parsed<>(
                        new DealBriefContent(List.of(new DealBriefContent.Section("Who they are", "Fresh."))),
                        0, 20, 10, "end_turn"));

        DealBriefDto result = service.generate(DEAL_ID);

        assertEquals("Fresh.", result.getSections().get(0).body());
        assertEquals(NOW.toString(), result.getGeneratedAt());
        verify(aiInvocationService).completeStructured(any(AiInvocation.class), eq(DealBriefContent.class));
        verify(aiOutputCacheStore).save(eq(WORKSPACE_ID), eq(FEATURE), eq(DEAL_ID),
                eq(AiOutputCacheStore.NO_SUBJECT), eq(HASH), any(DealBriefContent.class), eq(0), eq(NOW.toString()));
    }

    @Test
    void generate_refresh_bypassesCacheAndRegenerates() {
        BriefAssembly assembly = assembly();
        when(aiFeatureGate.isAiUsable()).thenReturn(true);
        when(dealBriefAssembler.assemble(WORKSPACE_ID, DEAL_ID)).thenReturn(assembly);
        when(aiOutputCacheStore.contentHash(assembly.prompt(), assembly.context())).thenReturn(HASH);
        when(aiInvocationService.completeStructured(any(AiInvocation.class), eq(DealBriefContent.class)))
                .thenReturn(new AiStructuredOutcome.Parsed<>(
                        new DealBriefContent(List.of(new DealBriefContent.Section("Who they are", "Fresh take."))),
                        0, 20, 10, "end_turn"));

        DealBriefDto result = service.generate(DEAL_ID, true);

        assertEquals("Fresh take.", result.getSections().get(0).body());
        verify(aiInvocationService).completeStructured(any(AiInvocation.class), eq(DealBriefContent.class));
        verify(aiOutputCacheStore, never()).find(anyInt(), any(), anyInt(), anyInt());
        verify(aiOutputCacheStore).save(eq(WORKSPACE_ID), eq(FEATURE), eq(DEAL_ID),
                eq(AiOutputCacheStore.NO_SUBJECT), eq(HASH), any(DealBriefContent.class), eq(0), eq(NOW.toString()));
    }

    @Test
    void generate_providerFailure_returnsProviderError() {
        arrangeMiss(assembly());
        when(aiInvocationService.completeStructured(any(AiInvocation.class), eq(DealBriefContent.class)))
                .thenThrow(new AiProviderException("provider unavailable"));

        DealBriefDto result = service.generate(DEAL_ID);

        assertFalse(result.isAvailable());
        assertEquals("provider_error", result.getReason());
        verify(aiOutputCacheStore, never()).save(anyInt(), any(), anyInt(), anyInt(), any(), any(), anyInt(), any());
    }

    @Test
    void generate_maskingLeak_returnsProviderError() {
        arrangeMiss(assembly());
        when(aiInvocationService.completeStructured(any(AiInvocation.class), eq(DealBriefContent.class)))
                .thenThrow(new MaskingLeakException("blocked outbound identifier"));

        DealBriefDto result = service.generate(DEAL_ID);

        assertFalse(result.isAvailable());
        assertEquals("provider_error", result.getReason());
    }

    @Test
    void generate_malformedOutcome_returnsProviderErrorAndDoesNotPersist() {
        arrangeMiss(assembly());
        when(aiInvocationService.completeStructured(any(AiInvocation.class), eq(DealBriefContent.class)))
                .thenReturn(new AiStructuredOutcome.Malformed<>(
                        AiStructuredOutcome.REASON_MALFORMED, 200, 120, "end_turn"));

        DealBriefDto result = service.generate(DEAL_ID);

        assertFalse(result.isAvailable());
        assertEquals("provider_error", result.getReason());
        verify(aiOutputCacheStore, never()).save(anyInt(), any(), anyInt(), anyInt(), any(), any(), anyInt(), any());
    }

    @Test
    void generate_noValidSections_returnsProviderError() {
        arrangeMiss(assembly());
        when(aiInvocationService.completeStructured(any(AiInvocation.class), eq(DealBriefContent.class)))
                .thenReturn(new AiStructuredOutcome.Parsed<>(
                        new DealBriefContent(List.of(new DealBriefContent.Section("  ", "  "))),
                        0, 20, 5, "end_turn"));

        DealBriefDto result = service.generate(DEAL_ID);

        assertFalse(result.isAvailable());
        assertEquals("provider_error", result.getReason());
        verify(aiOutputCacheStore, never()).save(anyInt(), any(), anyInt(), anyInt(), any(), any(), anyInt(), any());
    }

    private void arrangeMiss(BriefAssembly assembly) {
        when(aiFeatureGate.isAiUsable()).thenReturn(true);
        when(dealBriefAssembler.assemble(WORKSPACE_ID, DEAL_ID)).thenReturn(assembly);
        when(aiOutputCacheStore.contentHash(assembly.prompt(), assembly.context())).thenReturn(HASH);
        when(aiOutputCacheStore.find(WORKSPACE_ID, FEATURE, DEAL_ID, AiOutputCacheStore.NO_SUBJECT))
                .thenReturn(Optional.empty());
    }

    private static AiOutputCache row(String contentHash, int warnings, String generatedAt) {
        AiOutputCache row = new AiOutputCache();
        row.setContentHash(contentHash);
        row.setPayload("payload");
        row.setWarnings(warnings);
        row.setGeneratedAt(generatedAt);
        return row;
    }

    private static BriefAssembly assembly() {
        MaskingContext context = new MaskingContext();
        String person = MaskingEngine.maskField(EntityKind.PERSON, "Mina Patel", context);
        MaskedPrompt prompt = PromptAssembly.builder()
                .system("Use only the supplied context.")
                .userTurn("Stakeholder: " + person)
                .build();
        return new BriefAssembly(context, prompt);
    }
}
