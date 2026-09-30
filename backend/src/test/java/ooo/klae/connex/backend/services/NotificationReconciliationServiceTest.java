package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.AbstractList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import ooo.klae.connex.backend.beans.DealReminderCandidate;
import ooo.klae.connex.backend.beans.HistoricalNotificationBaseline;
import ooo.klae.connex.backend.beans.Notification;
import ooo.klae.connex.backend.beans.NotificationPreference;
import ooo.klae.connex.backend.beans.OpenDealRecipient;
import ooo.klae.connex.backend.beans.RelationshipNudgeCandidate;
import ooo.klae.connex.backend.beans.TaskReminderCandidate;
import ooo.klae.connex.backend.dto.DealRiskDto;
import ooo.klae.connex.backend.dto.DealRiskFactor;
import ooo.klae.connex.backend.dto.RelationshipTemperatureDto;
import ooo.klae.connex.backend.mappers.NotificationMapper;
import ooo.klae.connex.backend.mappers.PreferenceMapper;
import ooo.klae.connex.backend.notifications.NotificationDelivery;
import ooo.klae.connex.backend.notifications.NotificationDispatcher;
import ooo.klae.connex.backend.notifications.NotificationProperties;
import ooo.klae.connex.backend.notifications.NotificationPushPublisher;
import ooo.klae.connex.backend.notifications.NotificationStateVersionService;
import tools.jackson.databind.ObjectMapper;

class NotificationReconciliationServiceTest {

    @Test
    void classifiesWarningCriticalAndInitialBackfillBoundaries() {
        LocalDate today = LocalDate.of(2026, 6, 24);

        assertEquals(
            NotificationReconciliationService.WARNING,
            NotificationReconciliationService.classify(today, today, 1, 30, false)
        );
        assertEquals(
            NotificationReconciliationService.WARNING,
            NotificationReconciliationService.classify(today.plusDays(1), today, 1, 30, false)
        );
        assertNull(
            NotificationReconciliationService.classify(today.plusDays(2), today, 1, 30, false)
        );
        assertEquals(
            NotificationReconciliationService.CRITICAL,
            NotificationReconciliationService.classify(today.minusDays(30), today, 1, 30, false)
        );
        assertNull(
            NotificationReconciliationService.classify(today.minusDays(31), today, 1, 30, false)
        );
        assertEquals(
            NotificationReconciliationService.CRITICAL,
            NotificationReconciliationService.classify(today.minusDays(90), today, 1, 30, true)
        );
    }

    @Test
    void reconciliationUsesRecipientZoneForLocalDate() {
        ReconciliationFixture fixture = ReconciliationFixture.withDispatcher();
        NotificationMapper notificationMapper = fixture.notificationMapper;
        PreferenceMapper preferenceMapper = fixture.preferenceMapper;
        NotificationDispatcher dispatcher = fixture.dispatcher;

        TaskReminderCandidate candidate = new TaskReminderCandidate();
        candidate.setWorkspaceId(7);
        candidate.setTaskId(91);
        candidate.setTaskLabel("Send proposal");
        candidate.setDueDate("2026-06-23");
        candidate.setRecipientId(42);
        candidate.setRecipientTimezone("Asia/Tokyo");

        when(notificationMapper.findWorkspaceRecipientIds(7)).thenReturn(List.of(42));
        when(notificationMapper.findReminderNotifications(7, 42)).thenReturn(List.of());
        when(preferenceMapper.findByWorkspaceAndChannel(7, "in_app")).thenReturn(List.of());
        when(notificationMapper.findTaskReminderCandidates(7)).thenReturn(List.of(candidate));
        when(notificationMapper.findDealReminderCandidates(7)).thenReturn(List.of());

        NotificationReconciliationService service = fixture.service();

        service.reconcileWorkspace(7, true);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(dispatcher).dispatch(captor.capture());
        assertEquals(NotificationReconciliationService.CRITICAL, captor.getValue().getSeverity());
        assertNull(captor.getValue().getContextType());
        assertEquals("/activity/tasks?task=91", captor.getValue().getActionUrl());
    }

    @Test
    void nudgeSeverityFlagsColdAsCriticalAndCoolingAsWarningWithinBackfillWindow() {
        assertEquals(
            NotificationReconciliationService.CRITICAL,
            NotificationReconciliationService.nudgeSeverity("cold", "steady", 20, 14, 90, false)
        );
        assertEquals(
            NotificationReconciliationService.WARNING,
            NotificationReconciliationService.nudgeSeverity("cool", "cooling", 20, 14, 90, false)
        );
        assertNull(
            NotificationReconciliationService.nudgeSeverity("cool", "cooling", 13, 14, 90, false)
        );
        assertNull(
            NotificationReconciliationService.nudgeSeverity("cold", "steady", 5, 14, 90, false)
        );
        assertNull(
            NotificationReconciliationService.nudgeSeverity("cold", "steady", null, 14, 90, false)
        );
        assertNull(
            NotificationReconciliationService.nudgeSeverity("warm", "rising", 90, 14, 90, false)
        );
        assertNull(
            NotificationReconciliationService.nudgeSeverity("cold", "steady", 200, 14, 90, false)
        );
        assertEquals(
            NotificationReconciliationService.CRITICAL,
            NotificationReconciliationService.nudgeSeverity("cold", "steady", 200, 14, 90, true)
        );
    }

    @Test
    void nudgeSeverityKeepsExistingNudgeWhileStillCoolButResolvesOnWarmUp() {
        assertEquals(
            NotificationReconciliationService.WARNING,
            NotificationReconciliationService.nudgeSeverity("cool", "steady", 30, 14, 90, true)
        );
        assertNull(
            NotificationReconciliationService.nudgeSeverity("cool", "steady", 30, 14, 90, false)
        );
        assertNull(
            NotificationReconciliationService.nudgeSeverity("warm", "steady", 30, 14, 90, true)
        );
    }

    @Test
    void reconciliationEmitsCoolingNudgeForStakeholderOnOpenDeal() {
        ReconciliationFixture fixture = ReconciliationFixture.withDispatcher();
        NotificationMapper notificationMapper = fixture.notificationMapper;
        NotificationDispatcher dispatcher = fixture.dispatcher;
        ScoringService scoringService = fixture.scoringService;

        RelationshipNudgeCandidate candidate = nudgeCandidate();
        when(notificationMapper.findRelationshipNudgeCandidates(7)).thenReturn(List.of(candidate));
        when(scoringService.scoreContacts(eq(7), any(Instant.class))).thenReturn(List.of(
            new RelationshipTemperatureDto(9, 28, "cool", "cooling", "2026-05-10 09:00:00",
                44, 1, null, null, "test-model", Instant.EPOCH)
        ));

        NotificationReconciliationService service = fixture.service();
        service.reconcileWorkspace(7, true);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(dispatcher).dispatch(captor.capture());
        Notification nudge = captor.getValue();
        assertEquals(NotificationReconciliationService.RELATIONSHIP_TYPE, nudge.getType());
        assertEquals("relationship", nudge.getCategory());
        assertEquals(NotificationReconciliationService.WARNING, nudge.getSeverity());
        assertEquals("person", nudge.getSourceType());
        assertEquals(Integer.valueOf(9), nudge.getSourceId());
        assertEquals("deal", nudge.getContextType());
        assertEquals(Integer.valueOf(5), nudge.getContextId());
        assertEquals("/records/deals/5", nudge.getActionUrl());
    }

    @Test
    void highValueThresholdRanksOpenDealValuesAndIgnoresTinyWorkspaces() {
        assertEquals(
            Double.POSITIVE_INFINITY,
            NotificationReconciliationService.highValueThreshold(List.of(10.0, 20.0, 30.0))
        );
        assertEquals(
            6.0,
            NotificationReconciliationService.highValueThreshold(
                List.of(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0))
        );
    }

