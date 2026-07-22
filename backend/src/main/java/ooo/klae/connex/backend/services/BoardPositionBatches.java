package ooo.klae.connex.backend.services;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntPredicate;

import ooo.klae.connex.backend.dto.BoardPositionUpdate;

/** Builds bounded position-update batches while retaining indices from the full board order. */
final class BoardPositionBatches {
    private BoardPositionBatches() { }

    /** Partitions every ordered id into immutable batches with its dense 0-based position. */
    static List<List<BoardPositionUpdate>> fromOrderedIds(List<Integer> ids, int batchSize) {
        return partition(ids, batchSize, ignored -> true);
    }

    /** Partitions ordered sibling ids while omitting one moved record from the generated updates. */
    static List<List<BoardPositionUpdate>> fromOrderedIdsExcluding(
            List<Integer> ids, int excludedId, int batchSize) {
        return partition(ids, batchSize, id -> id != excludedId);
    }

    private static List<List<BoardPositionUpdate>> partition(
            List<Integer> ids, int batchSize, IntPredicate included) {
        if (batchSize <= 0) throw new IllegalArgumentException("Batch size must be positive");
        List<List<BoardPositionUpdate>> batches = new ArrayList<>();
        List<BoardPositionUpdate> current = new ArrayList<>(Math.min(batchSize, ids.size()));
        for (int index = 0; index < ids.size(); index++) {
            int id = ids.get(index);
            if (!included.test(id)) continue;
            current.add(new BoardPositionUpdate(id, index));
            if (current.size() == batchSize) {
                batches.add(List.copyOf(current));
                current = new ArrayList<>(Math.min(batchSize, ids.size() - index - 1));
            }
        }
        if (!current.isEmpty()) batches.add(List.copyOf(current));
        return List.copyOf(batches);
    }
}
