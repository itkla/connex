package ooo.klae.connex.backend.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.ai.brief.DealBriefContent;
import ooo.klae.connex.backend.ai.masking.EntityKind;
import ooo.klae.connex.backend.ai.masking.MaskedPrompt;
import ooo.klae.connex.backend.ai.masking.MaskingContext;
import ooo.klae.connex.backend.ai.masking.MaskingEngine;
import ooo.klae.connex.backend.ai.masking.PromptAssembly;
import ooo.klae.connex.backend.beans.AiOutputCache;
import ooo.klae.connex.backend.mappers.AiOutputCacheMapper;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class AiOutputCacheStoreTest {
    @Mock private AiOutputCacheMapper aiOutputCacheMapper;

    private AiOutputCacheStore store;

    @BeforeEach
    void setUp() {
        store = new AiOutputCacheStore(aiOutputCacheMapper, JsonMapper.builder().build());
    }

    @Test
    void contentHash_isStableForIdenticalPromptAndBindings() {
        assertEquals(store.contentHash(prompt("Owner: {{P1}}"), context("Mina Patel")),
                store.contentHash(prompt("Owner: {{P1}}"), context("Mina Patel")));
    }

    @Test
    void contentHash_usesFailClosedStructuredOutputPolicyVersion() {
        assertEquals("v2-structured-json-fail-closed", AiOutputCacheStore.HASH_VERSION);
    }

    @Test
    void contentHash_differsWhenIdentityBindingsDiffer() {
        assertNotEquals(store.contentHash(prompt("Owner: {{P1}}"), context("Mina Patel")),
                store.contentHash(prompt("Owner: {{P1}}"), context("Mina Shah")));
    }

    @Test
    void contentHash_differsWhenPromptTextDiffers() {
        assertNotEquals(store.contentHash(prompt("Owner: {{P1}}"), context("Mina Patel")),
                store.contentHash(prompt("Lead: {{P1}}"), context("Mina Patel")));
    }

    @Test
    void save_serializesContentToJsonAndUpserts() {
        store.save(7, "deal.brief", 29, AiOutputCacheStore.NO_SUBJECT, "hash-1",
                new DealBriefContent(List.of(new DealBriefContent.Section("Who they are", "Mina Patel."))), 2,
                "2026-07-09T18:30:00Z");

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

    private static MaskingContext context(String personName) {
        MaskingContext context = new MaskingContext();
        MaskingEngine.maskField(EntityKind.PERSON, personName, context);
        return context;
    }
}