    @Test
    void priorityReasonsFlagEachImportanceSignal() {
        LocalDate today = LocalDate.of(2026, 6, 23);

        RelationshipNudgeCandidate none = nudgeCandidate();
        none.setExpectedCloseDate("2026-08-01");
        assertTrue(NotificationReconciliationService
            .priorityReasons(none, Double.POSITIVE_INFINITY, today, 14).isEmpty());

        RelationshipNudgeCandidate soon = nudgeCandidate();
        soon.setExpectedCloseDate("2026-06-30");
        assertTrue(NotificationReconciliationService
            .priorityReasons(soon, Double.POSITIVE_INFINITY, today, 14).contains("closing_soon"));

        RelationshipNudgeCandidate valuable = nudgeCandidate();
        valuable.setExpectedCloseDate("2026-08-01");
        valuable.setDealValue(500.0);
        assertTrue(NotificationReconciliationService
            .priorityReasons(valuable, 100.0, today, 14).contains("high_value"));

        RelationshipNudgeCandidate late = nudgeCandidate();
        late.setExpectedCloseDate("2026-08-01");
        late.setStagePosition(4);
        late.setPipelineMaxPosition(5);
        assertTrue(NotificationReconciliationService
            .priorityReasons(late, Double.POSITIVE_INFINITY, today, 14).contains("late_stage"));

        RelationshipNudgeCandidate keyContact = nudgeCandidate();
        keyContact.setExpectedCloseDate("2026-08-01");
        keyContact.setPersonRole("Economic Buyer");
        assertTrue(NotificationReconciliationService
            .priorityReasons(keyContact, Double.POSITIVE_INFINITY, today, 14).contains("key_role"));
    }

    @Test
    void reconciliationFlagsPriorityInDataWhileSeverityStaysDecayState() {
        ReconciliationFixture fixture = ReconciliationFixture.withDispatcher();
        NotificationMapper notificationMapper = fixture.notificationMapper;
        NotificationDispatcher dispatcher = fixture.dispatcher;
        ScoringService scoringService = fixture.scoringService;

        RelationshipNudgeCandidate candidate = nudgeCandidate();
        candidate.setExpectedCloseDate("2026-06-30");
        when(notificationMapper.findRelationshipNudgeCandidates(7)).thenReturn(List.of(candidate));
        when(scoringService.scoreContacts(eq(7), any(Instant.class))).thenReturn(List.of(
            new RelationshipTemperatureDto(9, 28, "cool", "cooling", "2026-05-10 09:00:00",
                44, 1, null, null, "test-model", Instant.EPOCH)
        ));

        NotificationReconciliationService service = fixture.service();
        service.reconcileWorkspace(7, true);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(dispatcher).dispatch(captor.capture());
        Notification nudge = captor.getValue();
        assertEquals(NotificationReconciliationService.WARNING, nudge.getSeverity());
        assertTrue(nudge.getData().contains("closing_soon"));
    }

    @Test
    void reconciliationSuppressesFirstNudgeForLongDormantContact() {
        ReconciliationFixture fixture = ReconciliationFixture.withDispatcher();
        NotificationMapper notificationMapper = fixture.notificationMapper;
        NotificationDispatcher dispatcher = fixture.dispatcher;
        ScoringService scoringService = fixture.scoringService;

        when(notificationMapper.findRelationshipNudgeCandidates(7)).thenReturn(List.of(nudgeCandidate()));
        when(scoringService.scoreContacts(eq(7), any(Instant.class))).thenReturn(List.of(
            new RelationshipTemperatureDto(9, 4, "cold", "steady", "2025-06-01 09:00:00",
                365, 0, null, null, "test-model", Instant.EPOCH)
        ));

        NotificationReconciliationService service = fixture.service();
        service.reconcileWorkspace(7, true);

        verify(dispatcher, never()).dispatch(any());
    }

    @Test
    void reconciliationSkipsCoolingNudgeWhenRecipientOptedOut() {
        ReconciliationFixture fixture = ReconciliationFixture.withDispatcher();
        NotificationMapper notificationMapper = fixture.notificationMapper;
        PreferenceMapper preferenceMapper = fixture.preferenceMapper;
        NotificationDispatcher dispatcher = fixture.dispatcher;
        ScoringService scoringService = fixture.scoringService;

        NotificationPreference optOut = new NotificationPreference();
        optOut.setUserId(42);
        optOut.setType(NotificationReconciliationService.RELATIONSHIP_TYPE);
        optOut.setChannel("in_app");
        optOut.setEnabled(false);

        when(preferenceMapper.findByWorkspaceAndChannel(7, "in_app")).thenReturn(List.of(optOut));
        when(notificationMapper.findRelationshipNudgeCandidates(7)).thenReturn(List.of(nudgeCandidate()));
        when(scoringService.scoreContacts(eq(7), any(Instant.class))).thenReturn(List.of(
            new RelationshipTemperatureDto(9, 28, "cool", "cooling", "2026-05-10 09:00:00",
                44, 1, null, null, "test-model", Instant.EPOCH)
        ));

        NotificationReconciliationService service = fixture.service();
        service.reconcileWorkspace(7, true);

        verify(dispatcher, never()).dispatch(any());
    }

    @Test
    void reconciliationResolvesCoolingNudgeWhenContactWarmsUp() {
        ReconciliationFixture fixture = ReconciliationFixture.withDispatcher();
        NotificationMapper notificationMapper = fixture.notificationMapper;
        NotificationDispatcher dispatcher = fixture.dispatcher;
        ScoringService scoringService = fixture.scoringService;

        Notification existing = reminderNotification(
            101, NotificationReconciliationService.RELATIONSHIP_TYPE, "relationship.cooling:5:9");

        when(notificationMapper.findWorkspaceReminderNotifications(7)).thenReturn(List.of(existing));
        when(notificationMapper.findRelationshipNudgeCandidates(7)).thenReturn(List.of(nudgeCandidate()));
        when(scoringService.scoreContacts(eq(7), any(Instant.class))).thenReturn(List.of(
            new RelationshipTemperatureDto(9, 72, "hot", "rising", "2026-06-22 09:00:00",
                1, 6, null, null, "test-model", Instant.EPOCH)
        ));

        NotificationReconciliationService service = fixture.service();
        service.reconcileWorkspace(7, true);

        verify(dispatcher, never()).dispatch(any());
        verify(notificationMapper).resolveReminder(7, 42, 101, "2026-06-23 15:30:00");
    }

    @Test
    void reconciliationSkipsRelationshipNudgesWhenNotRequested() {
        ReconciliationFixture fixture = ReconciliationFixture.withDispatcher();
        NotificationMapper notificationMapper = fixture.notificationMapper;
        NotificationDispatcher dispatcher = fixture.dispatcher;
        ScoringService scoringService = fixture.scoringService;

        NotificationReconciliationService service = fixture.service();
        service.reconcileWorkspace(7, false);

        verify(notificationMapper, never()).findRelationshipNudgeCandidates(anyInt());
        Mockito.verifyNoInteractions(scoringService);
        verify(dispatcher, never()).dispatch(any());
    }

    private static final class ReconciliationFixture {
        private final NotificationMapper notificationMapper = Mockito.mock(NotificationMapper.class);
        private final PreferenceMapper preferenceMapper = Mockito.mock(PreferenceMapper.class);
        private final NotificationDispatcher dispatcher = Mockito.mock(NotificationDispatcher.class);
        private final ScoringService scoringService = Mockito.mock(ScoringService.class);
        private final IntroductionService introductionService = Mockito.mock(IntroductionService.class);
        private final DealRiskService dealRiskService = noRiskService();
        private final NotificationProperties properties = new NotificationProperties();
        private final Clock clock = Clock.fixed(Instant.parse("2026-06-23T15:30:00Z"), ZoneOffset.UTC);
        private final NotificationDelivery notificationDelivery;

        private ReconciliationFixture(boolean mockedDelivery) {
            notificationDelivery = mockedDelivery
                ? Mockito.mock(NotificationDelivery.class)
                : wrap(dispatcher, notificationMapper, preferenceMapper);
        }

        private static ReconciliationFixture withDispatcher() {
            return new ReconciliationFixture(false);
        }

        private static ReconciliationFixture mockedDelivery() {
            return new ReconciliationFixture(true);
        }

        private NotificationReconciliationService service() {
            return service(clock);
        }

        private NotificationReconciliationService service(Clock evaluationClock) {
            return new NotificationReconciliationService(
                notificationMapper,
                Mockito.mock(DuplicateDecisionLockService.class),
                preferenceMapper,
                notificationDelivery,
                stateVersions(notificationMapper),
                properties,
                scoringService,
                introductionService,
                dealRiskService,
                evaluationClock,
                new ObjectMapper());
        }
    }

    private static RelationshipNudgeCandidate nudgeCandidate() {
        RelationshipNudgeCandidate candidate = new RelationshipNudgeCandidate();
        candidate.setWorkspaceId(7);
        candidate.setDealId(5);
        candidate.setDealLabel("Acme renewal");
        candidate.setExpectedCloseDate("2026-07-15");
        candidate.setPersonId(9);
        candidate.setPersonLabel("Jordan Vega");
        candidate.setRecipientId(42);
        return candidate;
    }

