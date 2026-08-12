package ooo.klae.connex.backend.notifications;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.AiChatRealtimeRecipientDto;
import ooo.klae.connex.backend.dto.AiChatStepFrameDto;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

class SimpAiChatRealtimePublisherTest {
    @Test
    void explicitInitiatingUserIsResolvedToOnlyTheirUserQueue() {
        SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);
        UserMapper userMapper = mock(UserMapper.class);
        User user = new User();
        user.setUsername("initiator");
        when(userMapper.getUserById(17)).thenReturn(user);
        var publisher = new SimpAiChatRealtimePublisher(
                messagingTemplate,
                userMapper,
                mock(TenantWorkScope.class),
                mock(AiChatRealtimeRecipientReader.class));
        var frame = new AiChatStepFrameDto(7, 13, 31, 2, "step", "get_record", "executed", null);

        publisher.sendUser(17, frame);

        verify(messagingTemplate).convertAndSendToUser("initiator", "/queue/ai-chat", frame);
    }

    @Test
    void missingInitiatingUserPublishesNothing() {
        SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);
        UserMapper userMapper = mock(UserMapper.class);
        var publisher = new SimpAiChatRealtimePublisher(
                messagingTemplate,
                userMapper,
                mock(TenantWorkScope.class),
                mock(AiChatRealtimeRecipientReader.class));

        publisher.sendUser(17, new AiChatStepFrameDto(
                7, 13, 31, 0, "terminal", null, "failed", "provider_error"));

        verifyNoInteractions(messagingTemplate);
    }

    @Test
    void sessionFanoutUsesOnlyTheRecipientReaderSnapshot() {
        SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);
        UserMapper userMapper = mock(UserMapper.class);
        TenantWorkScope tenantWorkScope = mock(TenantWorkScope.class);
        AiChatRealtimeRecipientReader recipientReader = mock(AiChatRealtimeRecipientReader.class);
        when(tenantWorkScope.inWorkspace(
                org.mockito.ArgumentMatchers.eq(7),
                org.mockito.ArgumentMatchers.<Supplier<List<Integer>>>any()))
                .thenAnswer(invocation -> {
                    Supplier<List<Integer>> work = invocation.getArgument(1);
                    return work.get();
                });
        when(recipientReader.recipients(7, 13)).thenReturn(List.of(17, 23));
        when(userMapper.getActiveAiChatRealtimeRecipientsByIds(7, List.of(17, 23))).thenReturn(List.of(
                new AiChatRealtimeRecipientDto(17, "owner"),
                new AiChatRealtimeRecipientDto(23, "participant")));
        var publisher = new SimpAiChatRealtimePublisher(
                messagingTemplate, userMapper, tenantWorkScope, recipientReader);
        AiChatStepFrameDto frame = new AiChatStepFrameDto(
                7, 13, 31, 0, "session", null, "updated", null);

        publisher.sendSession(7, 13, frame);

        verify(messagingTemplate).convertAndSendToUser("owner", "/queue/ai-chat", frame);
        verify(messagingTemplate).convertAndSendToUser("participant", "/queue/ai-chat", frame);
    }
}
