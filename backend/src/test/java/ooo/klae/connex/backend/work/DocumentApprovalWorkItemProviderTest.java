package ooo.klae.connex.backend.work;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.dto.ApprovalInboxItemDto;
import ooo.klae.connex.backend.dto.WorkItemAction;
import ooo.klae.connex.backend.dto.WorkItemUrgency;
import ooo.klae.connex.backend.services.DocumentApprovalService;

@ExtendWith(MockitoExtension.class)
class DocumentApprovalWorkItemProviderTest {
    private static final Instant AS_OF = Instant.parse("2026-08-30T12:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 30);

    @Mock private DocumentApprovalService approvalService;

    @Test
    void preservesParallelActionableStepsAndRanksOverdueHigh() {
        when(approvalService.inbox(25)).thenReturn(List.of(
            item(8, 22, null, false),
            item(8, 21, "2026-08-29 12:00:00", true)));

        var result = provider().load(new WorkItemProviderQuery(
            7, 42, TODAY, AS_OF, Set.of(), 25));

        assertEquals(List.of(
            "document_approval:8:21",
            "document_approval:8:22"), result.items().stream().map(row -> row.id()).toList());
        assertEquals(WorkItemUrgency.high, result.items().getFirst().urgency());
        assertEquals(21, result.items().getFirst().context().stepId());
        assertEquals(2, result.overallTotal());
    }

    @Test
    void ceilingKeepsSourceAvailableButDeclaresTotalsTruncated() {
        when(approvalService.inbox(1)).thenReturn(List.of(item(8, 21, null, false)));

        var result = provider().load(new WorkItemProviderQuery(
            7, 42, TODAY, AS_OF, Set.of(), 1));

        assertFalse(result.totalsComplete());
        assertEquals(1, result.matchingTotal());
        assertEquals(1, result.overallTotal());
    }

    @Test
    void delegatesTheExactStepAndChainVersion() {
        provider().execute(8, new WorkItemActionCommand(
            WorkItemAction.approve,
            "a".repeat(64),
            null,
            "approved",
            "ok",
            21));

        verify(approvalService).decideWorkItem(8, 21, "approved", "ok", "a".repeat(64));
    }

    private DocumentApprovalWorkItemProvider provider() {
        return new DocumentApprovalWorkItemProvider(
            approvalService, Clock.fixed(AS_OF, ZoneOffset.UTC));
    }

    private static ApprovalInboxItemDto item(
            int approvalId,
            int stepId,
            String dueAt,
            boolean escalated) {
        return new ApprovalInboxItemDto(
            approvalId,
            5,
            "Renewal",
            9,
            "Quote",
            "quote",
            3,
            stepId,
            stepId - 20,
            "Review " + stepId,
            1,
            dueAt,
            escalated,
            7,
            "Requester",
            "2026-08-20 00:00:00",
            Instant.parse("2026-08-21T00:00:00Z"),
            "a".repeat(64));
    }
}