    private static NotificationDelivery wrap(
        NotificationDispatcher dispatcher,
        NotificationMapper notificationMapper,
        PreferenceMapper preferenceMapper
    ) {
        Mockito.lenient().when(dispatcher.channel()).thenReturn("in_app");
        NotificationQuietHoursControlAccess quietHoursControlAccess =
            Mockito.mock(NotificationQuietHoursControlAccess.class);
        Mockito.lenient().when(quietHoursControlAccess.evaluateForUser(anyInt(), Mockito.any(Instant.class)))
            .thenReturn(new NotificationQuietHoursEvaluator.Evaluation(false, null));
        return new NotificationDelivery(
            List.of(dispatcher), notificationMapper, preferenceMapper,
            Mockito.mock(NotificationPushPublisher.class), stateVersions(notificationMapper),
            quietHoursControlAccess,
            Mockito.mock(ooo.klae.connex.backend.notifications.NotificationQuietHoursBypassPolicy.class),
            Clock.systemUTC());
    }

    /** A deal-risk service that flags nothing, so the deal-risk pass is a no-op in unrelated tests. */
    private static DealRiskService noRiskService() {
        DealRiskService mock = Mockito.mock(DealRiskService.class);
        when(mock.assessWorkspaceNotificationStates(anyInt(), any(), any()))
            .thenReturn(List.of());
        return mock;
    }

    private static DealRiskService.NotificationRiskState riskState(DealRiskDto risk) {
        return riskState(
            risk,
            Integer.toHexString(risk.getDealId()).repeat(64).substring(0, 64));
    }

    private static DealRiskService.NotificationRiskState riskState(
            DealRiskDto risk,
            String sourceStateHash) {
        return new DealRiskService.NotificationRiskState(
            risk,
            sourceStateHash);
    }

    private static NotificationStateVersionService stateVersions(NotificationMapper notificationMapper) {
        return new NotificationStateVersionService(
            notificationMapper, Mockito.mock(NotificationPushPublisher.class));
    }

    private static OpenDealRecipient recipient(int dealId, String dealLabel, int recipientId) {
        OpenDealRecipient recipient = new OpenDealRecipient();
        recipient.setDealId(dealId);
        recipient.setDealLabel(dealLabel);
        recipient.setRecipientId(recipientId);
        return recipient;
    }

    @Test
    void reconciliationEmitsDealRiskNotificationForHighButSkipsLow() {
        ReconciliationFixture fixture = ReconciliationFixture.withDispatcher();
        NotificationMapper notificationMapper = fixture.notificationMapper;
        NotificationDispatcher dispatcher = fixture.dispatcher;
        DealRiskService dealRiskService = fixture.dealRiskService;

        DealRiskDto high = new DealRiskDto(101, BigDecimal.ZERO, null, "high", 60,
            List.of(new DealRiskFactor("close_overdue", "high", Map.of("daysOverdue", 22L))),
            "2026-06-23 15:30:00");
        DealRiskDto low = new DealRiskDto(102, BigDecimal.ZERO, null, "low", 10,
            List.of(new DealRiskFactor("no_stakeholders", "low", Map.of())),
            "2026-06-23 15:30:00");
        when(dealRiskService.assessWorkspaceNotificationStates(eq(7), any(), any()))
            .thenReturn(List.of(riskState(high), riskState(low)));
        when(notificationMapper.findOpenDealRecipients(7)).thenReturn(List.of(
            recipient(101, "Acme renewal", 42),
            recipient(102, "Beta deal", 42)));

        NotificationReconciliationService service = fixture.service();
        service.reconcileWorkspace(7, true);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(dispatcher).dispatch(captor.capture());
        Notification notification = captor.getValue();
        assertEquals(NotificationReconciliationService.DEAL_RISK_TYPE, notification.getType());
        assertEquals(NotificationReconciliationService.CRITICAL, notification.getSeverity());
        assertEquals(101, notification.getSourceId());
        assertEquals("deal.risk:101", notification.getDedupeKey());
        assertEquals("/records/deals/101", notification.getActionUrl());
        assertTrue(notification.getData().contains("close_overdue"));
    }

    @Test
    void dealRiskPassIsSkippedWhenDisabled() {
        ReconciliationFixture fixture = ReconciliationFixture.withDispatcher();
        DealRiskService dealRiskService = fixture.dealRiskService;
        NotificationProperties properties = fixture.properties;
        properties.setDealRiskEnabled(false);

        NotificationReconciliationService service = fixture.service();
        service.reconcileWorkspace(7, true);

        verify(dealRiskService, never()).assessWorkspace(anyInt());
        verify(dealRiskService, never())
            .assessWorkspaceNotificationStates(anyInt(), any(), any());
    }

    @Test
    void reconciliationEmitsDealRiskToOwnerAndCollaboratorAtWarning() {
        ReconciliationFixture fixture = ReconciliationFixture.withDispatcher();
        NotificationMapper notificationMapper = fixture.notificationMapper;
        NotificationDispatcher dispatcher = fixture.dispatcher;
        DealRiskService dealRiskService = fixture.dealRiskService;

        DealRiskDto medium = new DealRiskDto(101, BigDecimal.ZERO, null, "medium", 25,
            List.of(new DealRiskFactor("stalled", "medium", Map.of("daysSinceTouch", 40))),
            "2026-06-23 15:30:00");
        when(dealRiskService.assessWorkspaceNotificationStates(eq(7), any(), any()))
            .thenReturn(List.of(riskState(medium)));
        when(notificationMapper.findOpenDealRecipients(7)).thenReturn(List.of(
            recipient(101, "Acme renewal", 42),
            recipient(101, "Acme renewal", 43)));

        NotificationReconciliationService service = fixture.service();
        service.reconcileWorkspace(7, true);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(dispatcher, Mockito.times(2)).dispatch(captor.capture());
        assertTrue(captor.getAllValues().stream()
            .allMatch(n -> NotificationReconciliationService.WARNING.equals(n.getSeverity())));
        assertEquals(List.of(42, 43),
            captor.getAllValues().stream().map(Notification::getRecipientId).sorted().toList());
    }

    @Test
    void reconciliationSkipsDealRiskWhenRecipientOptedOut() {
        ReconciliationFixture fixture = ReconciliationFixture.withDispatcher();
        NotificationMapper notificationMapper = fixture.notificationMapper;
        PreferenceMapper preferenceMapper = fixture.preferenceMapper;
        NotificationDispatcher dispatcher = fixture.dispatcher;
        DealRiskService dealRiskService = fixture.dealRiskService;

        NotificationPreference optOut = new NotificationPreference();
        optOut.setUserId(42);
        optOut.setType(NotificationReconciliationService.DEAL_RISK_TYPE);
        optOut.setChannel("in_app");
        optOut.setEnabled(false);

        when(preferenceMapper.findByWorkspaceAndChannel(7, "in_app")).thenReturn(List.of(optOut));
        when(dealRiskService.assessWorkspaceNotificationStates(eq(7), any(), any()))
            .thenReturn(List.of(riskState(new DealRiskDto(
                101,
                BigDecimal.ZERO,
                null,
                "high",
                60,
                List.of(new DealRiskFactor(
                    "close_overdue",
                    "high",
                    Map.of("daysOverdue", 22L))),
                "2026-06-23 15:30:00"))));
        when(notificationMapper.findOpenDealRecipients(7)).thenReturn(List.of(recipient(101, "Acme renewal", 42)));

        NotificationReconciliationService service = fixture.service();
        service.reconcileWorkspace(7, true);

        verify(dispatcher, never()).dispatch(any());
    }

    @Test
    void reconciliationResolvesDealRiskWhenDealNoLongerAtRisk() {
        ReconciliationFixture fixture = ReconciliationFixture.withDispatcher();
        NotificationMapper notificationMapper = fixture.notificationMapper;
        NotificationDispatcher dispatcher = fixture.dispatcher;
        DealRiskService dealRiskService = fixture.dealRiskService;

        Notification existing = reminderNotification(
            202, NotificationReconciliationService.DEAL_RISK_TYPE, "deal.risk:101");

        when(notificationMapper.findWorkspaceReminderNotifications(7)).thenReturn(List.of(existing));
        when(dealRiskService.assessWorkspaceNotificationStates(eq(7), any(), any()))
            .thenReturn(List.of());
        when(notificationMapper.resolveReminder(
            7, 42, 202, "2026-06-23 15:30:00")).thenReturn(1);

        NotificationReconciliationService service = fixture.service();
        service.reconcileWorkspace(7, true);

        verify(dispatcher, never()).dispatch(any());
        verify(notificationMapper).resolveReminder(7, 42, 202, "2026-06-23 15:30:00");
        verify(notificationMapper).bumpStateVersions(List.of(42));
    }

