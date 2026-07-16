package ooo.klae.connex.backend.ai.introrationale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.dto.IntroRationaleDto;
import ooo.klae.connex.backend.dto.IntroSuggestionDto;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.services.IntroductionService;
import ooo.klae.connex.backend.services.WorkspaceService;

@ExtendWith(MockitoExtension.class)
class IntroRationaleServiceTest {
    private static final int WORKSPACE_ID = 17;
    private static final int PERSON_A_ID = 29;
    private static final int PERSON_B_ID = 41;
    private static final String FEATURE = "intro.rationale";
    private static final String CACHE_FEATURE = "intro.rationale:en";
    private static final String HASH = "content-hash-1";
    private static final Instant NOW = Instant.parse("2026-07-09T18:30:00Z");

    @Mock private IntroRationaleAssembler introRationaleAssembler;
    @Mock private AiInvocationService aiInvocationService;
    @Mock private AiFeatureGate aiFeatureGate;
    @Mock private IntroductionService introductionService;
    @Mock private AiOutputCacheStore aiOutputCacheStore;
    @Mock private WorkspaceService workspaceService;
    @Mock private PersonMapper personMapper;

    private IntroRationaleService service;

    @BeforeEach
    void setUp() {
        service = new IntroRationaleService(
                introRationaleAssembler,
                aiInvocationService,
                aiFeatureGate,
                introductionService,
                aiOutputCacheStore,
                workspaceService,
                personMapper,
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(WORKSPACE_ID);
        lenient().when(personMapper.getPersonById(eq(WORKSPACE_ID), anyInt())).thenReturn(new Person());
        LocaleContextHolder.setLocale(Locale.ENGLISH);
    }

    @AfterEach
    void tearDown() {
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    void generate_aiNotUsable_returnsNotConfiguredWithoutSuggestionOrInvocation() {
        when(aiFeatureGate.isAiUsable()).thenReturn(false);

        IntroRationaleDto result = service.generate(PERSON_B_ID, PERSON_A_ID);

        assertUnavailable(result, "not_configured");
        verify(introductionService, never()).computeSuggestions(anyInt(), anyInt());
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
    void generate_restrictedParticipant_returnsNotASuggestionWithoutAssembly() {
        when(aiFeatureGate.isAiUsable()).thenReturn(true);
        when(introductionService.computeSuggestions(WORKSPACE_ID, IntroRationaleService.RESOLVE_LIMIT))
                .thenReturn(List.of(suggestion(PERSON_A_ID, PERSON_B_ID)));
        Person ceased = new Person();
        ceased.setProvisionCeasedAt(java.time.LocalDateTime.parse("2026-07-01T00:00:00"));
        when(personMapper.getPersonById(WORKSPACE_ID, PERSON_A_ID)).thenReturn(ceased);

        IntroRationaleDto result = service.generate(PERSON_A_ID, PERSON_B_ID);

        assertUnavailable(result, "not_a_suggestion");
        verify(introRationaleAssembler, never()).assemble(anyInt(), any());
        verify(aiInvocationService, never()).completeStructured(any(), any());
    }

    @Test
    void generate_swappedPair_returnsDemaskedRationalePersistsAndWarnings() {
        arrangeMiss(assembly());
        when(aiInvocationService.completeStructured(any(AiInvocation.class), eq(IntroRationaleContent.class)))
                .thenReturn(new AiStructuredOutcome.Parsed<>(
                        new IntroRationaleContent(
                                "Alice and Bob share three trusted connections and complementary roles."),
                        2, 80, 24, "end_turn"));

        IntroRationaleDto result = service.generate(PERSON_B_ID, PERSON_A_ID);

        assertTrue(result.isAvailable());
        assertEquals(PERSON_A_ID, result.getPersonAId());
        assertEquals(PERSON_B_ID, result.getPersonBId());
        assertEquals(
                "Alice and Bob share three trusted connections and complementary roles.",
                result.getRationale());
        assertEquals(NOW.toString(), result.getGeneratedAt());
        assertEquals(2, result.getWarnings());

        ArgumentCaptor<AiInvocation> invocation = ArgumentCaptor.forClass(AiInvocation.class);
        verify(aiInvocationService).completeStructured(invocation.capture(), eq(IntroRationaleContent.class));
        assertEquals(FEATURE, invocation.getValue().feature());
        assertEquals(IntroRationaleService.MAX_TOKENS, invocation.getValue().maxTokens());
        verify(aiOutputCacheStore).save(eq(WORKSPACE_ID), eq(CACHE_FEATURE), eq(PERSON_A_ID), eq(PERSON_B_ID),
                eq(HASH), any(IntroRationaleContent.class), eq(2), eq(NOW.toString()));
    }

    @Test
    void generate_cacheHit_reusesStoredRationaleWithoutInvocation() {
        IntroSuggestionDto suggestion = suggestion(PERSON_A_ID, PERSON_B_ID);
        IntroRationaleAssembly assembly = assembly();
        when(aiFeatureGate.isAiUsable()).thenReturn(true);
        when(introductionService.computeSuggestions(WORKSPACE_ID, IntroRationaleService.RESOLVE_LIMIT))
                .thenReturn(List.of(suggestion));
        when(introRationaleAssembler.assemble(WORKSPACE_ID, suggestion)).thenReturn(assembly);
        when(aiOutputCacheStore.contentHash(assembly.prompt(), assembly.context())).thenReturn(HASH);
        when(aiOutputCacheStore.find(WORKSPACE_ID, CACHE_FEATURE, PERSON_A_ID, PERSON_B_ID))
                .thenReturn(Optional.of(row(HASH, 1, "2026-07-01T09:00:00Z")));
        when(aiOutputCacheStore.read("payload", IntroRationaleContent.class))
                .thenReturn(Optional.of(new IntroRationaleContent("Stored rationale.")));

        IntroRationaleDto result = service.generate(PERSON_B_ID, PERSON_A_ID);

        assertTrue(result.isAvailable());
        assertEquals("Stored rationale.", result.getRationale());
        assertEquals("2026-07-01T09:00:00Z", result.getGeneratedAt());
        assertEquals(1, result.getWarnings());
        verify(aiInvocationService, never()).completeStructured(any(), any());
        verify(aiOutputCacheStore, never()).save(anyInt(), any(), anyInt(), anyInt(), any(), any(), anyInt(), any());
    }

    @Test
    void generate_contentHashMismatch_regenerates() {
        IntroSuggestionDto suggestion = suggestion(PERSON_A_ID, PERSON_B_ID);
        IntroRationaleAssembly assembly = assembly();
        when(aiFeatureGate.isAiUsable()).thenReturn(true);
        when(introductionService.computeSuggestions(WORKSPACE_ID, IntroRationaleService.RESOLVE_LIMIT))
                .thenReturn(List.of(suggestion));
        when(introRationaleAssembler.assemble(WORKSPACE_ID, suggestion)).thenReturn(assembly);
        when(aiOutputCacheStore.contentHash(assembly.prompt(), assembly.context())).thenReturn(HASH);
        when(aiOutputCacheStore.find(WORKSPACE_ID, CACHE_FEATURE, PERSON_A_ID, PERSON_B_ID))
                .thenReturn(Optional.of(row("stale-hash", 0, "2026-07-01T09:00:00Z")));
        when(aiInvocationService.completeStructured(any(AiInvocation.class), eq(IntroRationaleContent.class)))
                .thenReturn(new AiStructuredOutcome.Parsed<>(new IntroRationaleContent("Fresh."), 0, 20, 10, "end_turn"));

        IntroRationaleDto result = service.generate(PERSON_A_ID, PERSON_B_ID);

        assertEquals("Fresh.", result.getRationale());
        assertEquals(NOW.toString(), result.getGeneratedAt());
        verify(aiInvocationService).completeStructured(any(AiInvocation.class), eq(IntroRationaleContent.class));
        verify(aiOutputCacheStore).save(eq(WORKSPACE_ID), eq(CACHE_FEATURE), eq(PERSON_A_ID), eq(PERSON_B_ID),
                eq(HASH), any(IntroRationaleContent.class), eq(0), eq(NOW.toString()));
    }

    @Test
    void generate_malformedOutcome_returnsProviderErrorAndDoesNotPersist() {
        arrangeMiss(assembly());
        when(aiInvocationService.completeStructured(any(AiInvocation.class), eq(IntroRationaleContent.class)))
                .thenReturn(new AiStructuredOutcome.Malformed<>(
                        AiStructuredOutcome.REASON_MALFORMED, 5, 0, "end_turn"));

        IntroRationaleDto result = service.generate(PERSON_A_ID, PERSON_B_ID);

        assertUnavailable(result, "provider_error");
        verify(aiOutputCacheStore, never()).save(anyInt(), any(), anyInt(), anyInt(), any(), any(), anyInt(), any());
    }

    @Test
    void generate_blankRationale_returnsProviderErrorAndDoesNotPersist() {
        arrangeMiss(assembly());
        when(aiInvocationService.completeStructured(any(AiInvocation.class), eq(IntroRationaleContent.class)))
                .thenReturn(new AiStructuredOutcome.Parsed<>(new IntroRationaleContent("   "), 0, 5, 0, "end_turn"));

        IntroRationaleDto result = service.generate(PERSON_A_ID, PERSON_B_ID);

        assertUnavailable(result, "provider_error");
        verify(aiOutputCacheStore, never()).save(anyInt(), any(), anyInt(), anyInt(), any(), any(), anyInt(), any());
    }

    @Test
    void generate_maskingLeak_returnsProviderError() {
        arrangeInvocationFailure(new MaskingLeakException("blocked outbound identifier"));

        assertUnavailable(service.generate(PERSON_A_ID, PERSON_B_ID), "provider_error");
    }

    @Test
    void generate_providerFailure_returnsProviderError() {
        arrangeInvocationFailure(new AiProviderException("provider unavailable"));

        assertUnavailable(service.generate(PERSON_A_ID, PERSON_B_ID), "provider_error");
    }

    @Test
    void generate_forbiddenInvocation_returnsNotConfigured() {
        arrangeInvocationFailure(new ForbiddenException("AI features are not available"));

        assertUnavailable(service.generate(PERSON_A_ID, PERSON_B_ID), "not_configured");
    }

    private void arrangeMiss(IntroRationaleAssembly assembly) {
        IntroSuggestionDto suggestion = suggestion(PERSON_A_ID, PERSON_B_ID);
        when(aiFeatureGate.isAiUsable()).thenReturn(true);
        when(introductionService.computeSuggestions(WORKSPACE_ID, IntroRationaleService.RESOLVE_LIMIT))
                .thenReturn(List.of(suggestion));
        when(introRationaleAssembler.assemble(WORKSPACE_ID, suggestion)).thenReturn(assembly);
        when(aiOutputCacheStore.contentHash(assembly.prompt(), assembly.context())).thenReturn(HASH);
        when(aiOutputCacheStore.find(WORKSPACE_ID, CACHE_FEATURE, PERSON_A_ID, PERSON_B_ID))
                .thenReturn(Optional.empty());
    }

    private void arrangeInvocationFailure(RuntimeException exception) {
        arrangeMiss(assembly());
        when(aiInvocationService.completeStructured(any(AiInvocation.class), eq(IntroRationaleContent.class)))
                .thenThrow(exception);
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

    private static AiOutputCache row(String contentHash, int warnings, String generatedAt) {
        AiOutputCache row = new AiOutputCache();
        row.setContentHash(contentHash);
        row.setPayload("payload");
        row.setWarnings(warnings);
        row.setGeneratedAt(generatedAt);
        return row;
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
