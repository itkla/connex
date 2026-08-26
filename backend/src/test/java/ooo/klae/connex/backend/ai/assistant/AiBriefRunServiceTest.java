package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;

import ooo.klae.connex.backend.ai.AiFeature;
import ooo.klae.connex.backend.ai.AiFeatureGate;
import ooo.klae.connex.backend.ai.AiGenerationContextRunner;
import ooo.klae.connex.backend.beans.AiBriefSchedule;
import ooo.klae.connex.backend.beans.AiChatTurn;
import ooo.klae.connex.backend.beans.Notification;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.AiChatSessionDto;
import ooo.klae.connex.backend.dto.AiChatTurnAcceptedDto;
import ooo.klae.connex.backend.dto.AiChatTurnCreateRequest;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.mappers.AiBriefScheduleMapper;
import ooo.klae.connex.backend.notifications.NotificationDelivery;
import ooo.klae.connex.backend.services.AiAssistantService;
import ooo.klae.connex.backend.services.UserService;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pins the scheduled-brief contract that matters: claim before generation, announce only after the
 * turn actually resolved, and never turn a failure into a notification.
 */
class AiBriefRunServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-24T07:00:00Z");
    private static final LocalDate CLAIM_ON = LocalDate.parse("2026-08-24");

    private final AiBriefScheduleMapper scheduleMapper = mock(AiBriefScheduleMapper.class);
    private final AiFeatureGate featureGate = mock(AiFeatureGate.class);
    private final AiGenerationContextRunner contextRunner = mock(AiGenerationContextRunner.class);
    private final AiAssistantService assistantService = mock(AiAssistantService.class);
    private final AiAssistantTurnService turnService = mock(AiAssistantTurnService.class);
    private final AiChatTurnPersistenceService persistenceService =
            mock(AiChatTurnPersistenceService.class);
    private final NotificationDelivery notificationDelivery = mock(NotificationDelivery.class);
    private final UserService userService = mock(UserService.class);
    private final PlatformTransactionManager transactionManager =
            mock(PlatformTransactionManager.class);

    private final AiBriefRunService service = new AiBriefRunService(
            scheduleMapper, featureGate, contextRunner, assistantService, turnService,
            persistenceService, notificationDelivery, userService,
            JsonMapper.builder().build(), transactionManager, Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void installIdentity() {
        User owner = new User();
        owner.setId(11);
        owner.setLocale("en");
        when(userService.getActiveWorkspaceUser(eq(7), eq(11))).thenReturn(owner);
        when(featureGate.isAiUsable(AiFeature.ASSISTANT_CHAT)).thenReturn(true);
        doAnswer(invocation -> {
            invocation.getArgument(3, Runnable.class).run();
            return null;
        }).when(contextRunner).run(anyInt(), anyInt(), any(), any(Runnable.class));
    }

    private static AiBriefSchedule schedule() {
        AiBriefSchedule schedule = new AiBriefSchedule();
        schedule.setId(4);
        schedule.setWorkspaceId(7);
        schedule.setUserId(11);
        schedule.setTimeZone("UTC");
        schedule.setDailyEnabled(true);
        return schedule;
    }

    private static AiBriefSchedule pending(String kind) {
        AiBriefSchedule schedule = schedule();
        schedule.setPendingKind(kind);
        schedule.setPendingSessionId(3);
        schedule.setPendingTurnId(9);
        schedule.setPendingStartedAt("2026-08-24 06:58:00.000000");
        return schedule;
    }

    private static AiChatTurn turn(String status) {
        AiChatTurn turn = new AiChatTurn();
        turn.setStatus(status);
        return turn;
    }

    @Test
    void anUnusableProviderSkipsWithoutSpendingTheDaysClaim() {
        when(featureGate.isAiUsable(AiFeature.ASSISTANT_CHAT)).thenReturn(false);

        assertEquals(AiBriefRunService.Outcome.SKIPPED,
                service.start(schedule(), AiBriefRunService.DAILY, CLAIM_ON));

        verify(scheduleMapper, never()).claimPeriod(anyInt(), anyInt(), anyString(), anyString());
        verify(notificationDelivery, never()).deliver(any());
    }

    @Test
    void losingTheClaimToAnotherInstanceStartsNothing() {
        when(featureGate.isAiUsable(AiFeature.ASSISTANT_CHAT)).thenReturn(true);
        when(scheduleMapper.claimPeriod(7, 4, AiBriefRunService.DAILY, "2026-08-24")).thenReturn(0);

        assertEquals(AiBriefRunService.Outcome.SKIPPED,
                service.start(schedule(), AiBriefRunService.DAILY, CLAIM_ON));

        verify(assistantService, never()).create(any());
    }

    @Test
    void aClaimedRunStartsOneOrdinaryTurnWhoseRequestTheSkillRouterRecognizes() {
        when(featureGate.isAiUsable(AiFeature.ASSISTANT_CHAT)).thenReturn(true);
        when(scheduleMapper.claimPeriod(7, 4, AiBriefRunService.WEEKLY, "2026-08-24")).thenReturn(1);
        AiChatSessionDto session = new AiChatSessionDto();
        session.setId(3);
        when(assistantService.create(any())).thenReturn(session);
        when(turnService.start(eq(3), any()))
                .thenReturn(new AiChatTurnAcceptedDto(9, 3, "handle", "accepted"));

        assertEquals(AiBriefRunService.Outcome.STARTED,
                service.start(schedule(), AiBriefRunService.WEEKLY, CLAIM_ON));

        ArgumentCaptor<AiChatTurnCreateRequest> request =
                ArgumentCaptor.forClass(AiChatTurnCreateRequest.class);
        verify(turnService).start(eq(3), request.capture());
        AiSkillRouter router = new AiSkillRouter(new AiSkillCatalog(), permissive());
        assertEquals("daily_work_brief_v1",
                router.route(7, 11, request.getValue().content(),
                        java.util.List.of(), AiChatQueryScope.none()).skill().key(),
                "A scheduled run must take the same routed path a typed request takes");
        assertEquals(AiChatScopeBounds.BRIEF_WEEKLY_PERIOD_DAYS,
                request.getValue().scope().periodDays());
        assertEquals("me", request.getValue().scope().ownerMode());
        verify(scheduleMapper).attachPendingTurn(
                eq(7), eq(4), eq(AiBriefRunService.WEEKLY), eq(3), eq(9), anyString());
    }

    @Test
    void aRunThatCannotStartRecordsTheFailureAndNotifiesNobody() {
        when(featureGate.isAiUsable(AiFeature.ASSISTANT_CHAT)).thenReturn(true);
        when(scheduleMapper.claimPeriod(7, 4, AiBriefRunService.DAILY, "2026-08-24")).thenReturn(1);
        when(assistantService.create(any())).thenThrow(new ForbiddenException("revoked"));

        assertEquals(AiBriefRunService.Outcome.FAILED,
                service.start(schedule(), AiBriefRunService.DAILY, CLAIM_ON));

        verify(scheduleMapper).recordStartFailure(
                eq(7), eq(4), eq(AiBriefRunService.REASON_START_FAILED), anyString());
        verify(notificationDelivery, never()).deliver(any());
    }

    @Test
    void aResolvedBriefIsReleasedBeforeItIsAnnouncedSoItCanOnlyBeAnnouncedOnce() {
        AiBriefSchedule pending = pending(AiBriefRunService.DAILY);
        when(persistenceService.readTurn(3, 9)).thenReturn(turn("resolved"));
        when(scheduleMapper.releasePendingTurn(
                eq(7), eq(4), eq(9), eq(true), any(), anyString())).thenReturn(1);

        assertEquals(AiBriefRunService.Outcome.DELIVERED, service.deliverPending(pending));

        ArgumentCaptor<Notification> notification = ArgumentCaptor.forClass(Notification.class);
        verify(notificationDelivery).deliver(notification.capture());
        assertEquals(11, notification.getValue().getRecipientId());
        assertEquals(AiBriefRunService.NOTIFICATION_TYPE, notification.getValue().getType());
        assertEquals("/ask-connex/3", notification.getValue().getActionUrl());
        assertEquals("ai.brief:daily:9", notification.getValue().getDedupeKey());
    }

    @Test
    void aSecondSweepThatLosesTheReleaseRaceAnnouncesNothing() {
        AiBriefSchedule pending = pending(AiBriefRunService.DAILY);
        when(persistenceService.readTurn(3, 9)).thenReturn(turn("resolved"));
        when(scheduleMapper.releasePendingTurn(
                eq(7), eq(4), eq(9), eq(true), any(), anyString())).thenReturn(0);

        assertEquals(AiBriefRunService.Outcome.SKIPPED, service.deliverPending(pending));

        verify(notificationDelivery, never()).deliver(any());
    }

    @Test
    void aFailedOrTimedOutBriefIsDroppedSilentlyRatherThanNotifiedAsAnError() {
        for (String terminal : java.util.List.of("failed", "timed_out")) {
            AiBriefSchedule pending = pending(AiBriefRunService.DAILY);
            when(persistenceService.readTurn(3, 9)).thenReturn(turn(terminal));

            assertEquals(AiBriefRunService.Outcome.FAILED, service.deliverPending(pending));
        }
        verify(scheduleMapper, org.mockito.Mockito.times(2)).releasePendingTurn(
                eq(7), eq(4), eq(9), eq(false),
                eq(AiBriefRunService.REASON_GENERATION_FAILED), anyString());
        verify(notificationDelivery, never()).deliver(any());
    }

    @Test
    void aBriefStillRunningIsLeftAloneUntilItStalls() {
        AiBriefSchedule fresh = pending(AiBriefRunService.DAILY);
        when(persistenceService.readTurn(3, 9)).thenReturn(turn("running"));

        assertEquals(AiBriefRunService.Outcome.SKIPPED, service.deliverPending(fresh));
        verify(scheduleMapper, never()).releasePendingTurn(
                anyInt(), anyInt(), anyInt(), anyBoolean(), any(), anyString());

        AiBriefSchedule stalled = pending(AiBriefRunService.DAILY);
        stalled.setPendingStartedAt("2026-08-24 03:00:00.000000");
        assertEquals(AiBriefRunService.Outcome.FAILED, service.deliverPending(stalled));
        verify(scheduleMapper).releasePendingTurn(
                eq(7), eq(4), eq(9), eq(false),
                eq(AiBriefRunService.REASON_STALLED), anyString());
        verify(notificationDelivery, never()).deliver(any());
    }

    /**
     * The gate can close between the start pass and the delivery pass. A brief generated under the
     * old fact must not be announced into a workspace that has since switched the assistant off.
     */
    @Test
    void aBriefIsNotAnnouncedOnceTheAssistantHasBeenSwitchedOffInTheWorkspace() {
        AiBriefSchedule pending = pending(AiBriefRunService.DAILY);
        when(featureGate.isAiUsable(AiFeature.ASSISTANT_CHAT)).thenReturn(false);
        when(persistenceService.readTurn(3, 9)).thenReturn(turn("resolved"));

        assertEquals(AiBriefRunService.Outcome.SKIPPED, service.deliverPending(pending));

        verify(notificationDelivery, never()).deliver(any());
        verify(persistenceService, never()).readTurn(anyInt(), anyInt());
        verify(scheduleMapper).releasePendingTurn(
                eq(7), eq(4), eq(9), eq(false),
                eq(AiBriefRunService.REASON_ACCESS_LOST), anyString());
    }

    @Test
    void aBriefWhoseOwnerLostAccessIsDroppedRatherThanAnnounced() {
        AiBriefSchedule pending = pending(AiBriefRunService.DAILY);
        when(persistenceService.readTurn(3, 9)).thenThrow(new ForbiddenException("revoked"));

        assertEquals(AiBriefRunService.Outcome.SKIPPED, service.deliverPending(pending));

        verify(scheduleMapper).releasePendingTurn(
                eq(7), eq(4), eq(9), eq(false),
                eq(AiBriefRunService.REASON_ACCESS_LOST), anyString());
        verify(notificationDelivery, never()).deliver(any());
    }

    /**
     * The release is the at-most-once claim, and it clears the pending fields, so a notification that
     * failed after it committed would leave nothing for a later sweep to retry. Release and
     * notification therefore share one transaction: a failed write rolls the release back and the
     * brief stays pending.
     */
    @Test
    void aFailedNotificationRollsTheReleaseBackSoTheBriefStaysPending() {
        AiBriefSchedule pending = pending(AiBriefRunService.DAILY);
        when(persistenceService.readTurn(3, 9)).thenReturn(turn("resolved"));
        when(scheduleMapper.releasePendingTurn(
                eq(7), eq(4), eq(9), eq(true), any(), anyString())).thenReturn(1);
        org.mockito.Mockito.doThrow(new IllegalStateException("inbox unavailable"))
                .when(notificationDelivery).deliver(any());

        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class, () -> service.deliverPending(pending));

        verify(transactionManager).rollback(any());
        verify(transactionManager, never()).commit(any());
    }

    /**
     * A membership read that timed out has said nothing about membership. Treating it as revocation
     * would discard a brief that was generated successfully and could still have been delivered, so
     * the transient failure propagates and the pending row is left exactly as it was.
     */
    @Test
    void aTransientFailureReadingTheOwnerLeavesThePendingBriefForTheNextSweep() {
        AiBriefSchedule pending = pending(AiBriefRunService.DAILY);
        when(userService.getActiveWorkspaceUser(7, 11))
                .thenThrow(new org.springframework.dao.QueryTimeoutException("timeout"));

        org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.dao.QueryTimeoutException.class,
                () -> service.deliverPending(pending));

        verify(scheduleMapper, never()).releasePendingTurn(
                anyInt(), anyInt(), anyInt(), anyBoolean(), any(), anyString());
        verify(notificationDelivery, never()).deliver(any());
    }

    /** The same is true of a turn read that failed for a reason other than access. */
    @Test
    void aTransientFailureReadingTheTurnLeavesThePendingBriefForTheNextSweep() {
        AiBriefSchedule pending = pending(AiBriefRunService.DAILY);
        when(persistenceService.readTurn(3, 9))
                .thenThrow(new org.springframework.dao.QueryTimeoutException("timeout"));

        org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.dao.QueryTimeoutException.class,
                () -> service.deliverPending(pending));

        verify(scheduleMapper, never()).releasePendingTurn(
                anyInt(), anyInt(), anyInt(), anyBoolean(), any(), anyString());
        verify(notificationDelivery, never()).deliver(any());
    }

    /**
     * The synthetic request is persisted as the member's own turn and the title heads their own
     * session list, so both answer to their locale. Routing must be identical either way — a
     * localized request that stopped selecting the work brief would silently change what a scheduled
     * run produces.
     */
    @Test
    void aJapaneseMembersScheduledBriefIsWrittenInJapaneseAndStillRoutesToTheWorkBrief() {
        User owner = new User();
        owner.setId(11);
        owner.setLocale("ja-JP");
        when(userService.getActiveWorkspaceUser(eq(7), eq(11))).thenReturn(owner);
        when(scheduleMapper.claimPeriod(7, 4, AiBriefRunService.DAILY, "2026-08-24")).thenReturn(1);
        AiChatSessionDto session = new AiChatSessionDto();
        session.setId(3);
        when(assistantService.create(any())).thenReturn(session);
        when(turnService.start(eq(3), any()))
                .thenReturn(new AiChatTurnAcceptedDto(9, 3, "handle", "accepted"));

        assertEquals(AiBriefRunService.Outcome.STARTED,
                service.start(schedule(), AiBriefRunService.DAILY, CLAIM_ON));

        ArgumentCaptor<ooo.klae.connex.backend.dto.AiChatSessionCreateRequest> sessionRequest =
                ArgumentCaptor.forClass(ooo.klae.connex.backend.dto.AiChatSessionCreateRequest.class);
        verify(assistantService).create(sessionRequest.capture());
        assertEquals("デイリーブリーフ 2026-08-24", sessionRequest.getValue().getTitle());

        ArgumentCaptor<AiChatTurnCreateRequest> request =
                ArgumentCaptor.forClass(AiChatTurnCreateRequest.class);
        verify(turnService).start(eq(3), request.capture());
        assertEquals("今日のブリーフをお願いします。", request.getValue().content());
        AiSkillRouter router = new AiSkillRouter(new AiSkillCatalog(), permissive());
        assertEquals("daily_work_brief_v1",
                router.route(7, 11, request.getValue().content(),
                        java.util.List.of(), AiChatQueryScope.none()).skill().key(),
                "A localized scheduled run must select the same skill an English one does");
    }

    /** The weekly Japanese form has to route identically too, not merely translate. */
    @Test
    void aJapaneseWeeklyReviewRoutesToTheSameWorkBriefSkill() {
        User owner = new User();
        owner.setId(11);
        owner.setLocale("ja");
        when(userService.getActiveWorkspaceUser(eq(7), eq(11))).thenReturn(owner);
        when(scheduleMapper.claimPeriod(7, 4, AiBriefRunService.WEEKLY, "2026-08-24")).thenReturn(1);
        AiChatSessionDto session = new AiChatSessionDto();
        session.setId(3);
        when(assistantService.create(any())).thenReturn(session);
        when(turnService.start(eq(3), any()))
                .thenReturn(new AiChatTurnAcceptedDto(9, 3, "handle", "accepted"));

        assertEquals(AiBriefRunService.Outcome.STARTED,
                service.start(schedule(), AiBriefRunService.WEEKLY, CLAIM_ON));

        ArgumentCaptor<AiChatTurnCreateRequest> request =
                ArgumentCaptor.forClass(AiChatTurnCreateRequest.class);
        verify(turnService).start(eq(3), request.capture());
        assertEquals("今週のブリーフをお願いします。", request.getValue().content());
        AiSkillRouter router = new AiSkillRouter(new AiSkillCatalog(), permissive());
        assertEquals("daily_work_brief_v1",
                router.route(7, 11, request.getValue().content(),
                        java.util.List.of(), AiChatQueryScope.none()).skill().key());
    }

    @Test
    void aBriefWhoseOwnerLeftTheWorkspaceIsDroppedWithoutEnteringTheirIdentity() {
        AiBriefSchedule pending = pending(AiBriefRunService.DAILY);
        when(userService.getActiveWorkspaceUser(7, 11))
                .thenThrow(new ooo.klae.connex.backend.exceptions.ResourceNotFoundException("gone"));

        assertEquals(AiBriefRunService.Outcome.SKIPPED, service.deliverPending(pending));

        verify(scheduleMapper).releasePendingTurn(
                eq(7), eq(4), eq(9), eq(false),
                eq(AiBriefRunService.REASON_ACCESS_LOST), anyString());
        verify(notificationDelivery, never()).deliver(any());
        assertTrue(true, "No turn read is attempted once the identity cannot be established");
        verify(persistenceService, never()).readTurn(anyInt(), anyInt());
    }

    private static ooo.klae.connex.backend.services.WorkspaceService permissive() {
        ooo.klae.connex.backend.services.WorkspaceService workspaceService =
                mock(ooo.klae.connex.backend.services.WorkspaceService.class);
        when(workspaceService.permissionsFor(anyInt(), anyInt()))
                .thenReturn(java.util.Set.of(ooo.klae.connex.backend.tenant.Permission.AI_USE));
        return workspaceService;
    }
}
