package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

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
        NotificationMapper notificationMapper = Mockito.mock(NotificationMapper.class);
        PreferenceMapper preferenceMapper = Mockito.mock(PreferenceMapper.class);
        NotificationDispatcher dispatcher = Mockito.mock(NotificationDispatcher.class);
        NotificationProperties properties = new NotificationProperties();
        Clock clock = Clock.fixed(Instant.parse("2026-06-23T15:30:00Z"), ZoneOffset.UTC);

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
        ScoringService scoringService = Mockito.mock(ScoringService.class);
        IntroductionService introductionService = Mockito.mock(IntroductionService.class);

        NotificationReconciliationService service = new NotificationReconciliationService(
            notificationMapper,
            preferenceMapper,
            wrap(dispatcher, notificationMapper, preferenceMapper),
            properties,
            scoringService,
            introductionService,
            noRiskService(),
            clock,
            new ObjectMapper()
        );

        service.reconcileWorkspace(7, true);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(dispatcher).dispatch(captor.capture());
        assertEquals(NotificationReconciliationService.CRITICAL, captor.getValue().getSeverity());
        assertNull(captor.getValue().getContextType());
        assertEquals("/activity/tasks?taskId=91", captor.getValue().getActionUrl());
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
        NotificationMapper notificationMapper = Mockito.mock(NotificationMapper.class);
        PreferenceMapper preferenceMapper = Mockito.mock(PreferenceMapper.class);
        NotificationDispatcher dispatcher = Mockito.mock(NotificationDispatcher.class);
        ScoringService scoringService = Mockito.mock(ScoringService.class);
        Clock clock = Clock.fixed(Instant.parse("2026-06-23T15:30:00Z"), ZoneOffset.UTC);

        RelationshipNudgeCandidate candidate = nudgeCandidate();
        when(notificationMapper.findRelationshipNudgeCandidates(7)).thenReturn(List.of(candidate));
        when(scoringService.scoreContacts(7)).thenReturn(List.of(
            new RelationshipTemperatureDto(9, 28, "cool", "cooling", "2026-05-10 09:00:00", 44, 1, null, null)
        ));

        NotificationReconciliationService service = nudgeService(
            notificationMapper, preferenceMapper, dispatcher, scoringService, clock);
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
        NotificationMapper notificationMapper = Mockito.mock(NotificationMapper.class);
        PreferenceMapper preferenceMapper = Mockito.mock(PreferenceMapper.class);
        NotificationDispatcher dispatcher = Mockito.mock(NotificationDispatcher.class);
        ScoringService scoringService = Mockito.mock(ScoringService.class);
        Clock clock = Clock.fixed(Instant.parse("2026-06-23T15:30:00Z"), ZoneOffset.UTC);

        RelationshipNudgeCandidate candidate = nudgeCandidate();
        candidate.setExpectedCloseDate("2026-06-30");
        when(notificationMapper.findRelationshipNudgeCandidates(7)).thenReturn(List.of(candidate));
        when(scoringService.scoreContacts(7)).thenReturn(List.of(
            new RelationshipTemperatureDto(9, 28, "cool", "cooling", "2026-05-10 09:00:00", 44, 1, null, null)
        ));

        NotificationReconciliationService service = nudgeService(
            notificationMapper, preferenceMapper, dispatcher, scoringService, clock);
        service.reconcileWorkspace(7, true);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(dispatcher).dispatch(captor.capture());
        Notification nudge = captor.getValue();
        assertEquals(NotificationReconciliationService.WARNING, nudge.getSeverity());
        assertTrue(nudge.getData().contains("closing_soon"));
    }

    @Test
    void reconciliationSuppressesFirstNudgeForLongDormantContact() {
        NotificationMapper notificationMapper = Mockito.mock(NotificationMapper.class);
        PreferenceMapper preferenceMapper = Mockito.mock(PreferenceMapper.class);
        NotificationDispatcher dispatcher = Mockito.mock(NotificationDispatcher.class);
        ScoringService scoringService = Mockito.mock(ScoringService.class);
        Clock clock = Clock.fixed(Instant.parse("2026-06-23T15:30:00Z"), ZoneOffset.UTC);

        when(notificationMapper.findRelationshipNudgeCandidates(7)).thenReturn(List.of(nudgeCandidate()));
        when(scoringService.scoreContacts(7)).thenReturn(List.of(
            new RelationshipTemperatureDto(9, 4, "cold", "steady", "2025-06-01 09:00:00", 365, 0, null, null)
        ));

        NotificationReconciliationService service = nudgeService(
            notificationMapper, preferenceMapper, dispatcher, scoringService, clock);
        service.reconcileWorkspace(7, true);

        verify(dispatcher, never()).dispatch(any());
    }

    @Test
    void reconciliationSkipsCoolingNudgeWhenRecipientOptedOut() {
        NotificationMapper notificationMapper = Mockito.mock(NotificationMapper.class);
        PreferenceMapper preferenceMapper = Mockito.mock(PreferenceMapper.class);
        NotificationDispatcher dispatcher = Mockito.mock(NotificationDispatcher.class);
        ScoringService scoringService = Mockito.mock(ScoringService.class);
        Clock clock = Clock.fixed(Instant.parse("2026-06-23T15:30:00Z"), ZoneOffset.UTC);

        NotificationPreference optOut = new NotificationPreference();
        optOut.setUserId(42);
        optOut.setType(NotificationReconciliationService.RELATIONSHIP_TYPE);
        optOut.setChannel("in_app");
        optOut.setEnabled(false);

        when(preferenceMapper.findByWorkspaceAndChannel(7, "in_app")).thenReturn(List.of(optOut));
        when(notificationMapper.findRelationshipNudgeCandidates(7)).thenReturn(List.of(nudgeCandidate()));
        when(scoringService.scoreContacts(7)).thenReturn(List.of(
            new RelationshipTemperatureDto(9, 28, "cool", "cooling", "2026-05-10 09:00:00", 44, 1, null, null)
        ));

        NotificationReconciliationService service = nudgeService(
            notificationMapper, preferenceMapper, dispatcher, scoringService, clock);
        service.reconcileWorkspace(7, true);

        verify(dispatcher, never()).dispatch(any());
    }

    @Test
    void reconciliationResolvesCoolingNudgeWhenContactWarmsUp() {
        NotificationMapper notificationMapper = Mockito.mock(NotificationMapper.class);
        PreferenceMapper preferenceMapper = Mockito.mock(PreferenceMapper.class);
        NotificationDispatcher dispatcher = Mockito.mock(NotificationDispatcher.class);
        ScoringService scoringService = Mockito.mock(ScoringService.class);
        Clock clock = Clock.fixed(Instant.parse("2026-06-23T15:30:00Z"), ZoneOffset.UTC);

        Notification existing = new Notification();
        existing.setId(101);
        existing.setWorkspaceId(7);
        existing.setRecipientId(42);
        existing.setType(NotificationReconciliationService.RELATIONSHIP_TYPE);
        existing.setDedupeKey("relationship.cooling:5:9");

        when(notificationMapper.findWorkspaceReminderNotifications(7)).thenReturn(List.of(existing));
        when(notificationMapper.findRelationshipNudgeCandidates(7)).thenReturn(List.of(nudgeCandidate()));
        when(scoringService.scoreContacts(7)).thenReturn(List.of(
            new RelationshipTemperatureDto(9, 72, "hot", "rising", "2026-06-22 09:00:00", 1, 6, null, null)
        ));

        NotificationReconciliationService service = nudgeService(
            notificationMapper, preferenceMapper, dispatcher, scoringService, clock);
        service.reconcileWorkspace(7, true);

        verify(dispatcher, never()).dispatch(any());
        verify(notificationMapper).resolveReminder(7, 42, 101, "2026-06-23 15:30:00");
    }

    @Test
    void reconciliationSkipsRelationshipNudgesWhenNotRequested() {
        NotificationMapper notificationMapper = Mockito.mock(NotificationMapper.class);
        PreferenceMapper preferenceMapper = Mockito.mock(PreferenceMapper.class);
        NotificationDispatcher dispatcher = Mockito.mock(NotificationDispatcher.class);
        ScoringService scoringService = Mockito.mock(ScoringService.class);
        Clock clock = Clock.fixed(Instant.parse("2026-06-23T15:30:00Z"), ZoneOffset.UTC);

        NotificationReconciliationService service = nudgeService(
            notificationMapper, preferenceMapper, dispatcher, scoringService, clock);
        service.reconcileWorkspace(7, false);

        verify(notificationMapper, never()).findRelationshipNudgeCandidates(anyInt());
        verify(scoringService, never()).scoreContacts(anyInt());
        verify(dispatcher, never()).dispatch(any());
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
        return new NotificationDelivery(
            List.of(dispatcher), notificationMapper, preferenceMapper,
            Mockito.mock(NotificationPushPublisher.class));
    }

    private static NotificationReconciliationService nudgeService(
        NotificationMapper notificationMapper,
        PreferenceMapper preferenceMapper,
        NotificationDispatcher dispatcher,
        ScoringService scoringService,
        Clock clock
    ) {
        return new NotificationReconciliationService(
            notificationMapper,
            preferenceMapper,
            wrap(dispatcher, notificationMapper, preferenceMapper),
            new NotificationProperties(),
            scoringService,
            Mockito.mock(IntroductionService.class),
            noRiskService(),
            clock,
            new ObjectMapper()
        );
    }

    /** A deal-risk service that flags nothing, so the deal-risk pass is a no-op in unrelated tests. */
    private static DealRiskService noRiskService() {
        DealRiskService mock = Mockito.mock(DealRiskService.class);
        when(mock.assessWorkspace(anyInt())).thenReturn(List.of());
        return mock;
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
        NotificationMapper notificationMapper = Mockito.mock(NotificationMapper.class);
        PreferenceMapper preferenceMapper = Mockito.mock(PreferenceMapper.class);
        NotificationDispatcher dispatcher = Mockito.mock(NotificationDispatcher.class);
        ScoringService scoringService = Mockito.mock(ScoringService.class);
        DealRiskService dealRiskService = Mockito.mock(DealRiskService.class);
        Clock clock = Clock.fixed(Instant.parse("2026-06-23T15:30:00Z"), ZoneOffset.UTC);

        DealRiskDto high = new DealRiskDto(101, "high", 60,
            List.of(new DealRiskFactor("close_overdue", "high", Map.of("daysOverdue", 22L))),
            "2026-06-23 15:30:00");
        DealRiskDto low = new DealRiskDto(102, "low", 10,
            List.of(new DealRiskFactor("no_stakeholders", "low", Map.of())),
            "2026-06-23 15:30:00");
        when(dealRiskService.assessWorkspace(7)).thenReturn(List.of(high, low));
        when(notificationMapper.findOpenDealRecipients(7)).thenReturn(List.of(
            recipient(101, "Acme renewal", 42),
            recipient(102, "Beta deal", 42)));

        NotificationReconciliationService service = new NotificationReconciliationService(
            notificationMapper, preferenceMapper, wrap(dispatcher, notificationMapper, preferenceMapper), new NotificationProperties(),
            scoringService, Mockito.mock(IntroductionService.class), dealRiskService, clock, new ObjectMapper());
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
        NotificationMapper notificationMapper = Mockito.mock(NotificationMapper.class);
        PreferenceMapper preferenceMapper = Mockito.mock(PreferenceMapper.class);
        NotificationDispatcher dispatcher = Mockito.mock(NotificationDispatcher.class);
        DealRiskService dealRiskService = Mockito.mock(DealRiskService.class);
        Clock clock = Clock.fixed(Instant.parse("2026-06-23T15:30:00Z"), ZoneOffset.UTC);
        NotificationProperties properties = new NotificationProperties();
        properties.setDealRiskEnabled(false);

        NotificationReconciliationService service = new NotificationReconciliationService(
            notificationMapper, preferenceMapper, wrap(dispatcher, notificationMapper, preferenceMapper), properties,
            Mockito.mock(ScoringService.class), Mockito.mock(IntroductionService.class),
            dealRiskService, clock, new ObjectMapper());
        service.reconcileWorkspace(7, true);

        verify(dealRiskService, never()).assessWorkspace(anyInt());
    }

    @Test
    void reconciliationEmitsDealRiskToOwnerAndCollaboratorAtWarning() {
        NotificationMapper notificationMapper = Mockito.mock(NotificationMapper.class);
        PreferenceMapper preferenceMapper = Mockito.mock(PreferenceMapper.class);
        NotificationDispatcher dispatcher = Mockito.mock(NotificationDispatcher.class);
        DealRiskService dealRiskService = Mockito.mock(DealRiskService.class);
        Clock clock = Clock.fixed(Instant.parse("2026-06-23T15:30:00Z"), ZoneOffset.UTC);

        DealRiskDto medium = new DealRiskDto(101, "medium", 25,
            List.of(new DealRiskFactor("stalled", "medium", Map.of("daysSinceTouch", 40))),
            "2026-06-23 15:30:00");
        when(dealRiskService.assessWorkspace(7)).thenReturn(List.of(medium));
        when(notificationMapper.findOpenDealRecipients(7)).thenReturn(List.of(
            recipient(101, "Acme renewal", 42),
            recipient(101, "Acme renewal", 43)));

        NotificationReconciliationService service = new NotificationReconciliationService(
            notificationMapper, preferenceMapper, wrap(dispatcher, notificationMapper, preferenceMapper), new NotificationProperties(),
            Mockito.mock(ScoringService.class), Mockito.mock(IntroductionService.class), dealRiskService, clock, new ObjectMapper());
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
        NotificationMapper notificationMapper = Mockito.mock(NotificationMapper.class);
        PreferenceMapper preferenceMapper = Mockito.mock(PreferenceMapper.class);
        NotificationDispatcher dispatcher = Mockito.mock(NotificationDispatcher.class);
        DealRiskService dealRiskService = Mockito.mock(DealRiskService.class);
        Clock clock = Clock.fixed(Instant.parse("2026-06-23T15:30:00Z"), ZoneOffset.UTC);

        NotificationPreference optOut = new NotificationPreference();
        optOut.setUserId(42);
        optOut.setType(NotificationReconciliationService.DEAL_RISK_TYPE);
        optOut.setChannel("in_app");
        optOut.setEnabled(false);

        when(preferenceMapper.findByWorkspaceAndChannel(7, "in_app")).thenReturn(List.of(optOut));
        when(dealRiskService.assessWorkspace(7)).thenReturn(List.of(new DealRiskDto(101, "high", 60,
            List.of(new DealRiskFactor("close_overdue", "high", Map.of("daysOverdue", 22L))), "2026-06-23 15:30:00")));
        when(notificationMapper.findOpenDealRecipients(7)).thenReturn(List.of(recipient(101, "Acme renewal", 42)));

        NotificationReconciliationService service = new NotificationReconciliationService(
            notificationMapper, preferenceMapper, wrap(dispatcher, notificationMapper, preferenceMapper), new NotificationProperties(),
            Mockito.mock(ScoringService.class), Mockito.mock(IntroductionService.class), dealRiskService, clock, new ObjectMapper());
        service.reconcileWorkspace(7, true);

        verify(dispatcher, never()).dispatch(any());
    }

    @Test
    void reconciliationResolvesDealRiskWhenDealNoLongerAtRisk() {
        NotificationMapper notificationMapper = Mockito.mock(NotificationMapper.class);
        PreferenceMapper preferenceMapper = Mockito.mock(PreferenceMapper.class);
        NotificationDispatcher dispatcher = Mockito.mock(NotificationDispatcher.class);
        DealRiskService dealRiskService = Mockito.mock(DealRiskService.class);
        Clock clock = Clock.fixed(Instant.parse("2026-06-23T15:30:00Z"), ZoneOffset.UTC);

        Notification existing = new Notification();
        existing.setId(202);
        existing.setWorkspaceId(7);
        existing.setRecipientId(42);
        existing.setType(NotificationReconciliationService.DEAL_RISK_TYPE);
        existing.setDedupeKey("deal.risk:101");

        when(notificationMapper.findWorkspaceReminderNotifications(7)).thenReturn(List.of(existing));
        when(dealRiskService.assessWorkspace(7)).thenReturn(List.of());

        NotificationReconciliationService service = new NotificationReconciliationService(
            notificationMapper, preferenceMapper, wrap(dispatcher, notificationMapper, preferenceMapper), new NotificationProperties(),
            Mockito.mock(ScoringService.class), Mockito.mock(IntroductionService.class), dealRiskService, clock, new ObjectMapper());
        service.reconcileWorkspace(7, true);

        verify(dispatcher, never()).dispatch(any());
        verify(notificationMapper).resolveReminder(7, 42, 202, "2026-06-23 15:30:00");
    }

    @Test
    void reconciliationEmitsIntroOpportunityForTopSuggestionToEachMember() {
        NotificationMapper notificationMapper = Mockito.mock(NotificationMapper.class);
        PreferenceMapper preferenceMapper = Mockito.mock(PreferenceMapper.class);
        NotificationDispatcher dispatcher = Mockito.mock(NotificationDispatcher.class);
        ScoringService scoringService = Mockito.mock(ScoringService.class);
        IntroductionService introductionService = Mockito.mock(IntroductionService.class);
        Clock clock = Clock.fixed(Instant.parse("2026-06-23T15:30:00Z"), ZoneOffset.UTC);

        when(notificationMapper.findWorkspaceRecipientIds(7)).thenReturn(List.of(42));
        when(introductionService.computeSuggestions(eq(7), anyInt(), any())).thenReturn(List.of(introSuggestion()));

        NotificationReconciliationService service = new NotificationReconciliationService(
            notificationMapper, preferenceMapper, wrap(dispatcher, notificationMapper, preferenceMapper), new NotificationProperties(),
            scoringService, introductionService, noRiskService(), clock, new ObjectMapper());
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
        assertEquals("/overview/introductions", opportunity.getActionUrl());
        assertEquals(42, opportunity.getRecipientId());
        assertEquals("relationship.intro_opportunity:3:8", opportunity.getDedupeKey());
    }

    @Test
    void reconciliationSkipsIntroOpportunitiesWhenDisabled() {
        NotificationMapper notificationMapper = Mockito.mock(NotificationMapper.class);
        PreferenceMapper preferenceMapper = Mockito.mock(PreferenceMapper.class);
        NotificationDispatcher dispatcher = Mockito.mock(NotificationDispatcher.class);
        ScoringService scoringService = Mockito.mock(ScoringService.class);
        IntroductionService introductionService = Mockito.mock(IntroductionService.class);
        Clock clock = Clock.fixed(Instant.parse("2026-06-23T15:30:00Z"), ZoneOffset.UTC);

        NotificationProperties properties = new NotificationProperties();
        properties.setIntroOpportunitiesEnabled(false);

        NotificationReconciliationService service = new NotificationReconciliationService(
            notificationMapper, preferenceMapper, wrap(dispatcher, notificationMapper, preferenceMapper), properties,
            scoringService, introductionService, noRiskService(), clock, new ObjectMapper());
        service.reconcileWorkspace(7, true);

        verify(introductionService, never()).computeSuggestions(anyInt(), anyInt(), any());
        verify(dispatcher, never()).dispatch(any());
    }

    @Test
    void reconciliationSkipsIntroOpportunitiesWhenNotRequested() {
        NotificationMapper notificationMapper = Mockito.mock(NotificationMapper.class);
        PreferenceMapper preferenceMapper = Mockito.mock(PreferenceMapper.class);
        NotificationDispatcher dispatcher = Mockito.mock(NotificationDispatcher.class);
        ScoringService scoringService = Mockito.mock(ScoringService.class);
        IntroductionService introductionService = Mockito.mock(IntroductionService.class);
        Clock clock = Clock.fixed(Instant.parse("2026-06-23T15:30:00Z"), ZoneOffset.UTC);

        NotificationReconciliationService service = new NotificationReconciliationService(
            notificationMapper, preferenceMapper, wrap(dispatcher, notificationMapper, preferenceMapper), new NotificationProperties(),
            scoringService, introductionService, noRiskService(), clock, new ObjectMapper());
        service.reconcileWorkspace(7, false);

        verify(introductionService, never()).computeSuggestions(anyInt(), anyInt(), any());
        verify(dispatcher, never()).dispatch(any());
    }

    @Test
    void reconciliationDoesNotResolveIntroOpportunityOnPerMutationPass() {
        NotificationMapper notificationMapper = Mockito.mock(NotificationMapper.class);
        PreferenceMapper preferenceMapper = Mockito.mock(PreferenceMapper.class);
        NotificationDispatcher dispatcher = Mockito.mock(NotificationDispatcher.class);
        ScoringService scoringService = Mockito.mock(ScoringService.class);
        IntroductionService introductionService = Mockito.mock(IntroductionService.class);
        Clock clock = Clock.fixed(Instant.parse("2026-06-23T15:30:00Z"), ZoneOffset.UTC);

        Notification existing = new Notification();
        existing.setId(55);
        existing.setWorkspaceId(7);
        existing.setRecipientId(42);
        existing.setType(NotificationReconciliationService.INTRO_OPPORTUNITY_TYPE);
        existing.setDedupeKey("relationship.intro_opportunity:3:8");
        when(notificationMapper.findWorkspaceReminderNotifications(7)).thenReturn(List.of(existing));

        NotificationReconciliationService service = new NotificationReconciliationService(
            notificationMapper, preferenceMapper, wrap(dispatcher, notificationMapper, preferenceMapper), new NotificationProperties(),
            scoringService, introductionService, noRiskService(), clock, new ObjectMapper());
        service.reconcileWorkspace(7, false);

        verify(notificationMapper, never()).resolveReminder(anyInt(), anyInt(), anyInt(), any());
        verify(introductionService, never()).computeSuggestions(anyInt(), anyInt(), any());
    }

    @Test
    void scoringFailureStillDeliversTaskRemindersAndPreservesRelationshipNotifications() {
        NotificationMapper notificationMapper = Mockito.mock(NotificationMapper.class);
        PreferenceMapper preferenceMapper = Mockito.mock(PreferenceMapper.class);
        NotificationDispatcher dispatcher = Mockito.mock(NotificationDispatcher.class);
        ScoringService scoringService = Mockito.mock(ScoringService.class);
        IntroductionService introductionService = Mockito.mock(IntroductionService.class);
        Clock clock = Clock.fixed(Instant.parse("2026-06-23T15:30:00Z"), ZoneOffset.UTC);

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
        when(scoringService.scoreContacts(7)).thenThrow(new IllegalStateException("scoring down"));

        NotificationReconciliationService service = new NotificationReconciliationService(
            notificationMapper, preferenceMapper, wrap(dispatcher, notificationMapper, preferenceMapper),
            new NotificationProperties(), scoringService, introductionService, noRiskService(), clock,
            new ObjectMapper());
        service.reconcileWorkspace(7, true);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(dispatcher).dispatch(captor.capture());
        assertEquals(NotificationReconciliationService.TASK_TYPE, captor.getValue().getType());
        verify(introductionService, never()).computeSuggestions(anyInt(), anyInt(), any());
        verify(notificationMapper, never()).resolveReminder(anyInt(), anyInt(), anyInt(), any());
    }

    @Test
    void dealRiskPassFailureDoesNotResolveExistingDealRiskNotifications() {
        NotificationMapper notificationMapper = Mockito.mock(NotificationMapper.class);
        PreferenceMapper preferenceMapper = Mockito.mock(PreferenceMapper.class);
        NotificationDispatcher dispatcher = Mockito.mock(NotificationDispatcher.class);
        DealRiskService dealRiskService = Mockito.mock(DealRiskService.class);
        Clock clock = Clock.fixed(Instant.parse("2026-06-23T15:30:00Z"), ZoneOffset.UTC);

        Notification existing = reminderNotification(
            202, NotificationReconciliationService.DEAL_RISK_TYPE, "deal.risk:101");
        when(notificationMapper.findWorkspaceReminderNotifications(7)).thenReturn(List.of(existing));
        when(dealRiskService.assessWorkspace(7)).thenThrow(new IllegalStateException("risk engine down"));

        NotificationReconciliationService service = new NotificationReconciliationService(
            notificationMapper, preferenceMapper, wrap(dispatcher, notificationMapper, preferenceMapper),
            new NotificationProperties(), Mockito.mock(ScoringService.class),
            Mockito.mock(IntroductionService.class), dealRiskService, clock, new ObjectMapper());
        service.reconcileWorkspace(7, true);

        verify(dispatcher, never()).dispatch(any());
        verify(notificationMapper, never()).resolveReminder(anyInt(), anyInt(), anyInt(), any());
    }

    @Test
    void disabledPassesStillResolveTheirStaleNotifications() {
        NotificationMapper notificationMapper = Mockito.mock(NotificationMapper.class);
        PreferenceMapper preferenceMapper = Mockito.mock(PreferenceMapper.class);
        NotificationDispatcher dispatcher = Mockito.mock(NotificationDispatcher.class);
        DealRiskService dealRiskService = Mockito.mock(DealRiskService.class);
        Clock clock = Clock.fixed(Instant.parse("2026-06-23T15:30:00Z"), ZoneOffset.UTC);

        NotificationProperties properties = new NotificationProperties();
        properties.setIntroOpportunitiesEnabled(false);
        properties.setDealRiskEnabled(false);

        Notification intro = reminderNotification(
            55, NotificationReconciliationService.INTRO_OPPORTUNITY_TYPE, "relationship.intro_opportunity:3:8");
        Notification risk = reminderNotification(
            202, NotificationReconciliationService.DEAL_RISK_TYPE, "deal.risk:101");
        when(notificationMapper.findWorkspaceReminderNotifications(7)).thenReturn(List.of(intro, risk));

        NotificationReconciliationService service = new NotificationReconciliationService(
            notificationMapper, preferenceMapper, wrap(dispatcher, notificationMapper, preferenceMapper), properties,
            Mockito.mock(ScoringService.class), Mockito.mock(IntroductionService.class),
            dealRiskService, clock, new ObjectMapper());
        service.reconcileWorkspace(7, true);

        verify(dealRiskService, never()).assessWorkspace(anyInt());
        verify(notificationMapper).resolveReminder(7, 42, 55, "2026-06-23 15:30:00");
        verify(notificationMapper).resolveReminder(7, 42, 202, "2026-06-23 15:30:00");
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