package ooo.klae.connex.backend.work;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.dto.WorkItemActionResponse;
import ooo.klae.connex.backend.dto.WorkItemAvailability;
import ooo.klae.connex.backend.dto.WorkItemContextDto;
import ooo.klae.connex.backend.dto.WorkItemDto;
import ooo.klae.connex.backend.dto.WorkItemReasonCode;
import ooo.klae.connex.backend.dto.WorkItemReasonDto;
import ooo.klae.connex.backend.dto.WorkItemSource;
import ooo.klae.connex.backend.dto.WorkItemSourceAvailability;
import ooo.klae.connex.backend.dto.WorkItemUrgency;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.services.UserCalendarService;
import ooo.klae.connex.backend.services.WorkspaceService;

@ExtendWith(MockitoExtension.class)
class WorkItemServiceTest {
    private static final Instant AS_OF = Instant.parse("2026-08-30T12:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 30);

    @Mock private WorkspaceService workspaceService;
    @Mock private UserCalendarService userCalendarService;

    @BeforeEach
    void setUp() {
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(7);
        when(workspaceService.getCurrentUserId()).thenReturn(42);
        when(userCalendarService.today()).thenReturn(TODAY);
    }

    @Test
    void mergesOnceWithExactOrderingAndAppliesGlobalPagination() {
        WorkItemDto taskCritical = item(
            WorkItemSource.task, 9, WorkItemUrgency.critical, TODAY.minusDays(1), AS_OF.minusSeconds(5), null);
        WorkItemDto notificationCritical = item(
            WorkItemSource.notification, 1, WorkItemUrgency.critical, TODAY.minusDays(1), AS_OF.minusSeconds(5), null);
        WorkItemDto approvalNormal = item(
            WorkItemSource.document_approval, 3, WorkItemUrgency.normal, null, AS_OF.minusSeconds(20), 8);
        WorkItemService service = service(
            available(WorkItemSource.task, List.of(taskCritical), 1, true),
            available(WorkItemSource.notification, List.of(notificationCritical), 1, true),
            available(WorkItemSource.document_approval, List.of(approvalNormal), 1, true));

        var page = service.getPage(null, null, 2, 2);

        assertEquals(List.of(approvalNormal.id()), page.items().stream().map(WorkItemDto::id).toList());
        assertEquals(3, page.knownMatchingTotal());
        assertEquals(3, page.knownOverallTotal());
        assertFalse(page.hasNext());
        assertTrue(page.hasNextKnown());
    }

    @Test
    void tieBreaksApprovalStepsByStepIdAfterTheContractKey() {
        WorkItemDto second = item(
            WorkItemSource.document_approval, 3, WorkItemUrgency.normal, null, AS_OF, 12);
        WorkItemDto first = item(
            WorkItemSource.document_approval, 3, WorkItemUrgency.normal, null, AS_OF, 4);
        WorkItemService service = service(
            available(WorkItemSource.task, List.of(), 0, true),
            available(WorkItemSource.notification, List.of(), 0, true),
            available(WorkItemSource.document_approval, List.of(second, first), 2, true));

        var page = service.getPage(null, null, 1, 25);

        assertEquals(List.of(first.id(), second.id()), page.items().stream().map(WorkItemDto::id).toList());
    }

    @Test
    void isolatesOneProviderFailureAndNeverTurnsItIntoZero() {
        WorkItemService service = service(
            available(WorkItemSource.task, List.of(), 0, true),
            failing(WorkItemSource.notification, new IllegalStateException("sql detail")),
            available(WorkItemSource.document_approval, List.of(), 0, true));

        var page = service.getPage(null, null, 1, 25);

        assertEquals(WorkItemAvailability.partial, page.availability());
        assertFalse(page.totalsComplete());
        var status = page.sourceStatuses().stream()
            .filter(candidate -> candidate.source() == WorkItemSource.notification)
            .findFirst().orElseThrow();
        assertEquals(WorkItemSourceAvailability.unavailable, status.status());
        assertEquals("provider_unavailable", status.errorCode());
        assertEquals(null, status.matchingTotal());
        assertEquals(null, status.overallTotal());
    }

    @Test
    void knownAvailableTotalsStillProveANextPageDuringPartialFailure() {
        WorkItemDto first = item(
            WorkItemSource.task, 1, WorkItemUrgency.normal, null, AS_OF, null);
        WorkItemService service = service(
            available(WorkItemSource.task, List.of(first), 2, true),
            failing(WorkItemSource.notification, new IllegalStateException()),
            available(WorkItemSource.document_approval, List.of(), 0, true));

        var page = service.getPage(null, null, 1, 1);

        assertTrue(page.hasNext());
        assertTrue(page.hasNextKnown());
        assertFalse(page.totalsComplete());
    }

    @Test
    void everyProviderFailureIsUnavailableRatherThanEmpty() {
        WorkItemService service = service(
            failing(WorkItemSource.task, new IllegalStateException()),
            failing(WorkItemSource.notification, new InvalidWorkItemSourceRowsException()),
            failing(WorkItemSource.document_approval, new IllegalStateException()));

        var page = service.getPage(null, null, 1, 25);

        assertTrue(page.items().isEmpty());
        assertEquals(WorkItemAvailability.unavailable, page.availability());
        assertFalse(page.totalsComplete());
        assertFalse(page.hasNextKnown());
    }

    @Test
    void everyExplicitlySelectedForbiddenProviderReturnsForbidden() {
        WorkItemService service = service(
            available(WorkItemSource.task, List.of(), 0, true),
            available(WorkItemSource.notification, List.of(), 0, true),
            failing(WorkItemSource.document_approval, new ForbiddenException("denied")));

        assertThrows(
            ForbiddenException.class,
            () -> service.getPage(List.of("document_approval"), null, 1, 25));
    }

    @Test
    void validEmptyAndTruncatedAvailableAreDistinguished() {
        WorkItemService exact = service(
            available(WorkItemSource.task, List.of(), 0, true),
            available(WorkItemSource.notification, List.of(), 0, true),
            available(WorkItemSource.document_approval, List.of(), 0, true));
        WorkItemService truncated = service(
            available(WorkItemSource.task, List.of(), 0, true),
            available(WorkItemSource.notification, List.of(), 0, true),
            available(WorkItemSource.document_approval, List.of(), 1000, false));

        var empty = exact.getPage(null, null, 1, 25);
        var bounded = truncated.getPage(null, null, 1, 25);

        assertEquals(WorkItemAvailability.available, empty.availability());
        assertTrue(empty.totalsComplete());
        assertEquals(WorkItemAvailability.available, bounded.availability());
        assertFalse(bounded.totalsComplete());
    }

    @Test
    void sourceAndUrgencyFiltersReachOnlySelectedProvider() {
        CapturingProvider task = available(WorkItemSource.task, List.of(), 0, true);
        WorkItemService service = service(
            task,
            failing(WorkItemSource.notification, new AssertionErrorException()),
            failing(WorkItemSource.document_approval, new AssertionErrorException()));

        service.getPage(List.of("task"), List.of("critical", "high"), 1, 10);

        assertEquals(List.of(WorkItemUrgency.critical, WorkItemUrgency.high),
            task.query.urgencies().stream().sorted().toList());
        assertEquals(10, task.query.candidateLimit());
    }

    private WorkItemService service(WorkItemProvider... providers) {
        return new WorkItemService(
            List.of(providers),
            workspaceService,
            userCalendarService,
            Clock.fixed(AS_OF, ZoneOffset.UTC));
    }

    private static CapturingProvider available(
            WorkItemSource source,
            List<WorkItemDto> items,
            long total,
            boolean complete) {
        return new CapturingProvider(source, query -> new WorkItemProviderResult(
            items, total, total, query.asOf(), complete));
    }

    private static CapturingProvider failing(WorkItemSource source, RuntimeException failure) {
        return new CapturingProvider(source, query -> {
            throw failure;
        });
    }

    private static WorkItemDto item(
            WorkItemSource source,
            int sourceId,
            WorkItemUrgency urgency,
            LocalDate dueDate,
            Instant freshness,
            Integer stepId) {
        String id = source == WorkItemSource.document_approval
            ? "document_approval:" + sourceId + ":" + stepId
            : source.name() + ":" + sourceId;
        return new WorkItemDto(
            id,
            source,
            sourceId,
            id,
            new WorkItemReasonDto(WorkItemReasonCode.task_open, dueDate, null, null),
            dueDate,
            urgency,
            List.of(),
            freshness,
            AS_OF,
            "a".repeat(64),
            "\"" + "a".repeat(64) + "\"",
            new WorkItemContextDto("task", sourceId, id, "/items/" + sourceId,
                stepId, null, null, null, null),
            List.of());
    }

    private static final class CapturingProvider implements WorkItemProvider {
        private final WorkItemSource source;
        private final Function<WorkItemProviderQuery, WorkItemProviderResult> loader;
        private WorkItemProviderQuery query;

        private CapturingProvider(
                WorkItemSource source,
                Function<WorkItemProviderQuery, WorkItemProviderResult> loader) {
            this.source = source;
            this.loader = loader;
        }

        @Override
        public WorkItemSource source() {
            return source;
        }

        @Override
        public WorkItemProviderResult load(WorkItemProviderQuery query) {
            this.query = query;
            return loader.apply(query);
        }

        @Override
        public WorkItemActionResponse execute(int sourceId, WorkItemActionCommand command) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class AssertionErrorException extends RuntimeException {
    }
}
