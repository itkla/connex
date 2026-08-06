package ooo.klae.connex.backend.ai.brief;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
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
import org.mockito.ArgumentCaptor;
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
import ooo.klae.connex.backend.dto.DealBriefDto;
import ooo.klae.connex.backend.services.WorkspaceService;

@ExtendWith(MockitoExtension.class)
class DealBriefServiceTest {
    private static final int WORKSPACE_ID = 17;
    private static final int DEAL_ID = 29;
    private static final String CACHE_FEATURE = "deal.brief:en";
    private static final String HASH = "content-hash-1";
    private static final Instant NOW = Instant.parse("2026-07-09T18:30:00Z");
    private static final AiGenerationProfile PROFILE = new AiGenerationProfile(
            "bedrock", "us-east-1", "anthropic.claude-3-sonnet-v1:0",
            null, null, null, null,
            DealBriefService.MAX_TOKENS, DealBriefService.TEMPERATURE);

    @Mock private DealBriefAssembler dealBriefAssembler;
    @Mock private AiInvocationService aiInvocationService;
    @Mock private AiInvocationAdmissionService aiInvocationAdmissionService;
    @Mock private Admission admission;
    @Mock private AiFeatureGate aiFeatureGate;
    @Mock private AiOutputCacheStore aiOutputCacheStore;
    @Mock private WorkspaceService workspaceService;

    private DealBriefService service;

