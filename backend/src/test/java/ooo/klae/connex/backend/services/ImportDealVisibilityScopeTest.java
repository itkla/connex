package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.services.RecordVisibilityScopeControlAccess.RecordVisibilityScope;

/** Pins scope reuse through the import's existing outcome cache and participant deduplication. */
class ImportDealVisibilityScopeTest {
    @Test
    void outcomeCacheAndParticipantLoopUseTheSuppliedScopeWithoutAnotherBoundaryLookup() {
        ImportService service = mock(ImportService.class, org.mockito.Answers.CALLS_REAL_METHODS);
        DealMapper mapper = mock(DealMapper.class);
        DealOutcomeWriter outcomes = mock(DealOutcomeWriter.class);
        RecordVisibilityScopeControlAccess scopes = mock(RecordVisibilityScopeControlAccess.class);
        RecordVisibilityScope scope = new RecordVisibilityScope(90, List.of(7, 8, 9));
        ReflectionTestUtils.setField(service, "dealMapper", mapper);
        ReflectionTestUtils.setField(service, "dealOutcomeWriter", outcomes);
        ReflectionTestUtils.setField(service, "recordVisibilityScopeControlAccess", scopes);
        when(outcomes.stageOutcome(7, 13, scope)).thenReturn("normal");
        Map<Integer, String> cache = new HashMap<>();

        for (int iteration = 0; iteration < 4; iteration++) {
            assertEquals("normal", ReflectionTestUtils.invokeMethod(service, "outcomeFor", 7, 13, cache, scope));
        }
        assertEquals("normal", ReflectionTestUtils.invokeMethod(service, "outcomeFor", 7, null, cache, scope));
        when(mapper.addPersonIfAbsent(7, "[7,8,9]", 41, 11)).thenReturn(1);
        boolean changed = Boolean.TRUE.equals(ReflectionTestUtils.invokeMethod(service, "linkDealPeople",
            7, 41, List.of("first", "alias", "second", "missing"),
            Map.of("first", 11, "alias", 11, "second", 12), scope));

        assertTrue(changed);
        verify(outcomes).stageOutcome(7, 13, scope);
        verify(mapper).addPersonIfAbsent(7, "[7,8,9]", 41, 11);
        verify(mapper).addPersonIfAbsent(7, "[7,8,9]", 41, 12);
        verifyNoInteractions(scopes);
    }
}
