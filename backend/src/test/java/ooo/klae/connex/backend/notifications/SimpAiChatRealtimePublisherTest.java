package ooo.klae.connex.backend.notifications;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

import ooo.klae.connex.backend.ai.AiRestrictionEpoch;
import ooo.klae.connex.backend.ai.assistant.AiAssistantLoopException;
import ooo.klae.connex.backend.ai.assistant.AiAssistantPrivacyPublicationGuard;
import ooo.klae.connex.backend.ai.assistant.AiAssistantPrivacyService;
import ooo.klae.connex.backend.ai.assistant.AiChatPrivacyGuard;
import ooo.klae.connex.backend.beans.AiAgentPrivacyBarrier;
import ooo.klae.connex.backend.beans.AiChatSession;
import ooo.klae.connex.backend.beans.AiChatTurn;
import ooo.klae.connex.backend.dto.AiChatRealtimeRecipientDto;
import ooo.klae.connex.backend.dto.AiChatStepFrameDto;
import ooo.klae.connex.backend.mappers.AiChatMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

class SimpAiChatRealtimePublisherTest {
    private final RealtimeRoutingIdentityResolver routingIdentities = new RealtimeRoutingIdentityResolver();
    private final SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
    private final UserMapper users = mock(UserMapper.class);
    private final TenantWorkScope scope = mock(TenantWorkScope.class);
    private final AiChatRealtimeRecipientReader recipients = mock(AiChatRealtimeRecipientReader.class);
    private final AiChatMapper chat = mock(AiChatMapper.class);
    private final AiAssistantPrivacyService authority = mock(AiAssistantPrivacyService.class);
    private final AiChatPrivacyGuard privacyGuard = new AiChatPrivacyGuard(authority);
    private final AiAssistantPrivacyPublicationGuard publication = mock(AiAssistantPrivacyPublicationGuard.class);
    private final WorkspaceService workspaces = mock(WorkspaceService.class);
    private final AiChatSession session = new AiChatSession();
    private final AiChatTurn turn = new AiChatTurn();
    private final AiAgentPrivacyBarrier barrier = new AiAgentPrivacyBarrier();
    private final SimpAiChatRealtimePublisher publisher = new SimpAiChatRealtimePublisher(
            messaging, users, scope, recipients, routingIdentities, publication, privacyGuard, chat, workspaces, new AiRestrictionEpoch());

    @BeforeEach
    void setUp() {
        doAnswer(invocation -> {
            Runnable work = invocation.getArgument(1);
            work.run();
            return null;
        }).when(scope).inWorkspace(eq(7), any(Runnable.class));
        when(authority.organizationId(7)).thenReturn(1);
        when(authority.current(1)).thenReturn(1L);
        when(authority.freshCurrent(1)).thenReturn(1L);
        doAnswer(invocation -> {
            Runnable publish = invocation.getArgument(2);
            publish.run();
            return null;
        }).when(publication).publish(anyInt(), anyLong(), any(Runnable.class));
        session.setId(13);
        session.setWorkspaceId(7);
        session.setOrganizationPrivacyGeneration(1L);
        session.setMissionTaintSeq(3);
        turn.setId(31);
        turn.setOrganizationPrivacyGeneration(1L);
        turn.setRequestedByUserId(17);
        turn.setStatus("running");
        turn.setPrivacyGeneration(1L);
        turn.setPrivacyTaintSeq(3);
        barrier.setWorkspaceId(7);
        barrier.setGeneration(1);
        when(chat.getTurnById(7, 13, 31, 1L)).thenReturn(turn);
        when(chat.getPrivacySession(7, 13)).thenReturn(session);
        when(chat.getPrivacyBarrier(7)).thenReturn(barrier);
        authorize(17);
    }

    @Test
    void explicitInitiatingUserIsResolvedToOnlyTheirUserQueue() {
        var frame = new AiChatStepFrameDto(7, 13, 31, 2, "step", "get_record", "executed", null);

        publisher.sendUser(17, frame);

        String destination = routingIdentities.destinationFor(17);
        verify(messaging).convertAndSendToUser(destination, "/queue/ai-chat", frame);
        assertNotEquals("initiator", destination);
        assertNotEquals("uid:17", destination);
        assertNotEquals("17", destination);
    }

    @Test
    void missingInitiatingUserPublishesNothing() {
        when(users.getActiveAiChatRealtimeRecipientsByIds(7, List.of(17))).thenReturn(List.of());

        publisher.sendUser(17, new AiChatStepFrameDto(7, 13, 31, 0, "step", null, "running", null));

        verifyNoInteractions(messaging);
    }

    @Test
    void sessionFanoutUsesOnlyCurrentlyAuthorizedRecipientSnapshot() {
        authorize(23);
        when(recipients.recipients(7, 13)).thenReturn(List.of(17, 23));
        AiChatStepFrameDto frame = new AiChatStepFrameDto(7, 13, 31, 0, "session", null, "updated", null);

        publisher.sendSession(7, 13, frame);

        verify(messaging).convertAndSendToUser(routingIdentities.destinationFor(17), "/queue/ai-chat", frame);
        verify(messaging).convertAndSendToUser(routingIdentities.destinationFor(23), "/queue/ai-chat", frame);
    }

