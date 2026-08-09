package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import ooo.klae.connex.backend.beans.RelationshipSignal;
import ooo.klae.connex.backend.mappers.RelationshipSignalMapper;

class RelationshipSignalWriteServiceTest {
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
}