    @Test
    void purgeMarksOnlyRecipientsWhoseRowsAreDeleted() {
        ReconciliationFixture fixture = ReconciliationFixture.withDispatcher();
        NotificationMapper notificationMapper = fixture.notificationMapper;
        when(notificationMapper.findPurgeRecipientIds(eq(7), any())).thenReturn(List.of(9, 42));
        when(notificationMapper.purgeWorkspaceReminderHistory(eq(7), any())).thenReturn(3);
        NotificationReconciliationService service = fixture.service();

        assertEquals(3, service.purgeWorkspace(7));

        verify(notificationMapper).bumpStateVersions(List.of(9));
        verify(notificationMapper).bumpStateVersions(List.of(42));
    }

    @Test
    void reconciliationEmitsIntroOpportunityForTopSuggestionToEachMember() {
        ReconciliationFixture fixture = ReconciliationFixture.withDispatcher();
        NotificationMapper notificationMapper = fixture.notificationMapper;
        NotificationDispatcher dispatcher = fixture.dispatcher;
        IntroductionService introductionService = fixture.introductionService;

        when(notificationMapper.findWorkspaceRecipientIds(7)).thenReturn(List.of(42));
        when(introductionService.computeSuggestions(eq(7), anyInt(), any())).thenReturn(List.of(introSuggestion()));

        NotificationReconciliationService service = fixture.service();
        service.reconcileWorkspace(7, true);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(dispatcher).dispatch(captor.capture());
        Notification opportunity = captor.getValue();
        assertEquals(NotificationReconciliationService.INTRO_OPPORTUNITY_TYPE, opportunity.getType());
        assertEquals("relationship", opportunity.getCategory());
        assertEquals("info", opportunity.getSeverity());
        assertEquals("person", opportunity.getSourceType());
        assertEquals(Integer.valueOf(3), opportunity.getSourceId());
        assertEquals("person", opportunity.getContextType());
        assertEquals(Integer.valueOf(8), opportunity.getContextId());
        assertEquals("/intelligence/introductions", opportunity.getActionUrl());
        assertEquals(42, opportunity.getRecipientId());
        assertEquals("relationship.intro_opportunity:3:8", opportunity.getDedupeKey());
    }

    @Test
    void reconciliationSkipsIntroOpportunitiesWhenDisabled() {
        ReconciliationFixture fixture = ReconciliationFixture.withDispatcher();
        NotificationDispatcher dispatcher = fixture.dispatcher;
        IntroductionService introductionService = fixture.introductionService;

        NotificationProperties properties = fixture.properties;
        properties.setIntroOpportunitiesEnabled(false);

        NotificationReconciliationService service = fixture.service();
        service.reconcileWorkspace(7, true);

        verify(introductionService, never()).computeSuggestions(anyInt(), anyInt(), any());
        verify(dispatcher, never()).dispatch(any());
    }

    @Test
    void reconciliationSkipsIntroOpportunitiesWhenNotRequested() {
        ReconciliationFixture fixture = ReconciliationFixture.withDispatcher();
        NotificationDispatcher dispatcher = fixture.dispatcher;
        IntroductionService introductionService = fixture.introductionService;

        NotificationReconciliationService service = fixture.service();
        service.reconcileWorkspace(7, false);

        verify(introductionService, never()).computeSuggestions(anyInt(), anyInt(), any());
        verify(dispatcher, never()).dispatch(any());
    }

    @Test
    void reconciliationDoesNotResolveIntroOpportunityOnPerMutationPass() {
        ReconciliationFixture fixture = ReconciliationFixture.withDispatcher();
        NotificationMapper notificationMapper = fixture.notificationMapper;
        IntroductionService introductionService = fixture.introductionService;

        Notification existing = reminderNotification(
            55, NotificationReconciliationService.INTRO_OPPORTUNITY_TYPE, "relationship.intro_opportunity:3:8");
        when(notificationMapper.findWorkspaceReminderNotifications(7)).thenReturn(List.of(existing));

        NotificationReconciliationService service = fixture.service();
        service.reconcileWorkspace(7, false);

        verify(notificationMapper, never()).resolveReminder(anyInt(), anyInt(), anyInt(), any());
        verify(introductionService, never()).computeSuggestions(anyInt(), anyInt(), any());
    }

    @Test
    void scoringFailureStillDeliversTaskRemindersAndPreservesRelationshipNotifications() {
        ReconciliationFixture fixture = ReconciliationFixture.withDispatcher();
        NotificationMapper notificationMapper = fixture.notificationMapper;
        NotificationDispatcher dispatcher = fixture.dispatcher;
        ScoringService scoringService = fixture.scoringService;
        IntroductionService introductionService = fixture.introductionService;

        TaskReminderCandidate task = new TaskReminderCandidate();
        task.setWorkspaceId(7);
        task.setTaskId(91);
        task.setTaskLabel("Send proposal");
        task.setDueDate("2026-06-23");
        task.setRecipientId(42);

        Notification nudge = reminderNotification(
            101, NotificationReconciliationService.RELATIONSHIP_TYPE, "relationship.cooling:5:9");
        Notification intro = reminderNotification(
            55, NotificationReconciliationService.INTRO_OPPORTUNITY_TYPE, "relationship.intro_opportunity:3:8");

        when(notificationMapper.findTaskReminderCandidates(7)).thenReturn(List.of(task));
        when(notificationMapper.findWorkspaceReminderNotifications(7)).thenReturn(List.of(nudge, intro));
        when(scoringService.scoreContacts(eq(7), any(Instant.class)))
            .thenThrow(new IllegalStateException("scoring down"));
        DealRiskService dealRiskService = fixture.dealRiskService;

        NotificationReconciliationService service = fixture.service();
        service.reconcileWorkspace(7, true);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(dispatcher).dispatch(captor.capture());
        assertEquals(NotificationReconciliationService.TASK_TYPE, captor.getValue().getType());
        verify(introductionService, never()).computeSuggestions(anyInt(), anyInt(), any());
        verify(dealRiskService, never())
            .assessWorkspaceNotificationStates(anyInt(), any(), any());
        verify(notificationMapper, never()).resolveReminder(anyInt(), anyInt(), anyInt(), any());
    }

    @Test
    void dealRiskPassFailureDoesNotResolveExistingDealRiskNotifications() {
        ReconciliationFixture fixture = ReconciliationFixture.withDispatcher();
        NotificationMapper notificationMapper = fixture.notificationMapper;
        NotificationDispatcher dispatcher = fixture.dispatcher;
        DealRiskService dealRiskService = fixture.dealRiskService;

        Notification existing = reminderNotification(
            202, NotificationReconciliationService.DEAL_RISK_TYPE, "deal.risk:101");
        when(notificationMapper.findWorkspaceReminderNotifications(7)).thenReturn(List.of(existing));
        when(dealRiskService.assessWorkspaceNotificationStates(eq(7), any(), any()))
            .thenThrow(new IllegalStateException("risk engine down"));

        NotificationReconciliationService service = fixture.service();
        service.reconcileWorkspace(7, true);

        verify(dispatcher, never()).dispatch(any());
        verify(notificationMapper, never()).resolveReminder(anyInt(), anyInt(), anyInt(), any());
    }

    @Test
    void disabledPassesStillResolveTheirStaleNotifications() {
        ReconciliationFixture fixture = ReconciliationFixture.withDispatcher();
        NotificationMapper notificationMapper = fixture.notificationMapper;
        DealRiskService dealRiskService = fixture.dealRiskService;

        NotificationProperties properties = fixture.properties;
        properties.setIntroOpportunitiesEnabled(false);
        properties.setDealRiskEnabled(false);

        Notification intro = reminderNotification(
            55, NotificationReconciliationService.INTRO_OPPORTUNITY_TYPE, "relationship.intro_opportunity:3:8");
        Notification risk = reminderNotification(
            202, NotificationReconciliationService.DEAL_RISK_TYPE, "deal.risk:101");
        when(notificationMapper.findWorkspaceReminderNotifications(7)).thenReturn(List.of(intro, risk));

        NotificationReconciliationService service = fixture.service();
        service.reconcileWorkspace(7, true);

        verify(dealRiskService, never())
            .assessWorkspaceNotificationStates(anyInt(), any(), any());
        verify(notificationMapper).resolveReminder(7, 42, 55, "2026-06-23 15:30:00");
        verify(notificationMapper).resolveReminder(7, 42, 202, "2026-06-23 15:30:00");
    }

