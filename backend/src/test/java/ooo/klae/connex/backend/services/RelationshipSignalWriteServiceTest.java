package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import ooo.klae.connex.backend.beans.RelationshipSignal;
import ooo.klae.connex.backend.beans.RelationshipSignalFamilyState;
import ooo.klae.connex.backend.mappers.RelationshipSignalMapper;

class RelationshipSignalWriteServiceTest {
    @Test
    void olderSuccessfulAttemptCannotReplaceNewerFamilyState() {
        RelationshipSignalMapper mapper = mock(RelationshipSignalMapper.class);
        RelationshipSignalWriteService service = new RelationshipSignalWriteService(mapper);
        LocalDateTime newerAttempt = LocalDateTime.of(2026, 8, 8, 13, 0);
        LocalDateTime olderAttempt = newerAttempt.minusMinutes(5);
        when(mapper.lockFamilyState(9, "relationship_decay"))
            .thenReturn(familyState(newerAttempt));

        service.replaceFamily(
            9,
            "relationship_decay",
            "older",
            List.of(new RelationshipSignal()),
            olderAttempt,
            olderAttempt);

        verify(mapper, never()).upsertSignal(any(RelationshipSignal.class));
        verify(mapper, never()).resolveMissing(
            anyInt(), anyString(), anyString(), any(LocalDateTime.class));
        verify(mapper, never()).upsertFamilyAvailable(
            anyInt(), anyString(), any(LocalDateTime.class), any(LocalDateTime.class));
    }

    @Test
    void olderFailedAttemptCannotHideNewerAvailableFamilyState() {
        RelationshipSignalMapper mapper = mock(RelationshipSignalMapper.class);
        RelationshipSignalWriteService service = new RelationshipSignalWriteService(mapper);
        LocalDateTime newerAttempt = LocalDateTime.of(2026, 8, 8, 13, 0);
        when(mapper.lockFamilyState(9, "relationship_decay"))
            .thenReturn(familyState(newerAttempt));

        service.markUnavailable(
            9, "relationship_decay", newerAttempt.minusMinutes(5), "detector_failed");

        verify(mapper, never()).upsertFamilyUnavailable(
            anyInt(), anyString(), any(LocalDateTime.class), anyString());
    }

    @Test
    void equalTimestampFailedAttemptCannotHideExistingAvailableFamilyState() {
        RelationshipSignalMapper mapper = mock(RelationshipSignalMapper.class);
        RelationshipSignalWriteService service = new RelationshipSignalWriteService(mapper);
        LocalDateTime existingAttempt = LocalDateTime.of(2026, 8, 8, 13, 0, 0, 123_456_000);
        when(mapper.lockFamilyState(9, "relationship_decay"))
            .thenReturn(familyState(existingAttempt));

        service.markUnavailable(
            9,
            "relationship_decay",
            existingAttempt.plusNanos(900),
            "detector_failed");

        verify(mapper, never()).upsertFamilyUnavailable(
            anyInt(), anyString(), any(LocalDateTime.class), anyString());
    }

    @Test
    void workspaceCapKeepsTheSameHighestFiftyAndResolvesEveryOverflowRow() {
        RelationshipSignalMapper mapper = mock(RelationshipSignalMapper.class);
        RelationshipSignalWriteService service = new RelationshipSignalWriteService(mapper);
        List<RelationshipSignal> ranked = new ArrayList<>();
        for (int index = 0; index < 75; index++) {
            RelationshipSignal signal = new RelationshipSignal();
            signal.setId(index + 1L);
            ranked.add(signal);
        }
        when(mapper.findActiveForActor(9, 0)).thenReturn(ranked);
        LocalDateTime resolvedAt = LocalDateTime.of(2026, 8, 8, 12, 0);

        service.enforceWorkspaceCap(9, resolvedAt);

        ArgumentCaptor<List<Long>> ids = ArgumentCaptor.captor();
        verify(mapper).resolveByIds(eqInt(9), ids.capture(), eqTime(resolvedAt));
        assertEquals(25, ids.getValue().size());
        assertEquals(51L, ids.getValue().getFirst());
        assertEquals(75L, ids.getValue().getLast());
    }

    private static int eqInt(int value) {
        return org.mockito.ArgumentMatchers.eq(value);
    }

    private static LocalDateTime eqTime(LocalDateTime value) {
        return org.mockito.ArgumentMatchers.eq(value);
    }

    private static RelationshipSignalFamilyState familyState(LocalDateTime lastAttemptAt) {
        RelationshipSignalFamilyState state = new RelationshipSignalFamilyState();
        state.setWorkspaceId(9);
        state.setFamily("relationship_decay");
        state.setStatus("available");
        state.setLastAttemptAt(lastAttemptAt);
        return state;
    }
}
