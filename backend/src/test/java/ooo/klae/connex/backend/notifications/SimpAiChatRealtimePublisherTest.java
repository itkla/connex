package ooo.klae.connex.backend.notifications;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
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

    private final RealtimeRoutingIdentityResolver routingIdentities = new RealtimeRoutingIdentityResolver();

    @Test
    void explicitInitiatingUserIsResolvedToOnlyTheirUserQueue() {
        SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);
        UserMapper userMapper = mock(UserMapper.class);
        User user = new User();
        user.setId(17);
        user.setUsername("initiator");
        when(userMapper.getUserById(17)).thenReturn(user);
        var publisher = new SimpAiChatRealtimePublisher(
                messagingTemplate,
                userMapper,
                mock(TenantWorkScope.class),
                mock(AiChatRealtimeRecipientReader.class),
                routingIdentities);
        var frame = new AiChatStepFrameDto(7, 13, 31, 2, "step", "get_record", "executed", null);

        publisher.sendUser(17, frame);

        String destination = routingIdentities.destinationFor(17);
        verify(messagingTemplate).convertAndSendToUser(destination, "/queue/ai-chat", frame);
        assertNotEquals("initiator", destination);
        assertNotEquals("uid:17", destination);
        assertNotEquals("17", destination);
    }

    @Test
    void missingInitiatingUserPublishesNothing() {
        SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);
        UserMapper userMapper = mock(UserMapper.class);
        var publisher = new SimpAiChatRealtimePublisher(
                messagingTemplate,
                userMapper,
                mock(TenantWorkScope.class),
                mock(AiChatRealtimeRecipientReader.class),
                routingIdentities);

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
                messagingTemplate, userMapper, tenantWorkScope, recipientReader, routingIdentities);
        AiChatStepFrameDto frame = new AiChatStepFrameDto(
                7, 13, 31, 0, "session", null, "updated", null);

        publisher.sendSession(7, 13, frame);

        verify(messagingTemplate).convertAndSendToUser(
                routingIdentities.destinationFor(17), "/queue/ai-chat", frame);
        verify(messagingTemplate).convertAndSendToUser(
                routingIdentities.destinationFor(23), "/queue/ai-chat", frame);
    }
}