    @Test
    void midPassFailureDeliversNothingFromThatPassAndPreservesItsNotifications() {
        ReconciliationFixture fixture = ReconciliationFixture.withDispatcher();
        NotificationMapper notificationMapper = fixture.notificationMapper;
        NotificationDispatcher dispatcher = fixture.dispatcher;
        DealRiskService dealRiskService = fixture.dealRiskService;

        TaskReminderCandidate task = new TaskReminderCandidate();
        task.setWorkspaceId(7);
        task.setTaskId(91);
        task.setTaskLabel("Send proposal");
        task.setDueDate("2026-06-23");
        task.setRecipientId(42);

        Notification existing = reminderNotification(
            202, NotificationReconciliationService.DEAL_RISK_TYPE, "deal.risk:101");
        when(notificationMapper.findTaskReminderCandidates(7)).thenReturn(List.of(task));
        when(notificationMapper.findWorkspaceReminderNotifications(7)).thenReturn(List.of(existing));
        when(dealRiskService.assessWorkspaceNotificationStates(eq(7), any(), any()))
            .thenReturn(List.of(riskState(new DealRiskDto(
                101,
                BigDecimal.ZERO,
                null,
                "high",
                60,
                List.of(new DealRiskFactor(
                    "close_overdue",
                    "high",
                    Map.of("daysOverdue", 22L))),
                "2026-06-23 15:30:00"))));
        when(notificationMapper.findOpenDealRecipients(7)).thenReturn(new AbstractList<>() {
            @Override
            public OpenDealRecipient get(int index) {
                if (index == 0) {
                    return recipient(101, "Acme renewal", 42);
                }
                throw new IllegalStateException("recipient row read failed");
            }

            @Override
            public int size() {
                return 2;
            }
        });

        NotificationReconciliationService service = fixture.service();
        service.reconcileWorkspace(7, true);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(dispatcher).dispatch(captor.capture());
        assertEquals(NotificationReconciliationService.TASK_TYPE, captor.getValue().getType());
        verify(notificationMapper, never()).resolveReminder(anyInt(), anyInt(), anyInt(), any());
    }

    @Test
    void taskPassFailureStillDeliversDealRemindersAndPreservesTaskNotifications() {
        ReconciliationFixture fixture = ReconciliationFixture.withDispatcher();
        NotificationMapper notificationMapper = fixture.notificationMapper;
        NotificationDispatcher dispatcher = fixture.dispatcher;

        DealReminderCandidate deal = new DealReminderCandidate();
        deal.setWorkspaceId(7);
        deal.setDealId(5);
        deal.setDealLabel("Acme renewal");
        deal.setExpectedCloseDate("2026-06-23");
        deal.setRecipientId(42);

        Notification existing = reminderNotification(
            88, NotificationReconciliationService.TASK_TYPE, "task.due:91");
        when(notificationMapper.findTaskReminderCandidates(7))
            .thenThrow(new IllegalStateException("task candidates read failed"));
        when(notificationMapper.findDealReminderCandidates(7)).thenReturn(List.of(deal));
        when(notificationMapper.findWorkspaceReminderNotifications(7)).thenReturn(List.of(existing));

        NotificationReconciliationService service = fixture.service();
        service.reconcileWorkspace(7, false);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(dispatcher).dispatch(captor.capture());
        assertEquals(NotificationReconciliationService.DEAL_TYPE, captor.getValue().getType());
        verify(notificationMapper, never()).resolveReminder(anyInt(), anyInt(), anyInt(), any());
    }

    @Test
    void matchingHistoricalBaselineSuppressesDeliveryAndPreservesExistingNotificationState() {
        ReconciliationFixture fixture = ReconciliationFixture.mockedDelivery();
        Clock clock = fixture.clock;
        NotificationMapper notificationMapper = fixture.notificationMapper;
        NotificationDelivery notificationDelivery = fixture.notificationDelivery;
        Notification existing = reminderNotification(
            88, NotificationReconciliationService.TASK_TYPE, "task.due:91");
        existing.setSeverity("warning");
        existing.setReadAt("2026-06-20 10:00:00");
        existing.setDismissedAt("2026-06-21 10:00:00");
        when(notificationMapper.findWorkspaceReminderNotifications(7))
            .thenReturn(List.of(existing));
        when(notificationMapper.findTaskReminderCandidates(7))
            .thenReturn(List.of(taskCandidate()));
        ScoringService scoringService = fixture.scoringService;
        when(scoringService.scoreContactsExcludingHistoryImports(
                eq(7), eq(clock.instant()), any(), any(), any()))
            .thenReturn(List.of());
        NotificationReconciliationService service = fixture.service();
        NotificationReconciliationService.HistoricalExpectationSnapshot snapshot =
            service.historicalExpectationSnapshot(7, clock.instant());
        HistoricalNotificationBaseline baseline =
            baseline(NotificationReconciliationService.TASK_TYPE, "warning", "task.due:91");
        baseline.setSourceStateHash(
            snapshot.expectations().values().iterator().next().sourceStateHash());
        when(notificationMapper.findHistoricalNotificationBaselines(7))
            .thenReturn(List.of(baseline));
        service.reconcileWorkspace(7, false);

        verify(notificationDelivery, never()).deliver(any());
        verify(notificationMapper, never()).deleteHistoricalNotificationBaselines(
            anyInt(), anyList());
        verify(notificationMapper, never()).resolveReminder(
            anyInt(), anyInt(), anyInt(), anyString());
        assertEquals("2026-06-20 10:00:00", existing.getReadAt());
        assertEquals("2026-06-21 10:00:00", existing.getDismissedAt());
    }

    @Test
    void clockOnlyTaskSeverityChangeKeepsHistoricalBaselineSuppressed() {
        ReconciliationFixture fixture = ReconciliationFixture.mockedDelivery();
        NotificationMapper notificationMapper = fixture.notificationMapper;
        NotificationDelivery notificationDelivery = fixture.notificationDelivery;
        Clock dueSoon = Clock.fixed(
            Instant.parse("2026-06-23T15:30:00Z"), ZoneOffset.UTC);
        Clock overdue = Clock.fixed(
            Instant.parse("2026-06-24T15:30:00Z"), ZoneOffset.UTC);
        when(notificationMapper.findTaskReminderCandidates(7))
            .thenReturn(List.of(taskCandidate()));
        ScoringService scoringService = fixture.scoringService;
        when(scoringService.scoreContactsExcludingHistoryImports(
                eq(7), any(Instant.class), any(), any(), any()))
            .thenReturn(List.of());
        NotificationReconciliationService dueSoonService = fixture.service(dueSoon);
        NotificationReconciliationService overdueService = fixture.service(overdue);
        NotificationReconciliationService.HistoricalExpectation dueSoonExpectation =
            dueSoonService.historicalExpectationSnapshot(7, dueSoon.instant())
                .expectations().values().iterator().next();
        NotificationReconciliationService.HistoricalExpectation overdueExpectation =
            overdueService.historicalExpectationSnapshot(7, overdue.instant())
                .expectations().values().iterator().next();
        HistoricalNotificationBaseline baseline =
            baseline(NotificationReconciliationService.TASK_TYPE, "warning", "task.due:91");
        baseline.setSourceStateHash(dueSoonExpectation.sourceStateHash());
        when(notificationMapper.findHistoricalNotificationBaselines(7))
            .thenReturn(List.of(baseline));

        overdueService.reconcileWorkspace(7, false);

        assertEquals("warning", dueSoonExpectation.severity());
        assertEquals("critical", overdueExpectation.severity());
        assertEquals(
            dueSoonExpectation.sourceStateHash(),
            overdueExpectation.sourceStateHash());
        verify(notificationDelivery, never()).deliver(any());
        verify(notificationMapper, never()).deleteHistoricalNotificationBaselines(
            anyInt(), anyList());
    }

