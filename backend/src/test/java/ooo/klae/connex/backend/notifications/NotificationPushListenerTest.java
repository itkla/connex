package ooo.klae.connex.backend.notifications;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.concurrent.atomic.AtomicReference;

import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.dto.NotificationDto;
import ooo.klae.connex.backend.mappers.NotificationMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.tenant.TenantCatalogResolver;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.tenant.TenantScopeInterceptor;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

@ExtendWith(MockitoExtension.class)
class NotificationPushListenerTest {
    private static final String STATE_VERSION_STATEMENT =
        "ooo.klae.connex.backend.mappers.NotificationMapper.getStateVersion";

    @Mock private NotificationRealtimePublisher realtimePublisher;
    @Mock private NotificationMapper notificationMapper;
    @Mock private TenantCatalogResolver tenantCatalogResolver;
    @Mock private WorkspaceMapper workspaceMapper;

    private final TenantContext tenantContext = new TenantContext();

    @AfterEach
    void clearThread() {
        tenantContext.clear();
    }

    @Test
    void pushReadsTheFinalStateVersionAfterCommit() {
        NotificationDto notification = new NotificationDto();
        notification.setId(77);
        when(notificationMapper.getStateVersion(9)).thenReturn(33L);
        NotificationPushListener listener = listener();

        listener.onPush(new NotificationPushEvent(
            9, "created", notification, "note.mention:5:9"));

        ArgumentCaptor<RealtimeNotificationPayload> payload =
            ArgumentCaptor.forClass(RealtimeNotificationPayload.class);
        verify(realtimePublisher).send(org.mockito.ArgumentMatchers.eq(9), payload.capture());
        assertEquals(33L, payload.getValue().stateVersion());
        assertEquals("created", payload.getValue().kind());
    }

    @Test
    void invalidationPushCarriesOnlyTheFinalStateVersion() {
        when(notificationMapper.getStateVersion(9)).thenReturn(34L);
        NotificationPushListener listener = listener();

        listener.onPush(new NotificationPushEvent(9, "invalidated", null, null));

        ArgumentCaptor<RealtimeNotificationPayload> payload =
            ArgumentCaptor.forClass(RealtimeNotificationPayload.class);
        verify(realtimePublisher).send(org.mockito.ArgumentMatchers.eq(9), payload.capture());
        assertEquals("invalidated", payload.getValue().kind());
        assertEquals(34L, payload.getValue().stateVersion());
        assertNull(payload.getValue().notification());
        assertNull(payload.getValue().dedupeKey());
    }

    @Test
    void stateVersionReadSatisfiesTheRoutedTenantScopeBackstop() {
        Executor guarded = (Executor) new TenantScopeInterceptor(tenantContext, true, true)
            .plugin(mock(Executor.class));
        MappedStatement statement = mock(MappedStatement.class);
        when(statement.getId()).thenReturn(STATE_VERSION_STATEMENT);
        AtomicReference<Throwable> refusedInsideTheScope = new AtomicReference<>();
        when(notificationMapper.getStateVersion(9)).thenAnswer(invocation -> {
            try {
                guarded.update(statement, new Object());
            } catch (Throwable refusal) {
                refusedInsideTheScope.set(refusal);
            }
            return 35L;
        });
        NotificationPushListener listener = listener();

        listener.onPush(new NotificationPushEvent(9, "invalidated", null, null));

        assertNull(refusedInsideTheScope.get(),
            () -> "the off-thread state-version read must satisfy the routed backstop but was refused: "
                + refusedInsideTheScope.get());
        assertThrows(IllegalStateException.class,
            () -> guarded.update(statement, new Object()),
            "the same statement outside the listener's scope must still fail closed");
        assertNull(tenantContext.getCatalog());
        verifyNoInteractions(workspaceMapper, tenantCatalogResolver);
    }

    private NotificationPushListener listener() {
        return new NotificationPushListener(
            realtimePublisher,
            notificationMapper,
            new TenantWorkScope(tenantContext, tenantCatalogResolver, workspaceMapper));
    }
}