    @Test
    void revocationAfterEnqueueSuppressesRequesterFrame() {
        when(workspaces.isMember(7, 17)).thenReturn(false);

        publisher.sendUser(17, new AiChatStepFrameDto(7, 13, 31, 0, "step", null, "running", null));

        verifyNoInteractions(messaging);
    }

    @Test
    void completedPurgeCannotRestampDeferredSourceWithCurrentGeneration() {
        barrier.setGeneration(2);
        barrier.setBlocked(false);

        assertThrows(AiAssistantLoopException.class, () -> publisher.sendUser(17,
                new AiChatStepFrameDto(7, 13, 31, 0, "step", null, "running", null)));

        verifyNoInteractions(messaging);
    }

    @Test
    void queuedAfterCommitFrameCannotCrossCompletedPurge() {
        StaticListableBeanFactory factory = new StaticListableBeanFactory();
        factory.addBean("realtimePublisher", publisher);
        AiChatRealtimeDispatcher dispatcher = new AiChatRealtimeDispatcher(
                factory.getBeanProvider(AiChatRealtimePublisher.class));
        TransactionSynchronizationManager.initSynchronization();
        try {
            dispatcher.userAfterCommit(17,
                    new AiChatStepFrameDto(7, 13, 31, 0, "step", null, "running", null));
            verifyNoInteractions(chat, messaging);
            barrier.setGeneration(2);
            barrier.setBlocked(false);

            TransactionSynchronizationUtils.triggerAfterCommit();

            verify(chat).getTurnById(7, 13, 31, 1L);
            verifyNoInteractions(messaging);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void missionSourceCannotReachOrdinaryRequesterChannel() {
        turn.setMissionRunId(41);

        publisher.sendUser(17, new AiChatStepFrameDto(7, 13, 31, 0, "step", null, "running", null));

        verifyNoInteractions(messaging);
    }

    @Test
    void neverTaintedSourceCannotPublishWithStaleOrganizationAuthority() {
        session.setMissionTaintSeq(null);
        turn.setPrivacyTaintSeq(null);
        when(authority.current(1)).thenReturn(2L);
        when(chat.getTurnById(7, 13, 31, 2L)).thenReturn(turn);
        when(chat.getAccessibleSessionById(7, 17, 13, 2L)).thenReturn(session);

        assertThrows(AiAssistantLoopException.class, () -> publisher.sendUser(17,
                new AiChatStepFrameDto(7, 13, 31, 0, "step", null, "running", null)));

        verifyNoInteractions(publication, messaging);
    }

    @Test
    void deletedSourceCannotRestoreQueuedContent() {
        when(chat.getTurnById(7, 13, 31, 1L)).thenReturn(null);

        publisher.sendUser(17, new AiChatStepFrameDto(7, 13, 31, 0, "step", null, "running", "retained"));

        verifyNoInteractions(publication, messaging);
    }

    @Test
    void workspaceWithdrawalReachesMembersWithoutAnyRetainedSessionRoots() {
        when(authority.current(1)).thenReturn(2L);
        authorize(23);
        when(workspaces.permissionsFor(7, 23)).thenReturn(Set.of(Permission.AI_SESSION_ADMIN));
        when(users.activeAiChatRecipientIdsAfter(7, 0, 100)).thenReturn(List.of(17, 23));
        when(users.activeAiChatRecipientIdsAfter(7, 23, 100)).thenReturn(List.of());

        publisher.invalidateWorkspace(7, 1);

        var frame = new AiChatRealtimePublisher.PrivacyInvalidated(7, "privacy_invalidated", "1");
        verify(messaging).convertAndSendToUser(routingIdentities.destinationFor(17), "/queue/ai-chat", frame);
        verify(messaging).convertAndSendToUser(routingIdentities.destinationFor(23), "/queue/ai-chat", frame);
        verifyNoInteractions(publication, chat, recipients);
    }

    @Test
    void workspaceWithdrawalRechecksRecipientAccessAndPagesBoundedly() {
        authorize(23);
        when(workspaces.isMember(7, 17)).thenReturn(false);
        when(users.activeAiChatRecipientIdsAfter(7, 0, 100)).thenReturn(List.of(17));
        when(users.activeAiChatRecipientIdsAfter(7, 17, 100)).thenReturn(List.of(23));
        when(users.activeAiChatRecipientIdsAfter(7, 23, 100)).thenReturn(List.of());

        publisher.invalidateWorkspace(7, 1);

        verify(messaging).convertAndSendToUser(routingIdentities.destinationFor(23), "/queue/ai-chat",
                new AiChatRealtimePublisher.PrivacyInvalidated(7, "privacy_invalidated", "1"));
        verify(messaging, org.mockito.Mockito.times(1)).convertAndSendToUser(any(), eq("/queue/ai-chat"), any(Object.class));
        verifyNoInteractions(publication, chat, recipients);
    }

    private void authorize(int userId) {
        when(workspaces.isMember(7, userId)).thenReturn(true);
        when(workspaces.permissionsFor(7, userId)).thenReturn(Set.of(Permission.AI_USE));
        when(chat.getAccessibleSessionById(7, userId, 13, 1L)).thenReturn(session);
        when(users.getActiveAiChatRealtimeRecipientsByIds(7, List.of(userId)))
                .thenReturn(List.of(new AiChatRealtimeRecipientDto(userId, "member")));
    }
}