    @Test
    void clockOnlyRelationshipPriorityChangeKeepsHistoricalSourceStateStable() {
        ReconciliationFixture fixture = ReconciliationFixture.mockedDelivery();
        NotificationMapper notificationMapper = fixture.notificationMapper;
        RelationshipNudgeCandidate candidate = nudgeCandidate();
        candidate.setExpectedCloseDate("2026-07-15");
        when(notificationMapper.findRelationshipNudgeCandidates(7))
            .thenReturn(List.of(candidate));
        RelationshipTemperatureDto temperature = new RelationshipTemperatureDto(
            9,
            28,
            "cool",
            "cooling",
            "2026-05-10 09:00:00",
            44,
            1,
            null,
            null,
            "test-model",
            Instant.EPOCH);
        ScoringService scoringService = fixture.scoringService;
        when(scoringService.scoreContactsExcludingHistoryImports(
                eq(7), any(Instant.class), any(), any(), any()))
            .thenReturn(List.of(temperature));
        Clock beforeClosingSoon = Clock.fixed(
            Instant.parse("2026-06-30T15:30:00Z"), ZoneOffset.UTC);
        Clock closingSoon = Clock.fixed(
            Instant.parse("2026-07-01T15:30:00Z"), ZoneOffset.UTC);

        NotificationReconciliationService.HistoricalExpectation before =
            fixture.service(beforeClosingSoon)
                .historicalExpectationSnapshot(7, beforeClosingSoon.instant())
                .expectations().values().iterator().next();
        NotificationReconciliationService.HistoricalExpectation after =
            fixture.service(closingSoon)
                .historicalExpectationSnapshot(7, closingSoon.instant())
                .expectations().values().iterator().next();

        assertEquals(before.sourceStateHash(), after.sourceStateHash());
    }

    @Test
    void clockOnlyDealRiskBandChangeKeepsHistoricalBaselineSuppressed() {
        ReconciliationFixture fixture = ReconciliationFixture.mockedDelivery();
        NotificationMapper notificationMapper = fixture.notificationMapper;
        NotificationDelivery notificationDelivery = fixture.notificationDelivery;
        DealRiskService dealRiskService = fixture.dealRiskService;
        String sourceStateHash = "d".repeat(64);
        DealRiskDto high = new DealRiskDto(
            101,
            BigDecimal.ZERO,
            null,
            "high",
            60,
            List.of(new DealRiskFactor(
                "close_overdue",
                "high",
                Map.of("daysOverdue", 22L))),
            "2026-06-23 15:30:00");
        DealRiskDto medium = new DealRiskDto(
            101,
            BigDecimal.ZERO,
            null,
            "medium",
            25,
            List.of(new DealRiskFactor(
                "stalled",
                "medium",
                Map.of("daysSinceTouch", 30))),
            "2026-06-24 15:30:00");
        when(dealRiskService.assessWorkspaceNotificationStates(eq(7), any(), any()))
            .thenReturn(
                List.of(riskState(high, sourceStateHash)),
                List.of(riskState(medium, sourceStateHash)));
        when(notificationMapper.findOpenDealRecipients(7))
            .thenReturn(List.of(recipient(101, "Acme renewal", 42)));
        ScoringService scoringService = fixture.scoringService;
        when(scoringService.scoreContactsExcludingHistoryImports(
                eq(7), any(Instant.class), any(), any(), any()))
            .thenReturn(List.of());
        when(scoringService.scoreContacts(eq(7), any(Instant.class)))
            .thenReturn(List.of());
        Clock clock = Clock.fixed(
            Instant.parse("2026-06-24T15:30:00Z"), ZoneOffset.UTC);
        NotificationReconciliationService service =
            fixture.service(clock);
        NotificationReconciliationService.HistoricalExpectation initial =
            service.historicalExpectationSnapshot(7, clock.instant())
                .expectations().values().iterator().next();
        HistoricalNotificationBaseline baseline = baseline(
            NotificationReconciliationService.DEAL_RISK_TYPE,
            "critical",
            "deal.risk:101");
        baseline.setSourceStateHash(initial.sourceStateHash());
        when(notificationMapper.findHistoricalNotificationBaselines(7))
            .thenReturn(List.of(baseline));

        service.reconcileWorkspace(7, true);

        verify(notificationDelivery, never()).deliver(any());
        verify(notificationMapper, never()).deleteHistoricalNotificationBaselines(
            anyInt(), anyList());
    }

    @Test
    void changedHistoricalBaselineIsDeletedBeforeNormalDelivery() {
        ReconciliationFixture fixture = ReconciliationFixture.mockedDelivery();
        NotificationMapper notificationMapper = fixture.notificationMapper;
        NotificationDelivery notificationDelivery = fixture.notificationDelivery;
        HistoricalNotificationBaseline baseline =
            baseline(NotificationReconciliationService.TASK_TYPE, "info", "task.due:91");
        when(notificationMapper.findHistoricalNotificationBaselines(7))
            .thenReturn(List.of(baseline));
        when(notificationMapper.findTaskReminderCandidates(7))
            .thenReturn(List.of(taskCandidate()));
        when(notificationMapper.deleteHistoricalNotificationBaselines(
                7, List.of(baseline)))
            .thenReturn(1);

        NotificationReconciliationService service = fixture.service();
        service.reconcileWorkspace(7, false);

        org.mockito.InOrder order = Mockito.inOrder(notificationMapper, notificationDelivery);
        order.verify(notificationMapper).deleteHistoricalNotificationBaselines(
            7, List.of(baseline));
        order.verify(notificationDelivery).deliver(any(Notification.class));
    }

    @Test
    void concurrentBaselineReplacementFailsClosedWithoutDelivery() {
        ReconciliationFixture fixture = ReconciliationFixture.mockedDelivery();
        NotificationMapper notificationMapper = fixture.notificationMapper;
        NotificationDelivery notificationDelivery = fixture.notificationDelivery;
        HistoricalNotificationBaseline baseline =
            baseline(NotificationReconciliationService.TASK_TYPE, "info", "task.due:91");
        when(notificationMapper.findHistoricalNotificationBaselines(7))
            .thenReturn(List.of(baseline));
        when(notificationMapper.findTaskReminderCandidates(7))
            .thenReturn(List.of(taskCandidate()));
        when(notificationMapper.deleteHistoricalNotificationBaselines(
                7, List.of(baseline)))
            .thenReturn(0);

        NotificationReconciliationService service = fixture.service();
        service.reconcileWorkspace(7, false);

        verify(notificationDelivery, never()).deliver(any());
    }

    @Test
    void clearedHistoricalConditionDeletesBaselineAndLaterRecurrenceDelivers() {
        ReconciliationFixture fixture = ReconciliationFixture.mockedDelivery();
        NotificationMapper notificationMapper = fixture.notificationMapper;
        NotificationDelivery notificationDelivery = fixture.notificationDelivery;
        HistoricalNotificationBaseline baseline =
            baseline(NotificationReconciliationService.TASK_TYPE, "warning", "task.due:91");
        when(notificationMapper.findHistoricalNotificationBaselines(7))
            .thenReturn(List.of(baseline), List.of());
        when(notificationMapper.findTaskReminderCandidates(7))
            .thenReturn(List.of(), List.of(taskCandidate()));

        NotificationReconciliationService service = fixture.service();
        service.reconcileWorkspace(7, false);
        service.reconcileWorkspace(7, false);

        verify(notificationMapper).deleteHistoricalNotificationBaselines(
            7, List.of(baseline));
        verify(notificationDelivery).deliver(any(Notification.class));
    }

    @Test
    void failedOrUnmanagedPassNeverDeletesItsHistoricalBaseline() {
        ReconciliationFixture fixture = ReconciliationFixture.mockedDelivery();
        NotificationMapper notificationMapper = fixture.notificationMapper;
        NotificationDelivery notificationDelivery = fixture.notificationDelivery;
        HistoricalNotificationBaseline task =
            baseline(NotificationReconciliationService.TASK_TYPE, "warning", "task.due:91");
        HistoricalNotificationBaseline relationship =
            baseline(NotificationReconciliationService.RELATIONSHIP_TYPE,
                "warning", "relationship.cooling:5:9");
        when(notificationMapper.findHistoricalNotificationBaselines(7))
            .thenReturn(List.of(task, relationship));
        when(notificationMapper.findTaskReminderCandidates(7))
            .thenThrow(new IllegalStateException("task candidates unavailable"));

        NotificationReconciliationService service = fixture.service();
        service.reconcileWorkspace(7, false);

        verify(notificationMapper, never()).deleteHistoricalNotificationBaselines(
            anyInt(), anyList());
        verify(notificationDelivery, never()).deliver(any());
    }

