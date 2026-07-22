package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.beans.Notification;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.FacetCount;
import ooo.klae.connex.backend.dto.NotificationCountsDto;
import ooo.klae.connex.backend.dto.NotificationDto;
import ooo.klae.connex.backend.dto.NotificationFacets;
import ooo.klae.connex.backend.dto.SnoozeRequest;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.NotificationMapper;
import ooo.klae.connex.backend.notifications.NotificationProperties;
import ooo.klae.connex.backend.notifications.NotificationStateVersionService;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {
    @Mock private NotificationMapper notificationMapper;
    @Mock private AuthService authService;
    @Mock private NotificationStateVersionService stateVersionService;
    @Mock private NotificationQuietHoursService quietHoursService;
    @Mock private WorkspaceService workspaceService;

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
            workspaceService
        );
        User user = new User();
        user.setId(42);
        when(authService.getCurrentUser()).thenReturn(user);
        lenient().when(quietHoursService.evaluateForUser(42, Instant.parse("2026-06-25T00:00:00Z")))
            .thenReturn(new NotificationQuietHoursEvaluator.Evaluation(false, null));
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
}