    @BeforeEach
    void setUp() {
        service = new DealBriefService(
                dealBriefAssembler,
                aiInvocationService,
                aiInvocationAdmissionService,
                aiFeatureGate,
                aiOutputCacheStore,
                workspaceService,
                Clock.fixed(NOW, ZoneOffset.UTC));
        lenient().when(workspaceService.getCurrentWorkspaceId()).thenReturn(WORKSPACE_ID);
        lenient().when(aiFeatureGate.generationProfileIfUsable(
                AiFeature.DEAL_BRIEF, DealBriefService.MAX_TOKENS, DealBriefService.TEMPERATURE))
                .thenReturn(Optional.of(PROFILE));
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
    void generate_aiNotUsableSparseDeal_returnsNotConfiguredWithoutAssemblyOrInvocation() {
        when(aiFeatureGate.generationProfileIfUsable(AiFeature.DEAL_BRIEF, DealBriefService.MAX_TOKENS, DealBriefService.TEMPERATURE)).thenReturn(Optional.empty());

        DealBriefDto result = service.generate(DEAL_ID);

        assertFalse(result.isAvailable());
        assertEquals(DEAL_ID, result.getDealId());
        assertEquals("not_configured", result.getReason());
        assertNull(result.getSections());
        assertNull(result.getBrief());
        verify(workspaceService, never()).getCurrentWorkspaceId();
        verify(dealBriefAssembler, never()).assemble(anyInt(), anyInt());
        verify(aiInvocationService, never()).completeStructured(any(AiInvocation.class), eq(DealBriefContent.class), eq(admission));
    }

    @Test
    void generate_happyPath_returnsDemaskedSectionsPersistsAndWarnings() {
        BriefAssembly assembly = assembly();
        arrangeMiss(assembly);
        when(aiInvocationService.completeStructured(any(AiInvocation.class), eq(DealBriefContent.class), eq(admission)))
                .thenReturn(new AiStructuredOutcome.Parsed<>(
                        new DealBriefContent(List.of(
                                section("Who they are", "Mina Patel leads the call."),
                                section("Deal status", "Proposal sent."),
                                section("Next move", "Call tomorrow."))),
                        2,
                        120,
                        45,
                        "end_turn"));

        DealBriefDto result = service.generate(DEAL_ID);

        assertTrue(result.isAvailable());
        assertEquals(3, result.getSections().size());
        assertEquals("Who they are", result.getSections().get(0).title());
        assertEquals("Mina Patel leads the call.", result.getSections().get(0).body());
        assertEquals(NOW.toString(), result.getGeneratedAt());
        assertEquals(2, result.getWarnings());

        ArgumentCaptor<AiInvocation> invocation = ArgumentCaptor.forClass(AiInvocation.class);
        verify(aiInvocationService).completeStructured(invocation.capture(), eq(DealBriefContent.class), eq(admission));
        assertEquals(AiFeature.DEAL_BRIEF, invocation.getValue().feature());
        assertEquals(DealBriefService.MAX_TOKENS, invocation.getValue().maxTokens());
        verify(aiOutputCacheStore).saveForPersons(eq(WORKSPACE_ID), eq(CACHE_FEATURE), eq(DEAL_ID),
                eq(AiOutputCacheStore.NO_SUBJECT), eq(HASH), any(DealBriefContent.class), eq(2),
                eq(NOW.toString()), eq(List.of(73)));
    }

    @Test
    void generate_contributorRestrictedBeforeCacheAdmissionReturnsProviderError() {
        BriefAssembly assembly = assembly();
        arrangeMiss(assembly);
        when(aiInvocationService.completeStructured(any(AiInvocation.class), eq(DealBriefContent.class), eq(admission)))
                .thenReturn(new AiStructuredOutcome.Parsed<>(
                        content("Fresh."),
                        0, 20, 10, "end_turn"));
        when(aiOutputCacheStore.saveForPersons(
                anyInt(), any(), anyInt(), anyInt(), any(), any(), anyInt(), any(), any()))
                .thenReturn(false);

        DealBriefDto result = service.generate(DEAL_ID);

        assertFalse(result.isAvailable());
        assertEquals("provider_error", result.getReason());
    }

    @Test
    void generate_cacheHit_reusesStoredBriefWithoutInvocation() {
        BriefAssembly assembly = assembly();
        when(aiFeatureGate.generationProfileIfUsable(AiFeature.DEAL_BRIEF, DealBriefService.MAX_TOKENS, DealBriefService.TEMPERATURE)).thenReturn(Optional.of(PROFILE));
        when(dealBriefAssembler.assemble(WORKSPACE_ID, DEAL_ID)).thenReturn(assembly);
        when(aiOutputCacheStore.contentHash(
                eq(PROFILE), eq(assembly.prompt()), eq(assembly.context()), anyList())).thenReturn(HASH);
        when(aiOutputCacheStore.find(WORKSPACE_ID, CACHE_FEATURE, DEAL_ID, AiOutputCacheStore.NO_SUBJECT))
                .thenReturn(Optional.of(row(HASH, 3, "2026-07-01T09:00:00Z")));
        when(aiOutputCacheStore.read("payload", DealBriefContent.class))
                .thenReturn(Optional.of(content("Stored brief.")));

        DealBriefDto result = service.generate(DEAL_ID);

        assertTrue(result.isAvailable());
        assertEquals("Stored brief.", result.getSections().get(0).body());
        assertEquals("2026-07-01T09:00:00Z", result.getGeneratedAt());
        assertEquals(3, result.getWarnings());
        assertFalse(result.isDegraded());
        verify(aiInvocationService, never()).completeStructured(any(AiInvocation.class), eq(DealBriefContent.class), eq(admission));
        verify(aiOutputCacheStore, never()).saveForPersons(
                anyInt(), any(), anyInt(), anyInt(), any(), any(), anyInt(), any(), any());
    }

    @Test
    void generate_followerReadsCachePublishedByLeader() {
        BriefAssembly assembly = assembly();
        when(dealBriefAssembler.assemble(WORKSPACE_ID, DEAL_ID)).thenReturn(assembly);
        when(aiOutputCacheStore.contentHash(
                eq(PROFILE), eq(assembly.prompt()), eq(assembly.context()), anyList())).thenReturn(HASH);
        when(aiOutputCacheStore.find(WORKSPACE_ID, CACHE_FEATURE, DEAL_ID, AiOutputCacheStore.NO_SUBJECT))
                .thenReturn(Optional.empty(), Optional.of(row(HASH, 1, "2026-07-01T09:00:00Z")));
        when(aiOutputCacheStore.read("payload", DealBriefContent.class))
                .thenReturn(Optional.of(content("Leader brief.")));
        when(admission.decision()).thenReturn(Decision.FOLLOWER);
        when(admission.awaitLeader()).thenReturn(LeaderOutcome.CACHE_READY);

        DealBriefDto result = service.generate(DEAL_ID);

        assertTrue(result.isAvailable());
        assertEquals("Leader brief.", result.getSections().getFirst().body());
        verify(aiInvocationService, never()).completeStructured(
                any(AiInvocation.class), eq(DealBriefContent.class), any(Admission.class));
    }

    @Test
    void generate_failedRefreshFollowerRetriesAsQuotaBoundedLeaderWithoutServingStaleCache() {
        BriefAssembly assembly = assembly();
        when(dealBriefAssembler.assemble(WORKSPACE_ID, DEAL_ID)).thenReturn(assembly);
        when(aiOutputCacheStore.contentHash(
                eq(PROFILE), eq(assembly.prompt()), eq(assembly.context()), anyList())).thenReturn(HASH);
        Admission retry = mock(Admission.class);
        when(aiInvocationAdmissionService.acquire(any(), eq(HASH), eq(true))).thenReturn(admission);
        when(aiInvocationAdmissionService.acquire(any(), eq(HASH), eq(false))).thenReturn(retry);
        when(admission.decision()).thenReturn(Decision.FOLLOWER);
        when(admission.awaitLeader()).thenReturn(LeaderOutcome.FAILED);
        when(retry.decision()).thenReturn(Decision.LEADER);
        when(aiInvocationService.completeStructured(
                any(AiInvocation.class), eq(DealBriefContent.class), eq(retry)))
                .thenReturn(new AiStructuredOutcome.Parsed<>(
                        content("Retry brief."),
                        0, 20, 10, "end_turn"));

        DealBriefDto result = service.generate(DEAL_ID, true);

        assertTrue(result.isAvailable());
        assertEquals("Retry brief.", result.getSections().getFirst().body());
        verify(aiInvocationAdmissionService).acquire(any(), eq(HASH), eq(true));
        verify(aiInvocationAdmissionService).acquire(any(), eq(HASH), eq(false));
        verify(aiOutputCacheStore, never()).find(anyInt(), anyString(), anyInt(), anyInt());
    }

    @Test
    void generate_japaneseLocaleReadsSeparateCacheEntry() {
        LocaleContextHolder.setLocale(Locale.JAPANESE);
        BriefAssembly assembly = assembly();
        when(aiFeatureGate.generationProfileIfUsable(AiFeature.DEAL_BRIEF, DealBriefService.MAX_TOKENS, DealBriefService.TEMPERATURE)).thenReturn(Optional.of(PROFILE));
        when(dealBriefAssembler.assemble(WORKSPACE_ID, DEAL_ID)).thenReturn(assembly);
        when(aiOutputCacheStore.contentHash(
                eq(PROFILE), eq(assembly.prompt()), eq(assembly.context()), anyList())).thenReturn(HASH);
        when(aiOutputCacheStore.find(
                WORKSPACE_ID, "deal.brief:ja", DEAL_ID, AiOutputCacheStore.NO_SUBJECT))
                .thenReturn(Optional.of(row(HASH, 0, "2026-07-01T09:00:00Z")));
        when(aiOutputCacheStore.read("payload", DealBriefContent.class))
                .thenReturn(Optional.of(content("保存済みの概要。")));

        DealBriefDto result = service.generate(DEAL_ID);

        assertTrue(result.isAvailable());
        assertEquals("保存済みの概要。", result.getSections().getFirst().body());
        verify(aiInvocationService, never()).completeStructured(any(AiInvocation.class), eq(DealBriefContent.class), eq(admission));
    }

    @Test
    void generate_contentHashMismatch_regenerates() {
        BriefAssembly assembly = assembly();
        when(aiFeatureGate.generationProfileIfUsable(AiFeature.DEAL_BRIEF, DealBriefService.MAX_TOKENS, DealBriefService.TEMPERATURE)).thenReturn(Optional.of(PROFILE));
        when(dealBriefAssembler.assemble(WORKSPACE_ID, DEAL_ID)).thenReturn(assembly);
        when(aiOutputCacheStore.contentHash(
                eq(PROFILE), eq(assembly.prompt()), eq(assembly.context()), anyList())).thenReturn(HASH);
        when(aiOutputCacheStore.find(WORKSPACE_ID, CACHE_FEATURE, DEAL_ID, AiOutputCacheStore.NO_SUBJECT))
                .thenReturn(Optional.of(row("stale-hash", 0, "2026-07-01T09:00:00Z")));
        when(aiInvocationService.completeStructured(any(AiInvocation.class), eq(DealBriefContent.class), eq(admission)))
                .thenReturn(new AiStructuredOutcome.Parsed<>(
                        content("Fresh."),
                        0, 20, 10, "end_turn"));

        DealBriefDto result = service.generate(DEAL_ID);

        assertEquals("Fresh.", result.getSections().get(0).body());
        assertEquals(NOW.toString(), result.getGeneratedAt());
        verify(aiInvocationService).completeStructured(any(AiInvocation.class), eq(DealBriefContent.class), eq(admission));
        verify(aiInvocationAdmissionService).acquire(any(), eq(HASH), eq(false));
        verify(aiOutputCacheStore).saveForPersons(eq(WORKSPACE_ID), eq(CACHE_FEATURE), eq(DEAL_ID),
                eq(AiOutputCacheStore.NO_SUBJECT), eq(HASH), any(DealBriefContent.class), eq(0),
                eq(NOW.toString()), eq(List.of(73)));
        verify(aiOutputCacheStore, never()).deleteIfContentHashMatches(
                anyInt(), any(), anyInt(), anyInt(), any());
    }

    @Test
    void generate_changedSourceRegistryInvalidatesCacheWithoutTranslatingStaleCitation() {
        BriefAssembly staleAssembly = assembly(Map.of(
                "deal.0", new DealBriefSource("deal", DEAL_ID),
                "act.0", new DealBriefSource("act", 201)), false);
        BriefAssembly currentAssembly = assembly(Map.of(
                "deal.0", new DealBriefSource("deal", DEAL_ID),
                "act.0", new DealBriefSource("act", 202)), false);
        List<String> staleGrounding = List.of(
                "act.0", "act", "201", "deal.0", "deal", Integer.toString(DEAL_ID));
        List<String> currentGrounding = List.of(
                "act.0", "act", "202", "deal.0", "deal", Integer.toString(DEAL_ID));
        AiOutputCacheStore hasher = mock(AiOutputCacheStore.class, CALLS_REAL_METHODS);
        String staleHash = hasher.contentHash(
                PROFILE, staleAssembly.prompt(), staleAssembly.context(), staleGrounding);
        String currentHash = hasher.contentHash(
                PROFILE, currentAssembly.prompt(), currentAssembly.context(), currentGrounding);
        assertEquals(
                hasher.contentHash(PROFILE, staleAssembly.prompt(), staleAssembly.context()),
                hasher.contentHash(PROFILE, currentAssembly.prompt(), currentAssembly.context()));
        assertNotEquals(staleHash, currentHash);

        when(dealBriefAssembler.assemble(WORKSPACE_ID, DEAL_ID)).thenReturn(currentAssembly);
        when(aiOutputCacheStore.contentHash(
                PROFILE, currentAssembly.prompt(), currentAssembly.context(), currentGrounding))
                .thenReturn(currentHash);
        when(aiOutputCacheStore.find(
                WORKSPACE_ID, CACHE_FEATURE, DEAL_ID, AiOutputCacheStore.NO_SUBJECT))
                .thenReturn(Optional.of(row(staleHash, 0, "2026-07-01T09:00:00Z")));
        when(aiInvocationService.completeStructured(
                any(AiInvocation.class), eq(DealBriefContent.class), eq(admission)))
                .thenReturn(new AiStructuredOutcome.Parsed<>(
                        content("Fresh brief.", "act.0"), 0, 20, 10, "end_turn"));

        DealBriefDto result = service.generate(DEAL_ID);

        assertTrue(result.isAvailable());
        assertEquals("Fresh brief.", result.getSections().getFirst().body());
        assertEquals(
                new DealBriefDto.Citation("act.0", "act", 202),
                result.getSections().getFirst().citations().getFirst());
        verify(aiOutputCacheStore, never()).read("payload", DealBriefContent.class);
        verify(aiInvocationService).completeStructured(
                any(AiInvocation.class), eq(DealBriefContent.class), eq(admission));
    }

    @Test
    void generate_refresh_bypassesCacheAndRegenerates() {
        BriefAssembly assembly = assembly();
        when(aiFeatureGate.generationProfileIfUsable(AiFeature.DEAL_BRIEF, DealBriefService.MAX_TOKENS, DealBriefService.TEMPERATURE)).thenReturn(Optional.of(PROFILE));
        when(dealBriefAssembler.assemble(WORKSPACE_ID, DEAL_ID)).thenReturn(assembly);
        when(aiOutputCacheStore.contentHash(
                eq(PROFILE), eq(assembly.prompt()), eq(assembly.context()), anyList())).thenReturn(HASH);
        when(aiInvocationService.completeStructured(any(AiInvocation.class), eq(DealBriefContent.class), eq(admission)))
                .thenReturn(new AiStructuredOutcome.Parsed<>(
                        content("Fresh take."),
                        0, 20, 10, "end_turn"));

        DealBriefDto result = service.generate(DEAL_ID, true);

        assertEquals("Fresh take.", result.getSections().get(0).body());
        verify(aiInvocationService).completeStructured(any(AiInvocation.class), eq(DealBriefContent.class), eq(admission));
        verify(aiOutputCacheStore, never()).find(anyInt(), any(), anyInt(), anyInt());
        verify(aiOutputCacheStore).saveForPersons(eq(WORKSPACE_ID), eq(CACHE_FEATURE), eq(DEAL_ID),
                eq(AiOutputCacheStore.NO_SUBJECT), eq(HASH), any(DealBriefContent.class), eq(0),
                eq(NOW.toString()), eq(List.of(73)));
    }

    @Test
    void generate_providerFailure_returnsProviderError() {
        arrangeMiss(assembly());
        when(aiInvocationService.completeStructured(any(AiInvocation.class), eq(DealBriefContent.class), eq(admission)))
                .thenThrow(new AiProviderException("provider unavailable"));

        DealBriefDto result = service.generate(DEAL_ID);

        assertFalse(result.isAvailable());
        assertEquals("provider_error", result.getReason());
        verify(aiOutputCacheStore, never()).saveForPersons(
                anyInt(), any(), anyInt(), anyInt(), any(), any(), anyInt(), any(), any());
    }

    @Test
    void generate_maskingLeak_returnsProviderError() {
        arrangeMiss(assembly());
        when(aiInvocationService.completeStructured(any(AiInvocation.class), eq(DealBriefContent.class), eq(admission)))
                .thenThrow(new MaskingLeakException("blocked outbound identifier"));

        DealBriefDto result = service.generate(DEAL_ID);

        assertFalse(result.isAvailable());
        assertEquals("provider_error", result.getReason());
    }

    @Test
    void generate_malformedOutcome_returnsProviderErrorAndDoesNotPersist() {
        arrangeMiss(assembly());
        when(aiInvocationService.completeStructured(any(AiInvocation.class), eq(DealBriefContent.class), eq(admission)))
                .thenReturn(new AiStructuredOutcome.Malformed<>(
                        AiStructuredOutcome.REASON_MALFORMED, 200, 120, "end_turn"));

        DealBriefDto result = service.generate(DEAL_ID);

        assertFalse(result.isAvailable());
        assertEquals("provider_error", result.getReason());
        verify(aiOutputCacheStore, never()).saveForPersons(
                anyInt(), any(), anyInt(), anyInt(), any(), any(), anyInt(), any(), any());
    }

    @Test
    void generate_noValidSections_returnsProviderError() {
        arrangeMiss(assembly());
        when(aiInvocationService.completeStructured(any(AiInvocation.class), eq(DealBriefContent.class), eq(admission)))
                .thenReturn(new AiStructuredOutcome.Parsed<>(
                        new DealBriefContent(List.of(
                                section("Account", "Valid."),
                                section("Status", "Valid."),
                                new DealBriefContent.Section("  ", "  ", List.of("person.0")))),
                        0, 20, 5, "end_turn"));

        DealBriefDto result = service.generate(DEAL_ID);

        assertFalse(result.isAvailable());
        assertEquals("provider_error", result.getReason());
        verify(aiOutputCacheStore, never()).saveForPersons(
                anyInt(), any(), anyInt(), anyInt(), any(), any(), anyInt(), any(), any());
    }

    @Test
    void generate_invalidCachedContentEvictsByHashAndRegenerates() {
        BriefAssembly assembly = assembly();
        when(dealBriefAssembler.assemble(WORKSPACE_ID, DEAL_ID)).thenReturn(assembly);
        when(aiOutputCacheStore.contentHash(
                eq(PROFILE), eq(assembly.prompt()), eq(assembly.context()), anyList())).thenReturn(HASH);
        when(aiOutputCacheStore.find(
                WORKSPACE_ID, CACHE_FEATURE, DEAL_ID, AiOutputCacheStore.NO_SUBJECT))
                .thenReturn(Optional.of(row(HASH, 0, "2026-07-01T09:00:00Z")), Optional.empty());
        when(aiOutputCacheStore.read("payload", DealBriefContent.class)).thenReturn(Optional.of(
                new DealBriefContent(List.of(section("Only section", "Invalid cached content.")))));
        when(aiInvocationService.completeStructured(
                any(AiInvocation.class), eq(DealBriefContent.class), eq(admission)))
                .thenReturn(new AiStructuredOutcome.Parsed<>(
                        content("Regenerated."), 0, 20, 10, "end_turn"));

        DealBriefDto result = service.generate(DEAL_ID);

        assertTrue(result.isAvailable());
        assertEquals("Regenerated.", result.getSections().getFirst().body());
        verify(aiOutputCacheStore).deleteIfContentHashMatches(
                WORKSPACE_ID, CACHE_FEATURE, DEAL_ID, AiOutputCacheStore.NO_SUBJECT, HASH);
        verify(aiInvocationService).completeStructured(
                any(AiInvocation.class), eq(DealBriefContent.class), eq(admission));
    }

    @Test
    void generate_belowEvidenceFloorSkipsProfileHashAdmissionAndProvider() {
        BriefAssembly assembly = assembly(
                Map.of("deal.0", new DealBriefSource("deal", DEAL_ID)), false);
        when(dealBriefAssembler.assemble(WORKSPACE_ID, DEAL_ID)).thenReturn(assembly);

        DealBriefDto result = service.generate(DEAL_ID);

        assertFalse(result.isAvailable());
        assertEquals("insufficient_data", result.getReason());
        verify(aiFeatureGate).generationProfileIfUsable(
                AiFeature.DEAL_BRIEF, DealBriefService.MAX_TOKENS, DealBriefService.TEMPERATURE);
        verify(aiOutputCacheStore, never()).contentHash(any(), any(), any(), anyList());
        verify(aiInvocationAdmissionService, never()).acquire(any(), anyString(), anyBoolean());
        verify(aiInvocationService, never()).completeStructured(
                any(AiInvocation.class), eq(DealBriefContent.class), eq(admission));
    }

    @Test
    void generate_degradedIsDerivedFromFreshAssemblyOnCacheHit() {
        BriefAssembly assembly = assembly(
                Map.of(
                        "deal.0", new DealBriefSource("deal", DEAL_ID),
                        "person.0", new DealBriefSource("person", 73)),
                true);
        when(dealBriefAssembler.assemble(WORKSPACE_ID, DEAL_ID)).thenReturn(assembly);
        when(aiOutputCacheStore.contentHash(
                eq(PROFILE), eq(assembly.prompt()), eq(assembly.context()), anyList())).thenReturn(HASH);
        when(aiOutputCacheStore.find(
                WORKSPACE_ID, CACHE_FEATURE, DEAL_ID, AiOutputCacheStore.NO_SUBJECT))
                .thenReturn(Optional.of(row(HASH, 0, "2026-07-01T09:00:00Z")));
        when(aiOutputCacheStore.read("payload", DealBriefContent.class))
                .thenReturn(Optional.of(content("Stored.")));

        DealBriefDto result = service.generate(DEAL_ID);

        assertTrue(result.isAvailable());
        assertTrue(result.isDegraded());
        verify(aiInvocationService, never()).completeStructured(
                any(AiInvocation.class), eq(DealBriefContent.class), eq(admission));
    }

    private void arrangeMiss(BriefAssembly assembly) {
        when(aiFeatureGate.generationProfileIfUsable(AiFeature.DEAL_BRIEF, DealBriefService.MAX_TOKENS, DealBriefService.TEMPERATURE)).thenReturn(Optional.of(PROFILE));
        when(dealBriefAssembler.assemble(WORKSPACE_ID, DEAL_ID)).thenReturn(assembly);
        when(aiOutputCacheStore.contentHash(
                eq(PROFILE), eq(assembly.prompt()), eq(assembly.context()), anyList())).thenReturn(HASH);
        when(aiOutputCacheStore.find(WORKSPACE_ID, CACHE_FEATURE, DEAL_ID, AiOutputCacheStore.NO_SUBJECT))
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
        return assembly(Map.of(
                "deal.0", new DealBriefSource("deal", DEAL_ID),
                "person.0", new DealBriefSource("person", 73)), false);
    }

    private static BriefAssembly assembly(
            Map<String, DealBriefSource> sourceRegistry, boolean degraded) {
        MaskingContext context = new MaskingContext();
        String person = MaskingEngine.maskField(EntityKind.PERSON, "Mina Patel", context);
        MaskedPrompt prompt = PromptAssembly.builder()
                .system("Use only the supplied context.")
                .userTurn("Stakeholder: " + person)
                .build();
        return new BriefAssembly(context, prompt, sourceRegistry, degraded, List.of(73));
    }

    private static DealBriefContent content(String firstBody) {
        return content(firstBody, "person.0");
    }

    private static DealBriefContent content(String firstBody, String sourceId) {
        return new DealBriefContent(List.of(
                section("Account", firstBody, sourceId),
                section("Status", "Discovery is active.", sourceId),
                section("Next move", "Call the champion.", sourceId)));
    }

    private static DealBriefContent.Section section(String title, String body) {
        return section(title, body, "person.0");
    }

    private static DealBriefContent.Section section(String title, String body, String sourceId) {
        return new DealBriefContent.Section(title, body, List.of(sourceId));
    }
}