    @Test
    void importSnapshotPersistsOnlyChangedExpectationsCausedByImportedEntities() {
        ReconciliationFixture fixture = ReconciliationFixture.mockedDelivery();
        NotificationMapper notificationMapper = fixture.notificationMapper;
        NotificationDelivery notificationDelivery = fixture.notificationDelivery;
        NotificationReconciliationService service = fixture.service(Clock.systemUTC());
        NotificationReconciliationService.HistoricalExpectationKey unchanged =
            new NotificationReconciliationService.HistoricalExpectationKey(
                7, 42, "task.due:1");
        NotificationReconciliationService.HistoricalExpectationKey changed =
            new NotificationReconciliationService.HistoricalExpectationKey(
                7, 42, "task.due:2");
        NotificationReconciliationService.HistoricalExpectationKey created =
            new NotificationReconciliationService.HistoricalExpectationKey(
                7, 42, "deal.close:3");
        NotificationReconciliationService.HistoricalExpectation warning =
            new NotificationReconciliationService.HistoricalExpectation("task.due", "warning");
        NotificationReconciliationService.HistoricalExpectationSnapshot before =
            new NotificationReconciliationService.HistoricalExpectationSnapshot(Map.of(
                unchanged, warning,
                changed, warning));
        NotificationReconciliationService.HistoricalExpectationSnapshot after =
            new NotificationReconciliationService.HistoricalExpectationSnapshot(Map.of(
                unchanged, warning,
                changed, new NotificationReconciliationService.HistoricalExpectation(
                    "task.due", "critical"),
                created, new NotificationReconciliationService.HistoricalExpectation(
                    "deal.close", "warning")));
        HistoricalNotificationBaseline existing =
            baseline(NotificationReconciliationService.TASK_TYPE, "warning", "task.due:2");
        existing.setSourceStateHash(warning.sourceStateHash());
        when(notificationMapper.findHistoricalNotificationBaselines(7))
            .thenReturn(List.of(existing));

        service.persistHistoricalBaselines(
            7,
            before,
            after,
            new NotificationReconciliationService.HistoricalBaselineScope(
                Set.of(), Set.of(), Set.of(), Set.of(2)),
            "f".repeat(64));

        verify(notificationMapper).insertHistoricalNotificationBaselines(
            eq(7),
            org.mockito.ArgumentMatchers.argThat(baselines ->
                baselines.size() == 1
                    && baselines.stream().noneMatch(
                        baseline -> "task.due:1".equals(baseline.getDedupeKey()))
                    && baselines.stream().anyMatch(
                        baseline -> "task.due:2".equals(baseline.getDedupeKey())
                            && "critical".equals(baseline.getBaselineSeverity()))
                    && baselines.stream().noneMatch(
                        baseline -> "deal.close:3".equals(baseline.getDedupeKey()))));
        verify(notificationDelivery, never()).deliver(any());
    }

    @Test
    void importSnapshotPreservesBaselineReleasedByLiveSourceChange() {
        ReconciliationFixture fixture = ReconciliationFixture.mockedDelivery();
        NotificationMapper notificationMapper = fixture.notificationMapper;
        NotificationReconciliationService service = fixture.service(Clock.systemUTC());
        NotificationReconciliationService.HistoricalExpectationKey key =
            new NotificationReconciliationService.HistoricalExpectationKey(
                7, 42, "task.due:91");
        NotificationReconciliationService.HistoricalExpectation beforeExpectation =
            new NotificationReconciliationService.HistoricalExpectation(
                NotificationReconciliationService.TASK_TYPE,
                "warning",
                "a".repeat(64));
        NotificationReconciliationService.HistoricalExpectation afterExpectation =
            new NotificationReconciliationService.HistoricalExpectation(
                NotificationReconciliationService.TASK_TYPE,
                "critical",
                "b".repeat(64));
        HistoricalNotificationBaseline existing =
            baseline(NotificationReconciliationService.TASK_TYPE, "warning", "task.due:91");
        existing.setSourceStateHash("c".repeat(64));
        when(notificationMapper.findHistoricalNotificationBaselines(7))
            .thenReturn(List.of(existing));

        service.persistHistoricalBaselines(
            7,
            new NotificationReconciliationService.HistoricalExpectationSnapshot(
                Map.of(key, beforeExpectation)),
            new NotificationReconciliationService.HistoricalExpectationSnapshot(
                Map.of(key, afterExpectation)),
            new NotificationReconciliationService.HistoricalBaselineScope(
                Set.of(), Set.of(), Set.of(), Set.of(91)),
            "f".repeat(64));

        verify(notificationMapper, never()).insertHistoricalNotificationBaselines(
            anyInt(), anyList());
    }

    @Test
    void disabledDeliveryPreferenceRetainsHistoricalBaselineUntilReenabled() {
        ReconciliationFixture fixture = ReconciliationFixture.mockedDelivery();
        NotificationMapper notificationMapper = fixture.notificationMapper;
        PreferenceMapper preferenceMapper = fixture.preferenceMapper;
        NotificationDelivery notificationDelivery = fixture.notificationDelivery;
        NotificationPreference optOut = new NotificationPreference();
        optOut.setUserId(42);
        optOut.setType(NotificationReconciliationService.TASK_TYPE);
        optOut.setChannel("in_app");
        optOut.setEnabled(false);
        when(preferenceMapper.findByWorkspaceAndChannel(7, "in_app"))
            .thenReturn(List.of(optOut));
        when(notificationMapper.findTaskReminderCandidates(7))
            .thenReturn(List.of(taskCandidate()));
        ScoringService scoringService = fixture.scoringService;
        when(scoringService.scoreContactsExcludingHistoryImports(
                eq(7), any(Instant.class), any(), any(), any()))
            .thenReturn(List.of());
        NotificationReconciliationService service = fixture.service(Clock.fixed(
                Instant.parse("2026-06-23T15:30:00Z"), ZoneOffset.UTC));

        NotificationReconciliationService.HistoricalExpectationSnapshot snapshot =
            service.historicalExpectationSnapshot(
                7, Instant.parse("2026-06-23T15:30:00Z"));
        HistoricalNotificationBaseline baseline =
            baseline(NotificationReconciliationService.TASK_TYPE, "warning", "task.due:91");
        baseline.setSourceStateHash(
            snapshot.expectations().values().iterator().next().sourceStateHash());
        when(notificationMapper.findHistoricalNotificationBaselines(7))
            .thenReturn(List.of(baseline));
        when(preferenceMapper.findByWorkspaceAndChannel(7, "in_app"))
            .thenReturn(List.of(optOut), List.of());

        service.reconcileWorkspace(7, false);
        service.reconcileWorkspace(7, false);

        assertTrue(snapshot.expectations().keySet().stream()
            .anyMatch(key -> "task.due:91".equals(key.dedupeKey())));
        verify(notificationMapper, never()).deleteHistoricalNotificationBaselines(
            anyInt(), anyList());
        verify(notificationDelivery, never()).deliver(any());
    }

    @Test
    void importedWarmthThatRemovesDealRiskPreservesUntilLiveSourceChange() {
        ReconciliationFixture fixture = ReconciliationFixture.mockedDelivery();
        NotificationMapper notificationMapper = fixture.notificationMapper;
        NotificationDelivery notificationDelivery = fixture.notificationDelivery;
        DealRiskService dealRiskService = fixture.dealRiskService;
        Clock clock = Clock.fixed(
            Instant.parse("2026-06-23T15:30:00Z"), ZoneOffset.UTC);
        NotificationReconciliationService service =
            fixture.service();
        NotificationReconciliationService.HistoricalExpectationKey key =
            new NotificationReconciliationService.HistoricalExpectationKey(
                7, 42, "deal.risk:101");
        NotificationReconciliationService.HistoricalExpectation previous =
            new NotificationReconciliationService.HistoricalExpectation(
                NotificationReconciliationService.DEAL_RISK_TYPE,
                "critical",
                "a".repeat(64));
        String warmedSourceState = "b".repeat(64);
        service.persistHistoricalBaselines(
            7,
            new NotificationReconciliationService.HistoricalExpectationSnapshot(
                Map.of(key, previous)),
            new NotificationReconciliationService.HistoricalExpectationSnapshot(
                Map.of(),
                Map.of(key, warmedSourceState)),
            new NotificationReconciliationService.HistoricalBaselineScope(
                Set.of(9), Set.of(81), Set.of(), Set.of()),
            "f".repeat(64));
        verify(notificationMapper).insertHistoricalNotificationBaselines(
            eq(7),
            org.mockito.ArgumentMatchers.argThat(baselines ->
                baselines.size() == 1
                    && "deal.risk:101".equals(
                        baselines.getFirst().getDedupeKey())
                    && warmedSourceState.equals(
                        baselines.getFirst().getSourceStateHash())));
        HistoricalNotificationBaseline baseline = baseline(
            NotificationReconciliationService.DEAL_RISK_TYPE,
            "critical",
            "deal.risk:101");
        baseline.setSourceStateHash(warmedSourceState);
        Notification existing = reminderNotification(
            202,
            NotificationReconciliationService.DEAL_RISK_TYPE,
            "deal.risk:101");
        when(notificationMapper.findWorkspaceReminderNotifications(7))
            .thenReturn(List.of(existing));
        when(notificationMapper.findHistoricalNotificationBaselines(7))
            .thenReturn(List.of(baseline));
        when(notificationMapper.findOpenDealRecipients(7))
            .thenReturn(List.of(recipient(101, "Acme renewal", 42)));
        DealRiskDto none = new DealRiskDto(
            101,
            BigDecimal.ZERO,
            null,
            "none",
            0,
            List.of(),
            "2026-06-23 15:30:00");
        when(dealRiskService.assessWorkspaceNotificationStates(eq(7), any(), any()))
            .thenReturn(
                List.of(riskState(none, warmedSourceState)),
                List.of(riskState(none, "c".repeat(64))));
        when(notificationMapper.deleteHistoricalNotificationBaselines(
                7, List.of(baseline)))
            .thenReturn(1);
        when(notificationMapper.resolveReminder(
                7, 42, 202, "2026-06-23 15:30:00"))
            .thenReturn(1);

        service.reconcileWorkspace(7, true);

        assertEquals(warmedSourceState, baseline.getSourceStateHash());
        verify(notificationDelivery, never()).deliver(any());
        verify(notificationMapper, never()).deleteHistoricalNotificationBaselines(
            anyInt(), anyList());
        verify(notificationMapper, never()).resolveReminder(
            anyInt(), anyInt(), anyInt(), anyString());

        service.reconcileWorkspace(7, true);

        verify(notificationMapper).deleteHistoricalNotificationBaselines(
            7, List.of(baseline));
        verify(notificationMapper).resolveReminder(
            7, 42, 202, "2026-06-23 15:30:00");
    }

