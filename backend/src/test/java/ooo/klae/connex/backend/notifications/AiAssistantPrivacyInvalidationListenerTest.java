package ooo.klae.connex.backend.notifications;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.SimpleTransactionStatus;

import ooo.klae.connex.backend.ai.assistant.AiAssistantPrivacyService;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.services.AutomationExecutor;
import ooo.klae.connex.backend.services.SystemActor;
import ooo.klae.connex.backend.tenant.ControlPlaneRead;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

class AiAssistantPrivacyInvalidationListenerTest {
    @Test
    void committedWithdrawalUsesBoundedPlacementScopesAndContinuesAfterUnavailableWorkspace() throws Exception {
        var authority = mock(AiAssistantPrivacyService.class);
        var scope = mock(TenantWorkScope.class);
        var automation = mock(AutomationExecutor.class);
        var system = mock(SystemActor.class);
        var transactions = mock(PlatformTransactionManager.class);
        var realtime = mock(AiChatRealtimeDispatcher.class);
        var actor = new User();
        when(system.user()).thenReturn(actor);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        doAnswer(call -> call.<ControlPlaneRead<?>>getArgument(0).read())
                .when(scope).unroutedRead(any());
        when(authority.operationWorkspaceIds(1, "operation")).thenReturn(List.of(5, 6, 7));
        when(authority.organizationId(5)).thenReturn(1);
        when(authority.organizationId(6)).thenReturn(2);
        when(authority.organizationId(7)).thenReturn(1);
        doAnswer(call -> {
            if (call.<Integer>getArgument(0) == 5) {
                throw new IllegalStateException("Unavailable placement");
            }
            return call.<Supplier<?>>getArgument(3).get();
        }).when(automation).runAs(anyInt(), eq(actor), eq("system"), any());
        var listener = new AiAssistantPrivacyInvalidationListener(
                authority, scope, automation, system, transactions, realtime);

        listener.invalidated(new AiAssistantPrivacyService.Invalidated(1, "operation", 4));

        verify(realtime).workspacePrivacyNow(7, 4);
        verify(automation, org.mockito.Mockito.never()).runAs(eq(6), eq(actor), eq("system"), any());
        verify(transactions).getTransaction(org.mockito.ArgumentMatchers.argThat(
                definition -> definition.getPropagationBehavior() == TransactionDefinition.PROPAGATION_NOT_SUPPORTED));
        var callback = AiAssistantPrivacyInvalidationListener.class.getMethod(
                "invalidated", AiAssistantPrivacyService.Invalidated.class);
        assertEquals(TransactionPhase.AFTER_COMMIT,
                callback.getAnnotation(TransactionalEventListener.class).phase());
    }
}
