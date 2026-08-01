package ooo.klae.connex.backend.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.ai.brief.DealBriefContent;
import ooo.klae.connex.backend.ai.masking.EntityKind;
import ooo.klae.connex.backend.ai.masking.MaskedPrompt;
import ooo.klae.connex.backend.ai.masking.MaskingContext;
import ooo.klae.connex.backend.ai.masking.MaskingEngine;
import ooo.klae.connex.backend.ai.masking.PromptAssembly;
import ooo.klae.connex.backend.beans.AiOutputCache;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.mappers.AiOutputCacheMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.services.WorkspaceService;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class AiOutputCacheStoreTest {
    private static final AiGenerationProfile PROFILE = new AiGenerationProfile(
            "bedrock", "us-east-1", "anthropic.claude-3-sonnet-v1:0",
            "https://api.example.test", "deployment-a", "2026-01-01", "project-a",
            2048, 0.2);

    @Mock private AiOutputCacheMapper aiOutputCacheMapper;
    @Mock private PersonMapper personMapper;
    @Mock private WorkspaceService workspaceService;

    private AiRestrictionEpoch restrictionEpoch;
    private AiOutputCacheStore store;

    @BeforeEach
    void setUp() {
        restrictionEpoch = new AiRestrictionEpoch();
        store = new AiOutputCacheStore(
                aiOutputCacheMapper, personMapper, restrictionEpoch, JsonMapper.builder().build(),
                workspaceService);
    }

    @Test
    void contentHash_isStableForIdenticalPromptAndBindings() {
        assertEquals(store.contentHash(PROFILE, prompt("Owner: {{P1}}"), context("Mina Patel")),
                store.contentHash(PROFILE, prompt("Owner: {{P1}}"), context("Mina Patel")));
    }

    @Test
    void contentHash_usesProviderProfileGroundingVersion() {
        assertEquals("v3-provider-profile-grounding", AiOutputCacheStore.HASH_VERSION);
    }

    @Test
    void contentHash_differsWhenIdentityBindingsDiffer() {
        assertNotEquals(store.contentHash(PROFILE, prompt("Owner: {{P1}}"), context("Mina Patel")),
                store.contentHash(PROFILE, prompt("Owner: {{P1}}"), context("Mina Shah")));
    }

    @Test
    void contentHash_differsWhenPromptTextDiffers() {
        assertNotEquals(store.contentHash(PROFILE, prompt("Owner: {{P1}}"), context("Mina Patel")),
                store.contentHash(PROFILE, prompt("Lead: {{P1}}"), context("Mina Patel")));
    }

    @Test
    void contentHash_differsWhenProviderOrModelChanges() {
        AiGenerationProfile providerChanged = new AiGenerationProfile(
                "vertex", PROFILE.region(), PROFILE.modelId(), PROFILE.endpoint(), PROFILE.deployment(),
                PROFILE.apiVersion(), PROFILE.projectId(), PROFILE.maxTokens(), PROFILE.temperature());
        AiGenerationProfile modelChanged = new AiGenerationProfile(
                PROFILE.provider(), PROFILE.region(), "anthropic.claude-sonnet-4-v1:0",
                PROFILE.endpoint(), PROFILE.deployment(), PROFILE.apiVersion(), PROFILE.projectId(),
                PROFILE.maxTokens(), PROFILE.temperature());
        String original = store.contentHash(PROFILE, prompt("Owner: {{P1}}"), context("Mina Patel"));

        assertNotEquals(original,
                store.contentHash(providerChanged, prompt("Owner: {{P1}}"), context("Mina Patel")));
        assertNotEquals(original,
                store.contentHash(modelChanged, prompt("Owner: {{P1}}"), context("Mina Patel")));
    }

    @Test
    void contentHash_differsWhenEndpointChanges() {
        AiGenerationProfile changed = new AiGenerationProfile(
                PROFILE.provider(), PROFILE.region(), PROFILE.modelId(), "https://other.example.test",
                PROFILE.deployment(), PROFILE.apiVersion(), PROFILE.projectId(),
                PROFILE.maxTokens(), PROFILE.temperature());

        assertNotEquals(hash(PROFILE), hash(changed));
    }

    @Test
    void generationProfileToStringRedactsEndpoint() {
        String rendered = PROFILE.toString();

        assertFalse(rendered.contains(PROFILE.endpoint()));
        assertTrue(rendered.contains("endpoint=<redacted>"));
        assertTrue(rendered.contains("provider=" + PROFILE.provider()));
        assertTrue(rendered.contains("modelId=" + PROFILE.modelId()));
    }

    @Test
    void contentHash_differsWhenDeploymentChanges() {
        AiGenerationProfile changed = new AiGenerationProfile(
                PROFILE.provider(), PROFILE.region(), PROFILE.modelId(), PROFILE.endpoint(),
                "deployment-b", PROFILE.apiVersion(), PROFILE.projectId(),
                PROFILE.maxTokens(), PROFILE.temperature());

        assertNotEquals(hash(PROFILE), hash(changed));
    }

    @Test
    void contentHash_differsWhenApiVersionChanges() {
        AiGenerationProfile changed = new AiGenerationProfile(
                PROFILE.provider(), PROFILE.region(), PROFILE.modelId(), PROFILE.endpoint(),
                PROFILE.deployment(), "2026-02-01", PROFILE.projectId(),
                PROFILE.maxTokens(), PROFILE.temperature());

        assertNotEquals(hash(PROFILE), hash(changed));
    }

    @Test
    void contentHash_differsWhenProjectIdChanges() {
        AiGenerationProfile changed = new AiGenerationProfile(
                PROFILE.provider(), PROFILE.region(), PROFILE.modelId(), PROFILE.endpoint(),
                PROFILE.deployment(), PROFILE.apiVersion(), "project-b",
                PROFILE.maxTokens(), PROFILE.temperature());

        assertNotEquals(hash(PROFILE), hash(changed));
    }

    @Test
    void save_withCurrentRestrictionEpochSerializesContentAndUpserts() {
        assertTrue(store.save(7, "deal.brief", 29, AiOutputCacheStore.NO_SUBJECT, "hash-1",
                new DealBriefContent(List.of(new DealBriefContent.Section("Who they are", "Mina Patel."))), 2,
                "2026-07-09T18:30:00Z", restrictionEpoch.current(7)));

        ArgumentCaptor<AiOutputCache> entry = ArgumentCaptor.forClass(AiOutputCache.class);
        verify(aiOutputCacheMapper).upsert(entry.capture());
        assertEquals(7, entry.getValue().getWorkspaceId());
        assertEquals("deal.brief", entry.getValue().getFeature());
        assertEquals(29, entry.getValue().getSubjectAId());
        assertEquals(0, entry.getValue().getSubjectBId());
        assertEquals("hash-1", entry.getValue().getContentHash());
        assertEquals(2, entry.getValue().getWarnings());
        assertEquals("2026-07-09T18:30:00Z", entry.getValue().getGeneratedAt());
        assertTrue(entry.getValue().getPayload().contains("\"Who they are\""));
        assertTrue(entry.getValue().getPayload().contains("Mina Patel."));
    }

    @Test
    void save_withStaleRestrictionEpochRefusesPersistence() {
        long snapshot = restrictionEpoch.current(7);
        restrictionEpoch.bump(7);

        assertFalse(store.save(7, "report.narrative", 29, AiOutputCacheStore.NO_SUBJECT, "hash-1",
                List.of("content"), 0, "2026-07-09T18:30:00Z", snapshot));
        verifyNoInteractions(aiOutputCacheMapper);
    }

    @Test
    void save_withSerializationFailureRefusesPersistence() {
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        when(objectMapper.writeValueAsString(any())).thenThrow(new SerializationFailure());
        AiOutputCacheStore failingStore = new AiOutputCacheStore(
                aiOutputCacheMapper, personMapper, restrictionEpoch, objectMapper, workspaceService);

        assertFalse(failingStore.save(
                7, "report.narrative", 29, AiOutputCacheStore.NO_SUBJECT, "hash-1",
                List.of("content"), 0, "2026-07-09T18:30:00Z", restrictionEpoch.current(7)));
        verifyNoInteractions(aiOutputCacheMapper);
    }

    @Test
    void saveForPersons_locksDistinctContributorRowsInAscendingOrderBeforeUpsert() {
        when(personMapper.getVisiblePersonByIdForUpdate(7, 3)).thenReturn(person(3));
        when(personMapper.getVisiblePersonByIdForUpdate(7, 9)).thenReturn(person(9));

        boolean safeToServe = store.saveForPersons(
                7, "deal.brief", 29, 0, "hash-1", List.of("content"), 0,
                "2026-07-09T18:30:00Z", List.of(9, 3, 9));

        assertTrue(safeToServe);
        InOrder ordered = inOrder(personMapper, aiOutputCacheMapper);
        ordered.verify(personMapper).getVisiblePersonByIdForUpdate(7, 3);
        ordered.verify(personMapper).getVisiblePersonByIdForUpdate(7, 9);
        ordered.verify(aiOutputCacheMapper).upsert(any(AiOutputCache.class));
    }

    @Test
    void saveForPersons_rejectsSuspendedContributor() {
        Person person = person(3);
        person.setSuspendedAt(LocalDateTime.parse("2026-07-21T10:00:00"));
        when(personMapper.getVisiblePersonByIdForUpdate(7, 3)).thenReturn(person);

        assertFalse(store.saveForPersons(
                7, "deal.brief", 29, 0, "hash-1", List.of("content"), 0,
                "2026-07-09T18:30:00Z", List.of(3)));
        verify(aiOutputCacheMapper, never()).upsert(any());
    }

    @Test
    void saveForPersons_rejectsProvisionCeasedContributor() {
        Person person = person(3);
        person.setProvisionCeasedAt(LocalDateTime.parse("2026-07-21T10:00:00"));
        when(personMapper.getVisiblePersonByIdForUpdate(7, 3)).thenReturn(person);

        assertFalse(store.saveForPersons(
                7, "deal.brief", 29, 0, "hash-1", List.of("content"), 0,
                "2026-07-09T18:30:00Z", List.of(3)));
        verify(aiOutputCacheMapper, never()).upsert(any());
    }

    @Test
    void saveForPersons_rejectsMissingOrInvisibleContributor() {
        when(personMapper.getVisiblePersonByIdForUpdate(7, 3)).thenReturn(null);

        assertFalse(store.saveForPersons(
                7, "deal.brief", 29, 0, "hash-1", List.of("content"), 0,
                "2026-07-09T18:30:00Z", List.of(3)));
        verify(aiOutputCacheMapper, never()).upsert(any());
    }

    @Test
    void saveForPersons_refusesSerializationFailureWithoutContributorAdmission() {
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        when(objectMapper.writeValueAsString(any())).thenThrow(new SerializationFailure());
        AiOutputCacheStore failingStore = new AiOutputCacheStore(
                aiOutputCacheMapper, personMapper, restrictionEpoch, objectMapper, workspaceService);

        assertFalse(failingStore.saveForPersons(
                7, "deal.brief", 29, 0, "hash-1", List.of("content"), 0,
                "2026-07-09T18:30:00Z", List.of(3)));
        verifyNoInteractions(personMapper, aiOutputCacheMapper);
    }

    @Test
    void saveForPersons_rejectsInvalidContributorIdsWithoutLockingOrUpserting() {
        assertFalse(store.saveForPersons(
                7, "deal.brief", 29, 0, "hash-1", List.of("content"), 0,
                "2026-07-09T18:30:00Z", java.util.Arrays.asList(3, null)));
        verifyNoInteractions(personMapper, aiOutputCacheMapper);
    }

    @Test
    void saveForPersons_rejectsNonPositiveContributorIdsWithoutLockingOrUpserting() {
        assertFalse(store.saveForPersons(
                7, "deal.brief", 29, 0, "hash-1", List.of("content"), 0,
                "2026-07-09T18:30:00Z", List.of(3, 0)));
        verifyNoInteractions(personMapper, aiOutputCacheMapper);
    }

    @Test
    void saveForPersons_rejectsNullContributorCollectionWithoutLockingOrUpserting() {
        assertFalse(store.saveForPersons(
                7, "deal.brief", 29, 0, "hash-1", List.of("content"), 0,
                "2026-07-09T18:30:00Z", null));
        verifyNoInteractions(personMapper, aiOutputCacheMapper);
    }

    @Test
    void saveForPersons_propagatesDatabaseFailure() {
        when(personMapper.getVisiblePersonByIdForUpdate(7, 3))
                .thenThrow(new IllegalStateException("database unavailable"));

        assertThrows(IllegalStateException.class, () -> store.saveForPersons(
                7, "deal.brief", 29, 0, "hash-1", List.of("content"), 0,
                "2026-07-09T18:30:00Z", List.of(3)));
        verify(aiOutputCacheMapper, never()).upsert(any());
    }

    @Test
    void saveForPersons_upsertsWhenNoContributorIsKnown() {
        assertTrue(store.saveForPersons(
                7, "deal.brief", 29, 0, "hash-1", List.of("content"), 0,
                "2026-07-09T18:30:00Z", List.of()));
        verifyNoInteractions(personMapper);
        verify(aiOutputCacheMapper).upsert(any(AiOutputCache.class));
    }

    @Test
    void read_roundTripsStoredContent() {
        Optional<DealBriefContent> content = store.read(
                "{\"sections\":[{\"title\":\"Deal status\",\"body\":\"Proposal sent.\"}]}", DealBriefContent.class);

        assertTrue(content.isPresent());
        assertEquals("Deal status", content.get().sections().get(0).title());
        assertEquals("Proposal sent.", content.get().sections().get(0).body());
    }

    @Test
    void read_returnsEmptyForUnparseableOrBlankPayload() {
        assertTrue(store.read("{not valid json", DealBriefContent.class).isEmpty());
        assertTrue(store.read("   ", DealBriefContent.class).isEmpty());
        assertTrue(store.read(null, DealBriefContent.class).isEmpty());
    }

    @Test
    void find_delegatesToMapper() {
        AiOutputCache row = new AiOutputCache();
        when(aiOutputCacheMapper.getBySubject(7, "deal.brief", 29, 0)).thenReturn(row);

        assertSame(row, store.find(7, "deal.brief", 29, 0).orElseThrow());
    }

    @Test
    void find_returnsEmptyWhenAbsent() {
        when(aiOutputCacheMapper.getBySubject(7, "deal.brief", 29, 0)).thenReturn(null);

        assertTrue(store.find(7, "deal.brief", 29, 0).isEmpty());
    }

    private static MaskedPrompt prompt(String userTurn) {
        return PromptAssembly.builder()
                .system("Use only the supplied context.")
                .userTurn(userTurn)
                .build();
    }

    private String hash(AiGenerationProfile profile) {
        return store.contentHash(profile, prompt("Owner: {{P1}}"), context("Mina Patel"));
    }

    private static MaskingContext context(String personName) {
        MaskingContext context = new MaskingContext();
        MaskingEngine.maskField(EntityKind.PERSON, personName, context);
        return context;
    }

    private static Person person(int id) {
        Person person = new Person();
        person.setId(id);
        return person;
    }

    private static final class SerializationFailure extends JacksonException {
        private SerializationFailure() {
            super("serialization failed");
        }
    }
}
