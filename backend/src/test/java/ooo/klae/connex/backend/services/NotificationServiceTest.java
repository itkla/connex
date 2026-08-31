package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

import ooo.klae.connex.backend.beans.Notification;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.FacetCount;
import ooo.klae.connex.backend.dto.NotificationCountsDto;
import ooo.klae.connex.backend.dto.NotificationDto;
import ooo.klae.connex.backend.dto.NotificationFacets;
import ooo.klae.connex.backend.dto.WorkItemUrgency;
import ooo.klae.connex.backend.dto.SnoozeRequest;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.NotificationMapper;
import ooo.klae.connex.backend.notifications.NotificationProperties;
import ooo.klae.connex.backend.notifications.NotificationStateVersionService;
import ooo.klae.connex.backend.work.InvalidWorkItemSourceRowsException;
import ooo.klae.connex.backend.work.WorkItemStateHash;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {
    @Mock private NotificationMapper notificationMapper;
    @Mock private AuthService authService;
    @Mock private NotificationStateVersionService stateVersionService;
    @Mock private NotificationQuietHoursService quietHoursService;
    @Mock private WorkspaceService workspaceService;
    @Mock private DocumentApprovalService documentApprovalService;

    private NotificationService service;

    @BeforeEach
    void setUp() {
        NotificationProperties properties = new NotificationProperties();
        properties.setMaxPageSize(100);
        service = new NotificationService(
            notificationMapper,
            authService,
            properties,
            stateVersionService,
            new NotificationSnoozeResolver(Clock.fixed(
                Instant.parse("2026-07-20T02:00:00Z"), ZoneOffset.UTC)),
            quietHoursService,
            workspaceService,
            documentApprovalService,
            new ImmediateApprovalMutationRetryService(),
            new ObjectMapper(),
            Clock.fixed(Instant.parse("2026-07-20T02:00:00Z"), ZoneOffset.UTC)
        );
        User user = new User();
        user.setId(42);
        when(authService.getCurrentUser()).thenReturn(user);
        lenient().when(workspaceService.getCurrentWorkspaceId()).thenReturn(7);
        lenient().when(quietHoursService.evaluateForUser(42, Instant.parse("2026-06-25T00:00:00Z")))
            .thenReturn(new NotificationQuietHoursEvaluator.Evaluation(false, null));
    }

    @Test
    void restoreAfterTerminalApprovalClearsReadButKeepsSourceResolution() {
        Notification terminal = approvalRequestNotification();
        terminal.setResolvedAt("2026-08-30 12:00:00");
        terminal.setReadAt("2026-08-30 13:00:00");
        Notification restoredRow = approvalRequestNotification();
        restoredRow.setResolvedAt("2026-08-30 12:00:00");
        when(notificationMapper.findById(42, 99)).thenReturn(terminal, restoredRow);
        when(notificationMapper.findByIdForUpdate(42, 99)).thenReturn(terminal);
        when(notificationMapper.restoreResolvedApprovalRequest(7, 42, 99)).thenReturn(1);
        when(stateVersionService.bumpNow(42)).thenReturn(18L);
        approvalRestoreLocks(false);

        NotificationDto restored = service.restore(99);

        assertEquals("2026-08-30 12:00:00", restored.getResolvedAt());
        assertNull(restored.getReadAt());
        verify(notificationMapper, never()).restore(42, 99);
        verify(notificationMapper, never()).restoreActionableApprovalRequest(7, 42, 99);
        verify(notificationMapper).restoreResolvedApprovalRequest(7, 42, 99);
    }

    @Test
    void restoreDismissedTerminalApprovalClearsDismissalButRetainsResolution() {
        Notification terminal = approvalRequestNotification();
        terminal.setDismissedAt("2026-08-29 12:00:00");
        terminal.setResolvedAt("2026-08-30 12:00:00");
        Notification restored = approvalRequestNotification();
        restored.setResolvedAt("2026-08-30 12:00:00");
        when(notificationMapper.findById(42, 99)).thenReturn(terminal, restored);
        when(notificationMapper.findByIdForUpdate(42, 99)).thenReturn(terminal);
        when(notificationMapper.restoreResolvedApprovalRequest(7, 42, 99)).thenReturn(1);
        when(stateVersionService.bumpNow(42)).thenReturn(18L);
        approvalRestoreLocks(false);

        NotificationDto result = service.restore(99);

        assertEquals("2026-08-30 12:00:00", result.getResolvedAt());
        verify(notificationMapper).restoreResolvedApprovalRequest(7, 42, 99);
        verify(notificationMapper, never()).restoreActionableApprovalRequest(7, 42, 99);
    }

    @Test
    void restoreActionableApprovalMayClearSourceResolution() {
        Notification archived = approvalRequestNotification();
        archived.setDismissedAt("2026-08-29 12:00:00");
        archived.setResolvedAt("2026-08-30 12:00:00");
        Notification restored = approvalRequestNotification();
        when(notificationMapper.findById(42, 99)).thenReturn(archived, restored);
        when(notificationMapper.findByIdForUpdate(42, 99)).thenReturn(archived);
        when(notificationMapper.restoreActionableApprovalRequest(7, 42, 99)).thenReturn(1);
        approvalRestoreLocks(true);

        NotificationDto result = service.restore(99);

        assertNull(result.getResolvedAt());
        verify(notificationMapper).restoreActionableApprovalRequest(7, 42, 99);
        verify(notificationMapper, never()).restoreResolvedApprovalRequest(7, 42, 99);
    }

    @Test
    void dealCloseProjectionValidatesTypedDataAndReturnsCanonicalVersion() {
        Notification row = dealCloseNotification();
        when(notificationMapper.findActiveDealCloseWork(
                7, 42, "2026-07-20 02:00:00", List.of("critical"), 25))
            .thenReturn(List.of(row));
        when(notificationMapper.countActiveDealCloseWork(
                7, 42, "2026-07-20 02:00:00", List.of("critical")))
            .thenReturn(1L);
        when(notificationMapper.countActiveDealCloseWork(
                7, 42, "2026-07-20 02:00:00", List.of()))
            .thenReturn(2L);

        var page = service.findActiveDealCloseWork(
            7,
            Instant.parse("2026-07-20T02:00:00Z"),
            Set.of(WorkItemUrgency.critical),
            25);

        assertEquals(1, page.items().size());
        assertEquals("2026-07-21", page.items().getFirst().expectedCloseDate().toString());
        assertEquals(64, page.items().getFirst().currentVersion().length());
        assertEquals(1, page.matchingTotal());
        assertEquals(2, page.overallTotal());
    }

    @Test
    void malformedDealCloseDataFailsTheProviderInsteadOfLookingEmpty() {
        Notification row = dealCloseNotification();
        row.setData("{}");
        when(notificationMapper.findActiveDealCloseWork(
                7, 42, "2026-07-20 02:00:00", List.of("critical", "warning"), 25))
            .thenReturn(List.of(row));

        assertThrows(InvalidWorkItemSourceRowsException.class, () ->
            service.findActiveDealCloseWork(
                7, Instant.parse("2026-07-20T02:00:00Z"), 25));
    }

    @Test
    void versionAwareDismissComparesAfterTheScopedLock() {
        Notification row = dealCloseNotification();
        String expected = workVersion(row);
        when(notificationMapper.findActiveDealCloseByIdForUpdate(
                7, 42, row.getId(), "2026-07-20 02:00:00",
                List.of("critical", "warning")))
            .thenReturn(row);
        when(notificationMapper.dismiss(42, row.getId())).thenReturn(1);
        when(notificationMapper.findById(42, row.getId())).thenReturn(row);
        when(stateVersionService.bumpNow(42)).thenReturn(18L);

        NotificationDto response = service.dismiss(row.getId(), expected);

        verify(notificationMapper).findActiveDealCloseByIdForUpdate(
            7, 42, row.getId(), "2026-07-20 02:00:00",
            List.of("critical", "warning"));
        verify(notificationMapper).dismiss(42, row.getId());
        assertEquals(18L, response.getStateVersion());
    }

    @Test
    void staleDealCloseVersionConflictsWithoutMutation() {
        Notification row = dealCloseNotification();
        when(notificationMapper.findActiveDealCloseByIdForUpdate(
                7, 42, row.getId(), "2026-07-20 02:00:00",
                List.of("critical", "warning")))
            .thenReturn(row);

        assertThrows(ConflictException.class, () ->
            service.dismiss(row.getId(), "f".repeat(64)));

        verify(notificationMapper, never()).dismiss(42, row.getId());
    }

    private static Notification dealCloseNotification() {
        Notification row = new Notification();
        row.setId(11);
        row.setWorkspaceId(7);
        row.setRecipientId(42);
        row.setType("deal.close");
        row.setSeverity("critical");
        row.setSourceType("deal");
        row.setSourceId(5);
        row.setData("{\"dealId\":5,\"expectedCloseDate\":\"2026-07-21\"}");
        row.setTriggeredAt("2026-07-19 00:00:00");
        row.setUpdatedAt("2026-07-19 00:00:00");
        return row;
    }

    private static String workVersion(Notification row) {
        return WorkItemStateHash.sha256(
            row.getId(),
            row.getSeverity(),
            row.getData(),
            row.getReadAt(),
            row.getDismissedAt(),
            row.getResolvedAt(),
            row.getSnoozedUntil(),
            row.getSnoozeTimezone(),
            row.getUpdatedAt());
    }

    @Test
    void pageUsesOffsetPaginationScopedToTheRecipientAcrossWorkspaces() {
        when(notificationMapper.getDatabaseUtcTimestamp()).thenReturn("2026-06-25 00:00:00");
        when(notificationMapper.findPage(
                42, "unread", null, List.of("task"), null, null,
                "deal", 55, "2026-06-25 00:00:00", 20, 40))
            .thenReturn(List.of());
        when(notificationMapper.countPage(
                42, "unread", null, List.of("task"), null, null,
                "deal", 55, "2026-06-25 00:00:00"))
            .thenReturn(0L);
        when(notificationMapper.getStateVersion(42)).thenReturn(19L);

        var page = service.getPage("unread", "task", "deal", 55, 3, 20);

        verify(notificationMapper).findPage(
            42, "unread", null, List.of("task"), null, null,
            "deal", 55, "2026-06-25 00:00:00", 20, 40);
        verify(notificationMapper).countPage(
            42, "unread", null, List.of("task"), null, null,
            "deal", 55, "2026-06-25 00:00:00");
        assertEquals(19L, page.stateVersion());
        assertEquals("2026-06-25T00:00:00Z", page.asOf());
    }

    @Test
    void facetsAreAssembledForTheAuthenticatedRecipient() {
        List<FacetCount> categories = List.of(new FacetCount("task", 3, null));
        List<FacetCount> severities = List.of(new FacetCount("warning", 2, null));
        List<FacetCount> workspaces = List.of(new FacetCount("7", 4, "Sales"));
        when(notificationMapper.countsByCategory(42)).thenReturn(categories);
        when(notificationMapper.countsBySeverity(42)).thenReturn(severities);
        when(notificationMapper.countsByWorkspace(42)).thenReturn(workspaces);
        when(notificationMapper.getStateVersion(42)).thenReturn(12L);

        NotificationFacets result = service.getFacets();

        assertSame(categories, result.categories());
        assertSame(severities, result.severities());
        assertSame(workspaces, result.workspaces());
        assertEquals(12L, result.stateVersion());
        verify(notificationMapper).countsByCategory(42);
        verify(notificationMapper).countsBySeverity(42);
        verify(notificationMapper).countsByWorkspace(42);
        verify(notificationMapper).getStateVersion(42);
    }

    @Test
    void zeroRowMutationIsNotFoundAfterScopedRead() {
        Notification current = new Notification();
        current.setId(99);
        when(notificationMapper.findById(42, 99)).thenReturn(current);
        when(notificationMapper.markRead(42, 99)).thenReturn(0);

        assertThrows(ResourceNotFoundException.class, () -> service.markRead(99));

        verify(notificationMapper).findById(42, 99);
        verify(notificationMapper).markRead(42, 99);
    }

    @Test
    void mutationCannotCrossRecipientScope() {
        when(notificationMapper.findById(42, 99)).thenReturn(null);

        assertThrows(ResourceNotFoundException.class, () -> service.markRead(99));

        verify(notificationMapper, never()).markRead(42, 99);
    }

    @Test
    void markAllReadBindsOneDatabaseTimestampAndReturnsTheStateVersion() {
        NotificationCountsDto counts = new NotificationCountsDto();
        counts.setStateVersion(91L);
        when(notificationMapper.lockRecipientMemberships(42)).thenReturn(List.of(7));
        when(notificationMapper.getInboxCutoffId(42)).thenReturn(81L);
        when(notificationMapper.getDatabaseUtcTimestamp()).thenReturn("2026-06-25 00:00:00");
        when(notificationMapper.markAllRead(42, 81L, "2026-06-25 00:00:00")).thenReturn(2);
        when(notificationMapper.getUnreadCounts(42, "2026-06-25 00:00:00")).thenReturn(counts);

        NotificationCountsDto result = service.markAllRead();

        InOrder mutationOrder = inOrder(notificationMapper);
        mutationOrder.verify(notificationMapper).lockRecipientMemberships(42);
        mutationOrder.verify(notificationMapper).getDatabaseUtcTimestamp();
        mutationOrder.verify(notificationMapper).getInboxCutoffId(42);
        mutationOrder.verify(notificationMapper).markAllRead(42, 81L, "2026-06-25 00:00:00");
        mutationOrder.verify(notificationMapper).getUnreadCounts(42, "2026-06-25 00:00:00");
        assertEquals(81L, result.getCutoffId());
        assertEquals("2026-06-25 00:00:00", result.getReadAt());
        assertEquals(91L, result.getStateVersion());
        assertSame(counts, result);
        verify(stateVersionService).bumpNow(42);
    }

    @Test
    void mutationResponseCarriesTheCurrentStateVersion() {
        Notification current = new Notification();
        current.setId(99);
        current.setReadAt("2026-06-25 00:00:00");
        Notification updated = new Notification();
        updated.setId(99);
        when(notificationMapper.findById(42, 99)).thenReturn(current, updated);
        when(notificationMapper.markUnread(42, 99)).thenReturn(1);
        when(stateVersionService.bumpNow(42)).thenReturn(92L);

        NotificationDto result = service.markUnread(99);

        assertEquals(92L, result.getStateVersion());
        verify(stateVersionService).bumpNow(42);
    }

    @Test
    void idempotentMutationDoesNotAdvanceStateVersion() {
        Notification current = new Notification();
        current.setId(99);
        current.setReadAt("2026-06-25 00:00:00");
        when(notificationMapper.findById(42, 99)).thenReturn(current);
        when(notificationMapper.getStateVersion(42)).thenReturn(17L);

        NotificationDto result = service.markRead(99);

        assertEquals(17L, result.getStateVersion());
        verify(stateVersionService, never()).bumpNow(42);
    }

    @Test
    void statusAndStateCannotBothBePresent() {
        assertThrows(BadRequestException.class, () -> service.getPage(
            "active", "unread", null, null, null, null, null, null, 1, 25));

        verify(notificationMapper, never()).getDatabaseUtcTimestamp();
    }

    @Test
    void repeatableFiltersAndWorkspaceAreNormalizedAndForwarded() {
        when(workspaceService.getRole(7, 42)).thenReturn("member");
        when(notificationMapper.getDatabaseUtcTimestamp()).thenReturn("2026-06-25 00:00:00");
        when(notificationMapper.findPage(
                42, "snoozed", List.of("task.due", "deal.close"), List.of("task"),
                List.of("critical"), 7, null, null, "2026-06-25 00:00:00", 25, 0))
            .thenReturn(List.of());

        service.getPage(
            "SNOOZED",
            null,
            List.of(" task.due ", "deal.close", "task.due"),
            List.of("task"),
            List.of("critical"),
            7,
            null,
            null,
            1,
            25
        );

        verify(notificationMapper).findPage(
            42, "snoozed", List.of("task.due", "deal.close"), List.of("task"),
            List.of("critical"), 7, null, null, "2026-06-25 00:00:00", 25, 0);
    }

    @Test
    void snoozePersistsResolvedInstantTimezoneAndBumpsOnce() {
        Notification current = new Notification();
        current.setId(99);
        Notification updated = new Notification();
        updated.setId(99);
        updated.setSnoozedUntil("2026-07-20 13:00:00");
        updated.setSnoozeTimezone("America/New_York");
        when(notificationMapper.findByIdForUpdate(42, 99)).thenReturn(current);
        when(notificationMapper.findById(42, 99)).thenReturn(updated);
        when(notificationMapper.snooze(
            42, 99, "2026-07-20 13:00:00", "America/New_York")).thenReturn(1);
        when(stateVersionService.bumpNow(42)).thenReturn(22L);

        NotificationDto result = service.snooze(99, preset("next_week", "America/New_York"));

        assertEquals("2026-07-20T13:00:00Z", result.getSnoozedUntil());
        assertEquals("America/New_York", result.getSnoozeTimezone());
        assertEquals(22L, result.getStateVersion());
        verify(stateVersionService).bumpNow(42);
    }

    @Test
    void snoozingDismissedOrResolvedNotificationConflicts() {
        Notification dismissed = new Notification();
        dismissed.setId(99);
        dismissed.setDismissedAt("2026-07-19 00:00:00");
        when(notificationMapper.findByIdForUpdate(42, 99)).thenReturn(dismissed);

        assertThrows(ConflictException.class,
            () -> service.snooze(99, preset("tomorrow_morning", "UTC")));

        verify(notificationMapper, never()).snooze(
            org.mockito.ArgumentMatchers.anyInt(),
            org.mockito.ArgumentMatchers.anyInt(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void repeatedUnsnoozeIsIdempotentWithoutVersionBump() {
        Notification current = new Notification();
        current.setId(99);
        when(notificationMapper.findByIdForUpdate(42, 99)).thenReturn(current);
        when(notificationMapper.getStateVersion(42)).thenReturn(17L);

        NotificationDto result = service.unsnooze(99);

        assertEquals(17L, result.getStateVersion());
        verify(notificationMapper, never()).unsnooze(42, 99);
        verify(stateVersionService, never()).bumpNow(42);
    }

    private static SnoozeRequest preset(String preset, String timezone) {
        SnoozeRequest request = new SnoozeRequest();
        request.setPreset(preset);
        request.setTimezone(timezone);
        return request;
    }

    private void approvalRestoreLocks(boolean actionable) {
        DocumentApprovalService.ApprovalMutationLocks locks =
            new DocumentApprovalService.ApprovalMutationLocks(
                Set.of(42), Map.of(31, Set.of(42)), Set.of());
        when(documentApprovalService.lockApprovalMutationRecipients(7, 31, 42))
            .thenReturn(locks);
        when(documentApprovalService.approvalRequestActionableForRestore(
                7, 12, 31, 42, locks))
            .thenReturn(actionable);
    }

    private static Notification approvalRequestNotification() {
        Notification notification = new Notification();
        notification.setId(99);
        notification.setWorkspaceId(7);
        notification.setRecipientId(42);
        notification.setType("document.approval_request");
        notification.setSourceType("deal_document");
        notification.setSourceId(31);
        notification.setContextType("deal");
        notification.setContextId(12);
        return notification;
    }

    private static final class ImmediateApprovalMutationRetryService
            extends ApprovalMutationRetryService {
        private ImmediateApprovalMutationRetryService() {
            super(org.mockito.Mockito.mock(PlatformTransactionManager.class));
        }

        @Override
        public <T> T execute(Supplier<T> mutation) {
            return mutation.get();
        }
    }
}
