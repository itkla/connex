package ooo.klae.connex.backend.ai.riskrationale;

import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
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
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.i18n.LocaleContextHolder;

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
    private static final AiGenerationProfile PROFILE = new AiGenerationProfile(
            "bedrock", "us-east-1", "anthropic.claude-3-sonnet-v1:0",
            null, null, null, null,
            DealRiskRationaleService.MAX_TOKENS, DealRiskRationaleService.TEMPERATURE);

    @Mock private DealRiskRationaleAssembler dealRiskRationaleAssembler;
    @Mock private AiInvocationService aiInvocationService;
    @Mock private AiInvocationAdmissionService aiInvocationAdmissionService;
    @Mock private Admission admission;
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
                aiInvocationAdmissionService,
                aiFeatureGate,
                dealRiskService,
                aiOutputCacheStore,
                workspaceService,
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(WORKSPACE_ID);
        lenient().when(aiFeatureGate.generationProfileIfUsable(
                AiFeature.DEAL_RISK_RATIONALE,
                DealRiskRationaleService.MAX_TOKENS,
                DealRiskRationaleService.TEMPERATURE)).thenReturn(Optional.of(PROFILE));
        lenient().when(aiInvocationAdmissionService.acquire(any(), anyString(), anyBoolean())).thenReturn(admission);
        lenient().when(admission.decision()).thenReturn(Decision.LEADER);
        lenient().when(aiOutputCacheStore.saveForPersons(
                anyInt(), any(), anyInt(), anyInt(), any(), any(), anyInt(), any(), any()))
                .thenReturn(true);
        LocaleContextHolder.setLocale(Locale.ENGLISH);
    }

    @AfterEach
    void tearDown() {
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    void generate_aiNotUsable_returnsNotConfiguredWithoutRiskOrInvocation() {
        when(aiFeatureGate.generationProfileIfUsable(AiFeature.DEAL_RISK_RATIONALE, DealRiskRationaleService.MAX_TOKENS, DealRiskRationaleService.TEMPERATURE)).thenReturn(Optional.empty());

        DealRationaleDto result = service.generate(DEAL_ID);

        assertUnavailable(result, "not_configured");
        verify(dealRiskService, never()).assessDeal(anyInt(), anyInt());
        verify(aiInvocationService, never()).completeStructured(any(AiInvocation.class), eq(DealRiskRationaleContent.class), eq(admission));
    }

    @Test
    void generate_noneRisk_returnsNotAtRiskWithoutInvocation() {
        DealRiskDto risk = new DealRiskDto(
                DEAL_ID, new BigDecimal("125000.00"), "USD", "none", 0,
                List.of(new DealRiskFactor("stalled", "medium", Map.of("daysSinceTouch", 31))),
                "2026-07-09 18:30:00");
        when(aiFeatureGate.generationProfileIfUsable(AiFeature.DEAL_RISK_RATIONALE, DealRiskRationaleService.MAX_TOKENS, DealRiskRationaleService.TEMPERATURE)).thenReturn(Optional.of(PROFILE));
        when(dealRiskService.assessDeal(WORKSPACE_ID, DEAL_ID)).thenReturn(risk);

        DealRationaleDto result = service.generate(DEAL_ID);

        assertUnavailable(result, "not_at_risk");
        verify(dealRiskRationaleAssembler, never()).assemble(anyInt(), anyInt(), any());
        verify(aiInvocationService, never()).completeStructured(any(AiInvocation.class), eq(DealRiskRationaleContent.class), eq(admission));
    }

    @Test
    void generate_emptyFactors_returnsNotAtRiskWithoutInvocation() {
        DealRiskDto risk = new DealRiskDto(
                DEAL_ID, new BigDecimal("125000.00"), "USD", "medium", 25,
                List.of(), "2026-07-09 18:30:00");
        when(aiFeatureGate.generationProfileIfUsable(AiFeature.DEAL_RISK_RATIONALE, DealRiskRationaleService.MAX_TOKENS, DealRiskRationaleService.TEMPERATURE)).thenReturn(Optional.of(PROFILE));
        when(dealRiskService.assessDeal(WORKSPACE_ID, DEAL_ID)).thenReturn(risk);

        DealRationaleDto result = service.generate(DEAL_ID);

        assertUnavailable(result, "not_at_risk");
        verify(aiInvocationService, never()).completeStructured(any(AiInvocation.class), eq(DealRiskRationaleContent.class), eq(admission));
    }

    @Test
    void generate_noAiEligibleFactorsAfterAssembly_returnsNotAtRiskWithoutInvocation() {
        DealRiskDto risk = atRisk();
        RationaleAssembly eligible = assembly();
        RationaleAssembly filtered = new RationaleAssembly(
            eligible.context(), eligible.prompt(), false, Set.of(), eligible.contributorPersonIds());
        when(aiFeatureGate.generationProfileIfUsable(AiFeature.DEAL_RISK_RATIONALE, DealRiskRationaleService.MAX_TOKENS, DealRiskRationaleService.TEMPERATURE)).thenReturn(Optional.of(PROFILE));
        when(dealRiskService.assessDeal(WORKSPACE_ID, DEAL_ID)).thenReturn(risk);
        when(dealRiskRationaleAssembler.assemble(WORKSPACE_ID, DEAL_ID, risk)).thenReturn(filtered);

        DealRationaleDto result = service.generate(DEAL_ID);

        assertUnavailable(result, "not_at_risk");
        verify(aiInvocationService, never()).completeStructured(any(AiInvocation.class), eq(DealRiskRationaleContent.class), eq(admission));
        verify(aiOutputCacheStore, never()).contentHash(any(), any(), any());
    }

    @Test
    void generate_happyPath_returnsDemaskedNarrativeActionsPersistsAndWarnings() {
        arrangeMiss(assembly());
        when(aiInvocationService.completeStructured(any(AiInvocation.class), eq(DealRiskRationaleContent.class), eq(admission)))
                .thenReturn(new AiStructuredOutcome.Parsed<>(
                        content(
                                "The deal is overdue and quiet.",
                                List.of("Contact Mina Patel today.", "Confirm the budget.")),
                        2, 120, 45, "end_turn"));

        DealRationaleDto result = service.generate(DEAL_ID);

        assertTrue(result.isAvailable());
        assertEquals("The deal is overdue and quiet.", result.getNarrative());
        assertEquals(List.of("Contact Mina Patel today.", "Confirm the budget."), result.getActions());
        assertEquals(List.of("stalled"), result.getNarrativeFactorCodes());
        assertEquals(List.of("stalled"), result.getRecommendedActions().getFirst().factorCodes());
        assertEquals(
                "The deal is overdue and quiet.\n• Contact Mina Patel today.\n• Confirm the budget.",
                result.getRationale());
        assertEquals(NOW.toString(), result.getGeneratedAt());
        assertEquals(2, result.getWarnings());
        verify(aiOutputCacheStore).saveForPersons(eq(WORKSPACE_ID), eq(CACHE_FEATURE), eq(DEAL_ID),
                eq(AiOutputCacheStore.NO_SUBJECT), eq(HASH), any(DealRiskRationaleContent.class), eq(2),
                eq(NOW.toString()), eq(List.of(73)));
    }

    @Test
    void generate_contributorRestrictedBeforeCacheAdmissionReturnsProviderError() {
        arrangeMiss(assembly());
        when(aiInvocationService.completeStructured(any(AiInvocation.class), eq(DealRiskRationaleContent.class), eq(admission)))
                .thenReturn(new AiStructuredOutcome.Parsed<>(
                        content("Fresh narrative.", List.of("Follow up with the stakeholder.")),
                        0, 20, 10, "end_turn"));
        when(aiOutputCacheStore.saveForPersons(
                anyInt(), any(), anyInt(), anyInt(), any(), any(), anyInt(), any(), any()))
                .thenReturn(false);

        DealRationaleDto result = service.generate(DEAL_ID);

        assertUnavailable(result, "provider_error");
    }

    @Test
    void generate_cacheHit_reusesStoredRationaleWithoutInvocation() {
        RationaleAssembly assembly = assembly();
        DealRiskDto risk = atRisk();
        when(aiFeatureGate.generationProfileIfUsable(AiFeature.DEAL_RISK_RATIONALE, DealRiskRationaleService.MAX_TOKENS, DealRiskRationaleService.TEMPERATURE)).thenReturn(Optional.of(PROFILE));
        when(dealRiskService.assessDeal(WORKSPACE_ID, DEAL_ID)).thenReturn(risk);
        when(dealRiskRationaleAssembler.assemble(WORKSPACE_ID, DEAL_ID, risk)).thenReturn(assembly);
        when(aiOutputCacheStore.contentHash(PROFILE, assembly.prompt(), assembly.context())).thenReturn(HASH);
        when(aiOutputCacheStore.find(WORKSPACE_ID, CACHE_FEATURE, DEAL_ID, AiOutputCacheStore.NO_SUBJECT))
                .thenReturn(Optional.of(row(HASH, 1, "2026-07-01T09:00:00Z")));
        when(aiOutputCacheStore.read("payload", DealRiskRationaleContent.class))
                .thenReturn(Optional.of(content("Stored narrative.", List.of("Stored action."))));

        DealRationaleDto result = service.generate(DEAL_ID);

        assertTrue(result.isAvailable());
        assertEquals("Stored narrative.", result.getNarrative());
        assertEquals(List.of("Stored action."), result.getActions());
        assertEquals("2026-07-01T09:00:00Z", result.getGeneratedAt());
        assertEquals(1, result.getWarnings());
        verify(aiInvocationService, never()).completeStructured(any(AiInvocation.class), eq(DealRiskRationaleContent.class), eq(admission));
        verify(aiOutputCacheStore, never()).saveForPersons(
                anyInt(), any(), anyInt(), anyInt(), any(), any(), anyInt(), any(), any());
    }

    @Test
    void generate_followerReadsCachePublishedByLeader() {
        RationaleAssembly assembly = assembly();
        DealRiskDto risk = atRisk();
        when(dealRiskService.assessDeal(WORKSPACE_ID, DEAL_ID)).thenReturn(risk);
        when(dealRiskRationaleAssembler.assemble(WORKSPACE_ID, DEAL_ID, risk)).thenReturn(assembly);
        when(aiOutputCacheStore.contentHash(PROFILE, assembly.prompt(), assembly.context())).thenReturn(HASH);
        when(aiOutputCacheStore.find(WORKSPACE_ID, CACHE_FEATURE, DEAL_ID, AiOutputCacheStore.NO_SUBJECT))
                .thenReturn(Optional.empty(), Optional.of(row(HASH, 1, "2026-07-01T09:00:00Z")));
        when(aiOutputCacheStore.read("payload", DealRiskRationaleContent.class))
                .thenReturn(Optional.of(content(
                        "Leader narrative.", List.of("Leader action."))));
        when(admission.decision()).thenReturn(Decision.FOLLOWER);
        when(admission.awaitLeader()).thenReturn(LeaderOutcome.CACHE_READY);

        DealRationaleDto result = service.generate(DEAL_ID);

        assertTrue(result.isAvailable());
        assertEquals("Leader narrative.", result.getNarrative());
        verify(aiInvocationService, never()).completeStructured(
                any(AiInvocation.class), eq(DealRiskRationaleContent.class), any(Admission.class));
    }

    @Test
    void generate_japaneseLocaleReadsSeparateCacheEntry() {
        LocaleContextHolder.setLocale(Locale.JAPANESE);
        RationaleAssembly assembly = assembly();
        DealRiskDto risk = atRisk();
        when(aiFeatureGate.generationProfileIfUsable(AiFeature.DEAL_RISK_RATIONALE, DealRiskRationaleService.MAX_TOKENS, DealRiskRationaleService.TEMPERATURE)).thenReturn(Optional.of(PROFILE));
        when(dealRiskService.assessDeal(WORKSPACE_ID, DEAL_ID)).thenReturn(risk);
        when(dealRiskRationaleAssembler.assemble(WORKSPACE_ID, DEAL_ID, risk)).thenReturn(assembly);
        when(aiOutputCacheStore.contentHash(PROFILE, assembly.prompt(), assembly.context())).thenReturn(HASH);
        when(aiOutputCacheStore.find(
                WORKSPACE_ID, "deal.risk_rationale:ja", DEAL_ID, AiOutputCacheStore.NO_SUBJECT))
                .thenReturn(Optional.of(row(HASH, 0, "2026-07-01T09:00:00Z")));
        when(aiOutputCacheStore.read("payload", DealRiskRationaleContent.class))
                .thenReturn(Optional.of(content("保存済みの説明。", List.of("フォローアップする。"))));

        DealRationaleDto result = service.generate(DEAL_ID);

        assertTrue(result.isAvailable());
        assertEquals("保存済みの説明。", result.getNarrative());
        verify(aiInvocationService, never()).completeStructured(any(AiInvocation.class), eq(DealRiskRationaleContent.class), eq(admission));
    }

    @Test
    void generate_contentHashMismatch_regenerates() {
        RationaleAssembly assembly = assembly();
        DealRiskDto risk = atRisk();
        when(aiFeatureGate.generationProfileIfUsable(AiFeature.DEAL_RISK_RATIONALE, DealRiskRationaleService.MAX_TOKENS, DealRiskRationaleService.TEMPERATURE)).thenReturn(Optional.of(PROFILE));
        when(dealRiskService.assessDeal(WORKSPACE_ID, DEAL_ID)).thenReturn(risk);
        when(dealRiskRationaleAssembler.assemble(WORKSPACE_ID, DEAL_ID, risk)).thenReturn(assembly);
        when(aiOutputCacheStore.contentHash(PROFILE, assembly.prompt(), assembly.context())).thenReturn(HASH);
        when(aiOutputCacheStore.find(WORKSPACE_ID, CACHE_FEATURE, DEAL_ID, AiOutputCacheStore.NO_SUBJECT))
                .thenReturn(Optional.of(row("stale-hash", 0, "2026-07-01T09:00:00Z")));
        when(aiInvocationService.completeStructured(any(AiInvocation.class), eq(DealRiskRationaleContent.class), eq(admission)))
                .thenReturn(new AiStructuredOutcome.Parsed<>(
                        content("Fresh narrative.", List.of("Follow up with the stakeholder.")), 0, 20, 10, "end_turn"));

        DealRationaleDto result = service.generate(DEAL_ID);

        assertEquals("Fresh narrative.", result.getNarrative());
        assertEquals(NOW.toString(), result.getGeneratedAt());
        verify(aiInvocationService).completeStructured(any(AiInvocation.class), eq(DealRiskRationaleContent.class), eq(admission));
        verify(aiInvocationAdmissionService).acquire(any(), eq(HASH), eq(false));
        verify(aiOutputCacheStore).saveForPersons(eq(WORKSPACE_ID), eq(CACHE_FEATURE), eq(DEAL_ID),
                eq(AiOutputCacheStore.NO_SUBJECT), eq(HASH), any(DealRiskRationaleContent.class), eq(0),
                eq(NOW.toString()), eq(List.of(73)));
        verify(aiOutputCacheStore, never()).deleteIfContentHashMatches(
                anyInt(), any(), anyInt(), anyInt(), any());
    }

    @Test
    void generate_refresh_bypassesCacheAndRegenerates() {
        RationaleAssembly assembly = assembly();
        DealRiskDto risk = atRisk();
        when(aiFeatureGate.generationProfileIfUsable(AiFeature.DEAL_RISK_RATIONALE, DealRiskRationaleService.MAX_TOKENS, DealRiskRationaleService.TEMPERATURE)).thenReturn(Optional.of(PROFILE));
        when(dealRiskService.assessDeal(WORKSPACE_ID, DEAL_ID)).thenReturn(risk);
        when(dealRiskRationaleAssembler.assemble(WORKSPACE_ID, DEAL_ID, risk)).thenReturn(assembly);
        when(aiOutputCacheStore.contentHash(PROFILE, assembly.prompt(), assembly.context())).thenReturn(HASH);
        when(aiInvocationService.completeStructured(any(AiInvocation.class), eq(DealRiskRationaleContent.class), eq(admission)))
                .thenReturn(new AiStructuredOutcome.Parsed<>(
                        content("Fresh narrative.", List.of("Follow up with the stakeholder.")), 0, 20, 10, "end_turn"));

        DealRationaleDto result = service.generate(DEAL_ID, true);

        assertEquals("Fresh narrative.", result.getNarrative());
        verify(aiInvocationService).completeStructured(any(AiInvocation.class), eq(DealRiskRationaleContent.class), eq(admission));
        verify(aiOutputCacheStore, never()).find(anyInt(), any(), anyInt(), anyInt());
        verify(aiOutputCacheStore).saveForPersons(eq(WORKSPACE_ID), eq(CACHE_FEATURE), eq(DEAL_ID),
                eq(AiOutputCacheStore.NO_SUBJECT), eq(HASH), any(DealRiskRationaleContent.class), eq(0),
                eq(NOW.toString()), eq(List.of(73)));
    }

    @Test
    void generate_malformedOutcome_returnsProviderErrorAndDoesNotPersist() {
        arrangeMiss(assembly());
        when(aiInvocationService.completeStructured(any(AiInvocation.class), eq(DealRiskRationaleContent.class), eq(admission)))
                .thenReturn(new AiStructuredOutcome.Malformed<>(
                        AiStructuredOutcome.REASON_TRUNCATED, 200, 200, "length"));

        DealRationaleDto result = service.generate(DEAL_ID);

        assertUnavailable(result, "provider_error");
        verify(aiOutputCacheStore, never()).saveForPersons(
                anyInt(), any(), anyInt(), anyInt(), any(), any(), anyInt(), any(), any());
    }

    @Test
    void generate_blankNarrative_returnsProviderErrorAndDoesNotPersist() {
        arrangeMiss(assembly());
        when(aiInvocationService.completeStructured(any(AiInvocation.class), eq(DealRiskRationaleContent.class), eq(admission)))
                .thenReturn(new AiStructuredOutcome.Parsed<>(
                        content("   ", List.of("Do something.")), 0, 20, 5, "end_turn"));

        DealRationaleDto result = service.generate(DEAL_ID);

        assertUnavailable(result, "provider_error");
        verify(aiOutputCacheStore, never()).saveForPersons(
                anyInt(), any(), anyInt(), anyInt(), any(), any(), anyInt(), any(), any());
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
        when(aiFeatureGate.generationProfileIfUsable(AiFeature.DEAL_RISK_RATIONALE, DealRiskRationaleService.MAX_TOKENS, DealRiskRationaleService.TEMPERATURE)).thenReturn(Optional.of(PROFILE));
        when(dealRiskService.assessDeal(WORKSPACE_ID, DEAL_ID)).thenReturn(risk);
        when(dealRiskRationaleAssembler.assemble(WORKSPACE_ID, DEAL_ID, risk)).thenReturn(assembly);
        when(aiOutputCacheStore.contentHash(PROFILE, assembly.prompt(), assembly.context())).thenReturn(HASH);
        when(aiOutputCacheStore.find(WORKSPACE_ID, CACHE_FEATURE, DEAL_ID, AiOutputCacheStore.NO_SUBJECT))
                .thenReturn(Optional.empty());
    }

    private void arrangeInvocationFailure(RuntimeException exception) {
        arrangeMiss(assembly());
        when(aiInvocationService.completeStructured(any(AiInvocation.class), eq(DealRiskRationaleContent.class), eq(admission)))
                .thenThrow(exception);
    }

    private static DealRiskDto atRisk() {
        DealRiskFactor factor = new DealRiskFactor("stalled", "medium", Map.of("daysSinceTouch", 31));
        return new DealRiskDto(
                DEAL_ID, new BigDecimal("125000.00"), "USD", "medium", 25,
                List.of(factor), "2026-07-09 18:30:00");
    }

    private static RationaleAssembly assembly() {
        MaskingContext context = new MaskingContext();
        String person = MaskingEngine.maskField(EntityKind.PERSON, "Mina Patel", context);
        MaskedPrompt prompt = PromptAssembly.builder()
                .system("Use only the supplied risk factors.")
                .userTurn("Stakeholder: " + person)
                .build();
        return new RationaleAssembly(context, prompt, true, Set.of("stalled"), List.of(73));
    }

    private static DealRiskRationaleContent content(String narrative, List<String> actions) {
        return new DealRiskRationaleContent(
                narrative,
                List.of("stalled"),
                actions.stream()
                        .map(action -> new DealRiskRationaleContent.RecommendedAction(
                                action, List.of("stalled")))
                        .toList(),
                null);
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