    @Test
    void historicalBaselineScopeRejectsUnrelatedWorkspaceExpectations() {
        NotificationReconciliationService.HistoricalBaselineScope scope =
            new NotificationReconciliationService.HistoricalBaselineScope(
                Set.of(9), Set.of(81), Set.of(86), Set.of(91));

        assertTrue(scope.includes(
            new NotificationReconciliationService.HistoricalExpectationKey(
                7, 42, "task.due:91"),
            new NotificationReconciliationService.HistoricalExpectation(
                NotificationReconciliationService.TASK_TYPE, "warning")));
        assertTrue(scope.includes(
            new NotificationReconciliationService.HistoricalExpectationKey(
                7, 42, "relationship.cooling:5:9"),
            new NotificationReconciliationService.HistoricalExpectation(
                NotificationReconciliationService.RELATIONSHIP_TYPE, "warning")));
        assertTrue(scope.includes(
            new NotificationReconciliationService.HistoricalExpectationKey(
                7, 42, "relationship.intro_opportunity:8:9"),
            new NotificationReconciliationService.HistoricalExpectation(
                NotificationReconciliationService.INTRO_OPPORTUNITY_TYPE, "info")));
        assertFalse(scope.includes(
            new NotificationReconciliationService.HistoricalExpectationKey(
                7, 42, "task.due:92"),
            new NotificationReconciliationService.HistoricalExpectation(
                NotificationReconciliationService.TASK_TYPE, "warning")));
        assertFalse(scope.includes(
            new NotificationReconciliationService.HistoricalExpectationKey(
                7, 42, "deal.close:5"),
            new NotificationReconciliationService.HistoricalExpectation(
                NotificationReconciliationService.DEAL_TYPE, "warning")));
        assertTrue(scope.includes(
            new NotificationReconciliationService.HistoricalExpectationKey(
                7, 42, "deal.risk:5"),
            new NotificationReconciliationService.HistoricalExpectation(
                NotificationReconciliationService.DEAL_RISK_TYPE, "warning")));
    }

    @Test
    void historicalBaselineScopeComparesOnlyRelevantCounterfactualExpectations() {
        NotificationReconciliationService.HistoricalBaselineScope scope =
            new NotificationReconciliationService.HistoricalBaselineScope(
                Set.of(9), Set.of(81), Set.of(), Set.of());
        NotificationReconciliationService.HistoricalExpectationKey relevant =
            new NotificationReconciliationService.HistoricalExpectationKey(
                7, 42, "relationship.cooling:5:9");
        NotificationReconciliationService.HistoricalExpectationKey unrelated =
            new NotificationReconciliationService.HistoricalExpectationKey(
                7, 42, "deal.close:3");
        NotificationReconciliationService.HistoricalExpectation warning =
            new NotificationReconciliationService.HistoricalExpectation(
                NotificationReconciliationService.RELATIONSHIP_TYPE, "warning");
        NotificationReconciliationService.HistoricalExpectationSnapshot before =
            new NotificationReconciliationService.HistoricalExpectationSnapshot(
                Map.of(relevant, warning));
        NotificationReconciliationService.HistoricalExpectationSnapshot unrelatedChange =
            new NotificationReconciliationService.HistoricalExpectationSnapshot(
                Map.of(
                    relevant,
                    warning,
                    unrelated,
                    new NotificationReconciliationService.HistoricalExpectation(
                        NotificationReconciliationService.DEAL_TYPE,
                        "critical")));
        NotificationReconciliationService.HistoricalExpectationSnapshot relevantChange =
            new NotificationReconciliationService.HistoricalExpectationSnapshot(
                Map.of());

        assertTrue(scope.sameRelevantExpectations(before, unrelatedChange));
        assertFalse(scope.sameRelevantExpectations(before, relevantChange));
    }

    @Test
    void historicalBaselineScopeDetectsStableSeveritySourceChanges() {
        NotificationReconciliationService.HistoricalBaselineScope scope =
            new NotificationReconciliationService.HistoricalBaselineScope(
                Set.of(9), Set.of(81), Set.of(), Set.of());
        NotificationReconciliationService.HistoricalExpectationKey relevant =
            new NotificationReconciliationService.HistoricalExpectationKey(
                7, 42, "relationship.cooling:5:9");
        NotificationReconciliationService.HistoricalExpectationSnapshot before =
            new NotificationReconciliationService.HistoricalExpectationSnapshot(
                Map.of(
                    relevant,
                    new NotificationReconciliationService.HistoricalExpectation(
                        NotificationReconciliationService.RELATIONSHIP_TYPE,
                        "warning",
                        "a".repeat(64))));
        NotificationReconciliationService.HistoricalExpectationSnapshot changed =
            new NotificationReconciliationService.HistoricalExpectationSnapshot(
                Map.of(
                    relevant,
                    new NotificationReconciliationService.HistoricalExpectation(
                        NotificationReconciliationService.RELATIONSHIP_TYPE,
                        "warning",
                        "b".repeat(64))));

        assertFalse(scope.sameRelevantExpectations(before, changed));
    }

    private static Notification reminderNotification(int id, String type, String dedupeKey) {
        Notification notification = new Notification();
        notification.setId(id);
        notification.setWorkspaceId(7);
        notification.setRecipientId(42);
        notification.setType(type);
        notification.setDedupeKey(dedupeKey);
        return notification;
    }

    private static HistoricalNotificationBaseline baseline(
            String type,
            String severity,
            String dedupeKey) {
        HistoricalNotificationBaseline baseline = new HistoricalNotificationBaseline();
        baseline.setWorkspaceId(7);
        baseline.setRecipientId(42);
        baseline.setDedupeKey(dedupeKey);
        baseline.setNotificationType(type);
        baseline.setBaselineSeverity(severity);
        baseline.setSourceStateHash("a".repeat(64));
        baseline.setImportRunId("f".repeat(64));
        return baseline;
    }

    private static TaskReminderCandidate taskCandidate() {
        TaskReminderCandidate candidate = new TaskReminderCandidate();
        candidate.setWorkspaceId(7);
        candidate.setTaskId(91);
        candidate.setTaskLabel("Send proposal");
        candidate.setDueDate("2026-06-23");
        candidate.setRecipientId(42);
        candidate.setRecipientTimezone("UTC");
        return candidate;
    }

    private static ooo.klae.connex.backend.dto.IntroSuggestionDto introSuggestion() {
        ooo.klae.connex.backend.dto.IntroSuggestionDto suggestion =
            new ooo.klae.connex.backend.dto.IntroSuggestionDto();
        suggestion.setPersonAId(3);
        suggestion.setPersonAName("Ada Lovelace");
        suggestion.setPersonBId(8);
        suggestion.setPersonBName("Alan Turing");
        suggestion.setScore(72);
        suggestion.setMutualConnections(2);
        suggestion.setSharedCompany("Bletchley");
        suggestion.setReasons(List.of("mutual_connections", "shared_company"));
        return suggestion;
    }
}
