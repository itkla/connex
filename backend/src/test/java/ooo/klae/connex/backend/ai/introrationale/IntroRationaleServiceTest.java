package ooo.klae.connex.backend.ai.introrationale;

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
import ooo.klae.connex.backend.dto.IntroRationaleDto;
import ooo.klae.connex.backend.dto.IntroSuggestionDto;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.services.IntroductionService;
import ooo.klae.connex.backend.services.WorkspaceService;

@ExtendWith(MockitoExtension.class)
class IntroRationaleServiceTest {
    private static final int WORKSPACE_ID = 17;
    private static final int PERSON_A_ID = 29;
    private static final int PERSON_B_ID = 41;
    private static final Instant NOW = Instant.parse("2026-07-09T18:30:00Z");

    @Mock private IntroRationaleAssembler introRationaleAssembler;
    @Mock private AiInvocationService aiInvocationService;
    @Mock private AiFeatureGate aiFeatureGate;
    @Mock private IntroductionService introductionService;
    @Mock private WorkspaceService workspaceService;

    private IntroRationaleService service;

    @BeforeEach
    void setUp() {
        service = new IntroRationaleService(
                introRationaleAssembler,
                aiInvocationService,
                aiFeatureGate,
                introductionService,
                workspaceService,
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(WORKSPACE_ID);
    }

    @Test
    void generate_aiNotUsable_returnsNotConfiguredWithoutSuggestionOrInvocation() {
        when(aiFeatureGate.isAiUsable()).thenReturn(false);

        IntroRationaleDto result = service.generate(PERSON_B_ID, PERSON_A_ID);

        assertUnavailable(result, "not_configured");
        verify(introductionService, never()).computeSuggestions(anyInt(), anyInt());
        verify(introRationaleAssembler, never()).assemble(anyInt(), any());
        verify(aiInvocationService, never()).completeStructured(any(), any());
    }

    @Test
    void generate_pairNotInSuggestions_returnsNotASuggestionWithoutInvocation() {
        when(aiFeatureGate.isAiUsable()).thenReturn(true);
        when(introductionService.computeSuggestions(WORKSPACE_ID, IntroRationaleService.RESOLVE_LIMIT))
                .thenReturn(List.of(suggestion(7, 11)));

        IntroRationaleDto result = service.generate(PERSON_A_ID, PERSON_B_ID);

        assertUnavailable(result, "not_a_suggestion");
        verify(introRationaleAssembler, never()).assemble(anyInt(), any());
        verify(aiInvocationService, never()).completeStructured(any(), any());
    }

    @Test
    void generate_swappedPair_returnsDemaskedRationaleAndWarnings() {
        IntroSuggestionDto suggestion = suggestion(PERSON_A_ID, PERSON_B_ID);
        IntroRationaleAssembly assembly = assembly();
        when(aiFeatureGate.isAiUsable()).thenReturn(true);
        when(introductionService.computeSuggestions(WORKSPACE_ID, IntroRationaleService.RESOLVE_LIMIT))
                .thenReturn(List.of(suggestion));
        when(introRationaleAssembler.assemble(WORKSPACE_ID, suggestion)).thenReturn(assembly);
        when(aiInvocationService.completeStructured(any(AiInvocation.class), eq(IntroRationaleContent.class)))
                .thenReturn(new AiStructuredOutcome.Parsed<>(
                        new IntroRationaleContent(
                                "Alice and Bob share three trusted connections and complementary roles."),
                        2,
                        80,
                        24,
                        "end_turn"));

        IntroRationaleDto result = service.generate(PERSON_B_ID, PERSON_A_ID);

        assertTrue(result.isAvailable());
        assertEquals(PERSON_A_ID, result.getPersonAId());
        assertEquals(PERSON_B_ID, result.getPersonBId());
        assertEquals(
                "Alice and Bob share three trusted connections and complementary roles.",
                result.getRationale());
        assertEquals(NOW.toString(), result.getGeneratedAt());
        assertEquals(2, result.getWarnings());
        assertNull(result.getReason());

        verify(introductionService).computeSuggestions(WORKSPACE_ID, IntroRationaleService.RESOLVE_LIMIT);
        verify(introRationaleAssembler).assemble(WORKSPACE_ID, suggestion);
        ArgumentCaptor<AiInvocation> invocation = ArgumentCaptor.forClass(AiInvocation.class);
        verify(aiInvocationService).completeStructured(invocation.capture(), eq(IntroRationaleContent.class));
        assertEquals("intro.rationale", invocation.getValue().feature());
        assertSame(assembly.context(), invocation.getValue().context());
        assertSame(assembly.prompt(), invocation.getValue().prompt());
        assertEquals(IntroRationaleService.MAX_TOKENS, invocation.getValue().maxTokens());
        assertEquals(IntroRationaleService.TEMPERATURE, invocation.getValue().temperature());
    }

    @Test
    void generate_sameContextHash_reusesCachedRationale() {
        IntroSuggestionDto suggestion = suggestion(PERSON_A_ID, PERSON_B_ID);
        IntroRationaleAssembly assembly = assembly();
        when(aiFeatureGate.isAiUsable()).thenReturn(true);
        when(introductionService.computeSuggestions(WORKSPACE_ID, IntroRationaleService.RESOLVE_LIMIT))
                .thenReturn(List.of(suggestion));
        when(introRationaleAssembler.assemble(WORKSPACE_ID, suggestion)).thenReturn(assembly);
        when(aiInvocationService.completeStructured(any(AiInvocation.class), eq(IntroRationaleContent.class)))
                .thenReturn(new AiStructuredOutcome.Parsed<>(
                        new IntroRationaleContent("Cached rationale"), 0, 20, 10, "end_turn"));

        IntroRationaleDto first = service.generate(PERSON_A_ID, PERSON_B_ID);
        IntroRationaleDto second = service.generate(PERSON_B_ID, PERSON_A_ID);

        assertSame(first, second);
        verify(aiInvocationService, times(1))
                .completeStructured(any(AiInvocation.class), eq(IntroRationaleContent.class));
    }

    @Test
    void generate_malformedOutcome_returnsProviderErrorAndDoesNotCache() {
        IntroSuggestionDto suggestion = suggestion(PERSON_A_ID, PERSON_B_ID);
        IntroRationaleAssembly assembly = assembly();
        when(aiFeatureGate.isAiUsable()).thenReturn(true);
        when(introductionService.computeSuggestions(WORKSPACE_ID, IntroRationaleService.RESOLVE_LIMIT))
                .thenReturn(List.of(suggestion));
        when(introRationaleAssembler.assemble(WORKSPACE_ID, suggestion)).thenReturn(assembly);
        when(aiInvocationService.completeStructured(any(AiInvocation.class), eq(IntroRationaleContent.class)))
                .thenReturn(new AiStructuredOutcome.Malformed<>(
                        AiStructuredOutcome.REASON_MALFORMED, 5, 0, "end_turn"));

        IntroRationaleDto first = service.generate(PERSON_A_ID, PERSON_B_ID);
        IntroRationaleDto second = service.generate(PERSON_A_ID, PERSON_B_ID);

        assertUnavailable(first, "provider_error");
        assertUnavailable(second, "provider_error");
        verify(aiInvocationService, times(2))
                .completeStructured(any(AiInvocation.class), eq(IntroRationaleContent.class));
    }

    @Test
    void generate_blankRationale_returnsProviderErrorAndDoesNotCache() {
        IntroSuggestionDto suggestion = suggestion(PERSON_A_ID, PERSON_B_ID);
        IntroRationaleAssembly assembly = assembly();
        when(aiFeatureGate.isAiUsable()).thenReturn(true);
        when(introductionService.computeSuggestions(WORKSPACE_ID, IntroRationaleService.RESOLVE_LIMIT))
                .thenReturn(List.of(suggestion));
        when(introRationaleAssembler.assemble(WORKSPACE_ID, suggestion)).thenReturn(assembly);
        when(aiInvocationService.completeStructured(any(AiInvocation.class), eq(IntroRationaleContent.class)))
                .thenReturn(new AiStructuredOutcome.Parsed<>(
                        new IntroRationaleContent("   "), 0, 5, 0, "end_turn"));

        IntroRationaleDto first = service.generate(PERSON_A_ID, PERSON_B_ID);
        IntroRationaleDto second = service.generate(PERSON_A_ID, PERSON_B_ID);

        assertUnavailable(first, "provider_error");
        assertUnavailable(second, "provider_error");
        verify(aiInvocationService, times(2))
                .completeStructured(any(AiInvocation.class), eq(IntroRationaleContent.class));
    }

    @Test
    void generate_identitySwapWithIdenticalMaskedText_doesNotCollideInCache() {
        IntroSuggestionDto suggestion = suggestion(PERSON_A_ID, PERSON_B_ID);
        MaskingContext firstContext = new MaskingContext();
        MaskingEngine.maskField(EntityKind.PERSON, "Alice Ng", firstContext);
        MaskingEngine.maskField(EntityKind.PERSON, "Bob Lee", firstContext);
        MaskingContext swappedContext = new MaskingContext();
        MaskingEngine.maskField(EntityKind.PERSON, "Bob Lee", swappedContext);
        MaskingEngine.maskField(EntityKind.PERSON, "Alice Ng", swappedContext);
        IntroRationaleAssembly first = new IntroRationaleAssembly(firstContext, twoPersonPrompt());
        IntroRationaleAssembly swapped = new IntroRationaleAssembly(swappedContext, twoPersonPrompt());

        when(aiFeatureGate.isAiUsable()).thenReturn(true);
        when(introductionService.computeSuggestions(WORKSPACE_ID, IntroRationaleService.RESOLVE_LIMIT))
                .thenReturn(List.of(suggestion));
        when(introRationaleAssembler.assemble(WORKSPACE_ID, suggestion)).thenReturn(first, swapped);
        when(aiInvocationService.completeStructured(any(AiInvocation.class), eq(IntroRationaleContent.class)))
                .thenReturn(
                        new AiStructuredOutcome.Parsed<>(new IntroRationaleContent("first"), 0, 5, 5, "end_turn"),
                        new AiStructuredOutcome.Parsed<>(new IntroRationaleContent("second"), 0, 5, 5, "end_turn"));

        IntroRationaleDto firstResult = service.generate(PERSON_A_ID, PERSON_B_ID);
        IntroRationaleDto secondResult = service.generate(PERSON_A_ID, PERSON_B_ID);

        assertEquals("first", firstResult.getRationale());
        assertEquals("second", secondResult.getRationale());
        verify(aiInvocationService, times(2))
                .completeStructured(any(AiInvocation.class), eq(IntroRationaleContent.class));
    }

    @Test
    void generate_maskingLeak_returnsProviderError() {
        arrangeInvocationFailure(new MaskingLeakException("blocked outbound identifier"));

        IntroRationaleDto result = service.generate(PERSON_A_ID, PERSON_B_ID);

        assertUnavailable(result, "provider_error");
    }

    @Test
    void generate_providerFailure_returnsProviderError() {
        arrangeInvocationFailure(new AiProviderException("provider unavailable"));

        IntroRationaleDto result = service.generate(PERSON_A_ID, PERSON_B_ID);

        assertUnavailable(result, "provider_error");
    }

    @Test
    void generate_forbiddenInvocation_returnsNotConfigured() {
        arrangeInvocationFailure(new ForbiddenException("AI features are not available"));

        IntroRationaleDto result = service.generate(PERSON_A_ID, PERSON_B_ID);

        assertUnavailable(result, "not_configured");
    }

    private void arrangeInvocationFailure(RuntimeException exception) {
        IntroSuggestionDto suggestion = suggestion(PERSON_A_ID, PERSON_B_ID);
        when(aiFeatureGate.isAiUsable()).thenReturn(true);
        when(introductionService.computeSuggestions(WORKSPACE_ID, IntroRationaleService.RESOLVE_LIMIT))
                .thenReturn(List.of(suggestion));
        when(introRationaleAssembler.assemble(WORKSPACE_ID, suggestion)).thenReturn(assembly());
        when(aiInvocationService.completeStructured(any(AiInvocation.class), eq(IntroRationaleContent.class)))
                .thenThrow(exception);
    }

    private static MaskedPrompt twoPersonPrompt() {
        return PromptAssembly.builder()
                .system("Use only the supplied introduction signals.")
                .userTurn("Person A: {{P1}}; Person B: {{P2}}")
                .build();
    }

    private static IntroRationaleAssembly assembly() {
        MaskingContext context = new MaskingContext();
        String personA = MaskingEngine.maskField(EntityKind.PERSON, "Alice Ng", context);
        String personB = MaskingEngine.maskField(EntityKind.PERSON, "Bob Lee", context);
        MaskedPrompt prompt = PromptAssembly.builder()
                .system("Use only the supplied introduction signals.")
                .userTurn("Person A: " + personA + "; Person B: " + personB)
                .build();
        return new IntroRationaleAssembly(context, prompt);
    }

    private static IntroSuggestionDto suggestion(int personAId, int personBId) {
        IntroSuggestionDto suggestion = new IntroSuggestionDto();
        suggestion.setPersonAId(personAId);
        suggestion.setPersonAName("Alice Ng");
        suggestion.setPersonATitle("VP Partnerships");
        suggestion.setPersonACompany("Atlas Systems");
        suggestion.setPersonAWarmth("warm");
        suggestion.setPersonBId(personBId);
        suggestion.setPersonBName("Bob Lee");
        suggestion.setPersonBTitle("Head of Data");
        suggestion.setPersonBCompany("Beacon Labs");
        suggestion.setPersonBWarmth("hot");
        suggestion.setScore(82);
        suggestion.setReasons(List.of("mutual_connections", "shared_company"));
        suggestion.setMutualConnections(3);
        suggestion.setSharedCompany("Atlas Systems");
        return suggestion;
    }

    private static void assertUnavailable(IntroRationaleDto result, String reason) {
        assertFalse(result.isAvailable());
        assertEquals(PERSON_A_ID, result.getPersonAId());
        assertEquals(PERSON_B_ID, result.getPersonBId());
        assertEquals(reason, result.getReason());
        assertNull(result.getRationale());
        assertNull(result.getGeneratedAt());
        assertEquals(0, result.getWarnings());
    }
}
