package ooo.klae.connex.backend.notifications;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
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
    private final WorkspaceService workspaces = mock(WorkspaceService.class);
    private final AiChatSession session = new AiChatSession();
    private final AiChatTurn turn = new AiChatTurn();
    private final AiAgentPrivacyBarrier barrier = new AiAgentPrivacyBarrier();
    private final SimpAiChatRealtimePublisher publisher = new SimpAiChatRealtimePublisher(
            messaging, users, scope, recipients, routingIdentities, chat, workspaces, new AiRestrictionEpoch());

    @BeforeEach
    void setUp() {
        doAnswer(invocation -> {
            Runnable work = invocation.getArgument(1);
            work.run();
            return null;
        }).when(scope).inWorkspace(eq(7), any(Runnable.class));
        session.setId(13);
        session.setWorkspaceId(7);
        session.setMissionTaintSeq(3);
        turn.setId(31);
        turn.setRequestedByUserId(17);
        turn.setStatus("running");
        turn.setPrivacyGeneration(1L);
        turn.setPrivacyTaintSeq(3);
        barrier.setWorkspaceId(7);
        barrier.setGeneration(1);
        when(chat.getTurnById(7, 13, 31)).thenReturn(turn);
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

            verify(chat).getTurnById(7, 13, 31);
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

    private void authorize(int userId) {
        when(workspaces.isMember(7, userId)).thenReturn(true);
        when(workspaces.permissionsFor(7, userId)).thenReturn(Set.of(Permission.AI_USE));
        when(chat.getAccessibleSessionById(7, userId, 13)).thenReturn(session);
        when(users.getActiveAiChatRealtimeRecipientsByIds(7, List.of(userId)))
                .thenReturn(List.of(new AiChatRealtimeRecipientDto(userId, "member")));
    }
}
