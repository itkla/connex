package ooo.klae.connex.backend.work;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import ooo.klae.connex.backend.dto.ApprovalInboxCursor;
import ooo.klae.connex.backend.dto.ApprovalInboxItemDto;
import ooo.klae.connex.backend.dto.ApprovalInboxScanPage;
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
        when(approvalService.scanInbox(AS_OF, null, 200)).thenReturn(page(
            List.of(
                item(8, 21, "2026-08-29 12:00:00", true),
                item(8, 22, null, false)),
            null,
            2,
            true));

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
    void urgencyFilterContinuesPastNonmatchingRawPage() {
        ApprovalInboxCursor cursor = cursor(1);
        when(approvalService.scanInbox(AS_OF, null, 200)).thenReturn(page(
            List.of(
                item(8, 21, "2026-08-28 12:00:00", true),
                item(9, 22, "2026-08-29 12:00:00", false)),
            cursor,
            200,
            false));
        when(approvalService.scanInbox(AS_OF, cursor, 200)).thenReturn(page(
            List.of(item(10, 23, null, false)), null, 1, true));

        var result = provider().load(new WorkItemProviderQuery(
            7, 42, TODAY, AS_OF, Set.of(WorkItemUrgency.normal), 25));

        assertEquals(List.of("document_approval:10:23"),
            result.items().stream().map(row -> row.id()).toList());
        assertEquals(1, result.matchingTotal());
        assertEquals(3, result.overallTotal());
        assertTrue(result.totalsComplete());
    }

    @Test
    void rawCeilingKeepsSourceAvailableButDeclaresTotalsTruncated() {
        AtomicInteger pageNumber = new AtomicInteger();
        when(approvalService.scanInbox(
                eq(AS_OF), nullable(ApprovalInboxCursor.class), anyInt()))
            .thenAnswer(invocation -> {
                int number = pageNumber.incrementAndGet();
                return page(
                    number == 1 ? List.of(item(8, 21, null, false)) : List.of(),
                    cursor(number),
                    DocumentApprovalService.WORK_RAW_PAGE_SIZE,
                    false);
            });

        var result = provider().load(new WorkItemProviderQuery(
            7, 42, TODAY, AS_OF, Set.of(), 1));

        assertFalse(result.totalsComplete());
        assertEquals(1, result.matchingTotal());
        assertEquals(1, result.overallTotal());
        assertEquals(
            DocumentApprovalService.MAX_WORK_RAW_ROWS
                / DocumentApprovalService.WORK_RAW_PAGE_SIZE,
            pageNumber.get());
    }

    @Test
    void midScanFreshnessMutationCannotDuplicateAnApprovalStep() {
        ApprovalInboxCursor cursor = cursor(1);
        when(approvalService.scanInbox(AS_OF, null, 200)).thenReturn(page(
            List.of(item(8, 21, null, false,
                Instant.parse("2026-08-21T00:00:00Z"), "a".repeat(64))),
            cursor,
            200,
            false));
        when(approvalService.scanInbox(AS_OF, cursor, 200)).thenReturn(page(
            List.of(
                item(8, 21, null, false,
                    Instant.parse("2026-08-22T00:00:00Z"), "b".repeat(64)),
                item(9, 22, null, false)),
            null,
            2,
            true));

        var result = provider().load(new WorkItemProviderQuery(
            7, 42, TODAY, AS_OF, Set.of(), 25));

        assertEquals(List.of(
            "document_approval:8:21",
            "document_approval:9:22"), result.items().stream().map(row -> row.id()).toList());
        assertEquals(2, result.matchingTotal());
        assertEquals(2, result.overallTotal());
    }

    @Test
    void scanUsesOneRepeatableReadSnapshot() throws ReflectiveOperationException {
        Transactional transaction = DocumentApprovalWorkItemProvider.class
            .getMethod("load", WorkItemProviderQuery.class)
            .getAnnotation(Transactional.class);

        assertTrue(transaction.readOnly());
        assertEquals(Isolation.REPEATABLE_READ, transaction.isolation());
    }

    @Test
    void wholeSecondDeadlineMatchesSqlUrgencyPartition() {
        Instant subsecondAsOf = AS_OF.plusNanos(900_000_000);
        when(approvalService.scanInbox(subsecondAsOf, null, 200)).thenReturn(page(
            List.of(item(8, 21, "2026-08-30 12:00:00", false)), null, 1, true));

        var result = provider().load(new WorkItemProviderQuery(
            7, 42, TODAY, subsecondAsOf, Set.of(), 25));

        assertEquals(WorkItemUrgency.normal, result.items().getFirst().urgency());
    }

    @Test
    void openContextUsesCanonicalDealDocumentsAnchor() {
        when(approvalService.scanInbox(AS_OF, null, 200)).thenReturn(page(
            List.of(item(8, 21, null, false)), null, 1, true));

        var result = provider().load(new WorkItemProviderQuery(
            7, 42, TODAY, AS_OF, Set.of(), 25));

        assertEquals("/records/deals/5#deal-documents",
            result.items().getFirst().context().href());
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

    private static ApprovalInboxScanPage page(
            List<ApprovalInboxItemDto> items,
            ApprovalInboxCursor cursor,
            int rawRows,
            boolean exhausted) {
        return new ApprovalInboxScanPage(items, cursor, rawRows, exhausted);
    }

    private static ApprovalInboxCursor cursor(int id) {
        return new ApprovalInboxCursor(
            2, "9999-12-31", "2026-08-21 00:00:00", id, id);
    }

    private static ApprovalInboxItemDto item(
            int approvalId,
            int stepId,
            String dueAt,
            boolean escalated) {
        return item(
            approvalId,
            stepId,
            dueAt,
            escalated,
            Instant.parse("2026-08-21T00:00:00Z"),
            "a".repeat(64));
    }

    private static ApprovalInboxItemDto item(
            int approvalId,
            int stepId,
            String dueAt,
            boolean escalated,
            Instant freshnessAt,
            String currentVersion) {
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
            freshnessAt,
            currentVersion);
    }
}
