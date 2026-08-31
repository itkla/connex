package ooo.klae.connex.backend.work;

import java.time.LocalDate;
import java.util.Comparator;

import ooo.klae.connex.backend.dto.WorkItemDto;
import ooo.klae.connex.backend.dto.WorkItemSource;
import ooo.klae.connex.backend.dto.WorkItemUrgency;

/** Exact total ordering shared by providers and the merged My Work page. */
public final class WorkItemOrdering {
    private static final LocalDate LAST_DATE = LocalDate.of(9999, 12, 31);

    private WorkItemOrdering() {
    }

    /** Returns the contract-defined stable comparator. */
    public static Comparator<WorkItemDto> comparator() {
        return Comparator
            .comparingInt((WorkItemDto item) -> urgencyRank(item.urgency()))
            .thenComparing(item -> item.dueDate() == null ? LAST_DATE : item.dueDate())
            .thenComparing(WorkItemDto::freshnessAt)
            .thenComparingInt(item -> sourceRank(item.source()))
            .thenComparingInt(WorkItemDto::sourceId)
            .thenComparingInt(item -> item.context().stepId() == null
                ? 0
                : item.context().stepId());
    }

    private static int urgencyRank(WorkItemUrgency urgency) {
        return switch (urgency) {
            case critical -> 0;
            case high -> 1;
            case normal -> 2;
            case low -> 3;
        };
    }

    private static int sourceRank(WorkItemSource source) {
        return switch (source) {
            case task -> 0;
            case document_approval -> 1;
            case notification -> 2;
        };
    }
}
