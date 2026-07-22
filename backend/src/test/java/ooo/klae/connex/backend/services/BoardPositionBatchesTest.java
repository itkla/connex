package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.dto.BoardPositionUpdate;

class BoardPositionBatchesTest {

    @Test
    void retainsGlobalPositionsAcrossBatchBoundary() {
        List<Integer> ids = IntStream.range(0, 501).boxed().toList();

        List<List<BoardPositionUpdate>> batches = BoardPositionBatches.fromOrderedIds(ids, 500);

        assertEquals(2, batches.size());
        assertEquals(500, batches.getFirst().size());
        assertEquals(new BoardPositionUpdate(499, 499), batches.getFirst().getLast());
        assertEquals(List.of(new BoardPositionUpdate(500, 500)), batches.getLast());
    }

    @Test
    void excludesMovedRecordWithoutConsumingSiblingCapacity() {
        List<Integer> ids = IntStream.range(0, 501).boxed().toList();

        List<List<BoardPositionUpdate>> batches =
            BoardPositionBatches.fromOrderedIdsExcluding(ids, 250, 500);

        assertEquals(1, batches.size());
        assertEquals(500, batches.getFirst().size());
        assertEquals(new BoardPositionUpdate(249, 249), batches.getFirst().get(249));
        assertEquals(new BoardPositionUpdate(251, 251), batches.getFirst().get(250));
        assertEquals(new BoardPositionUpdate(500, 500), batches.getFirst().getLast());
    }

    @Test
    void emitsSecondBatchForFirstSiblingBeyondBoundary() {
        List<Integer> ids = IntStream.range(0, 502).boxed().toList();

        List<List<BoardPositionUpdate>> batches =
            BoardPositionBatches.fromOrderedIdsExcluding(ids, 250, 500);

        assertEquals(2, batches.size());
        assertEquals(500, batches.getFirst().size());
        assertEquals(List.of(new BoardPositionUpdate(501, 501)), batches.getLast());
    }

    @Test
    void returnsNoBatchesForEmptySiblingOrder() {
        assertTrue(BoardPositionBatches.fromOrderedIds(List.of(), 500).isEmpty());
        assertTrue(BoardPositionBatches.fromOrderedIdsExcluding(List.of(3), 3, 500).isEmpty());
    }
}
