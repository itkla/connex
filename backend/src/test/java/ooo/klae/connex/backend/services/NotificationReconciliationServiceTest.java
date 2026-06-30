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

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import ooo.klae.connex.backend.beans.Notification;
import ooo.klae.connex.backend.beans.NotificationPreference;
import ooo.klae.connex.backend.beans.RelationshipNudgeCandidate;
import ooo.klae.connex.backend.beans.TaskReminderCandidate;
import ooo.klae.connex.backend.dto.RelationshipTemperatureDto;
import ooo.klae.connex.backend.mappers.NotificationMapper;
import ooo.klae.connex.backend.mappers.PreferenceMapper;
import ooo.klae.connex.backend.notifications.NotificationDispatcher;
import ooo.klae.connex.backend.notifications.NotificationProperties;
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
            dispatcher,
            properties,
            scoringService,
            introductionService,
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
            dispatcher,
            new NotificationProperties(),
            scoringService,
            Mockito.mock(IntroductionService.class),
            clock,
            new ObjectMapper()
        );
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
            notificationMapper, preferenceMapper, dispatcher, new NotificationProperties(),
            scoringService, introductionService, clock, new ObjectMapper());
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
            notificationMapper, preferenceMapper, dispatcher, properties,
            scoringService, introductionService, clock, new ObjectMapper());
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
            notificationMapper, preferenceMapper, dispatcher, new NotificationProperties(),
            scoringService, introductionService, clock, new ObjectMapper());
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
            notificationMapper, preferenceMapper, dispatcher, new NotificationProperties(),
            scoringService, introductionService, clock, new ObjectMapper());
        service.reconcileWorkspace(7, false);

        verify(notificationMapper, never()).resolveReminder(anyInt(), anyInt(), anyInt(), any());
        verify(introductionService, never()).computeSuggestions(anyInt(), anyInt(), any());
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