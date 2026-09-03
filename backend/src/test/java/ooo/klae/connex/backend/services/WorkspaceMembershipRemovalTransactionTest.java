package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.function.BiFunction;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

@ExtendWith(MockitoExtension.class)
class WorkspaceMembershipRemovalTransactionTest {
    @Mock private TenantWorkScope tenantWorkScope;
    @Mock private TenantContext tenantContext;
    @Mock private TransactionTemplate transactionTemplate;

    @InjectMocks private WorkspaceMembershipRemovalTransaction transaction;

    @Test
    void authorizesBeforePinningPathWorkspaceIdentityAndOpeningTransaction() {
        AtomicBoolean authorized = new AtomicBoolean();
        when(tenantContext.isResolved()).thenReturn(true);
        when(tenantContext.getWorkspaceId()).thenReturn(11);
        when(tenantContext.getOrgId()).thenReturn(12);
        when(tenantContext.getUserId()).thenReturn(7);
        when(tenantContext.getRole()).thenReturn("member");
        when(tenantContext.getScopeCatalog()).thenReturn("tenant_a");
        when(tenantWorkScope.withWorkspacePlacement(
                eq(23), org.mockito.ArgumentMatchers.<BiFunction<Integer, String, String>>any()))
            .thenAnswer(invocation -> {
                assertTrue(authorized.get());
                BiFunction<Integer, String, String> work = invocation.getArgument(1);
                return work.apply(51, "tenant_b");
            });
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(org.mockito.Mockito.mock(TransactionStatus.class));
        });

        String result = transaction.execute(
            23,
            7,
            () -> {
                authorized.set(true);
                return "admin";
            },
            (workspaceId, orgId) -> workspaceId + ":" + orgId);

        assertEquals("23:51", result);
        InOrder order = inOrder(tenantWorkScope, tenantContext, transactionTemplate);
        order.verify(tenantWorkScope).withWorkspacePlacement(
            eq(23), org.mockito.ArgumentMatchers.<BiFunction<Integer, String, String>>any());
        order.verify(tenantContext).set(23, 51, 7, "admin", "tenant_b");
        order.verify(transactionTemplate).execute(any());
        order.verify(tenantContext).set(11, 12, 7, "member", "tenant_a");
    }

    @Test
    void authorizationFailureDoesNotResolvePlacementOrOpenTransaction() {
        SecurityException denial = new SecurityException("denied");

        SecurityException thrown = assertThrows(
            SecurityException.class,
            () -> transaction.execute(
                23,
                7,
                () -> {
                    throw denial;
                },
                (workspaceId, orgId) -> "removed"));

        assertEquals(denial, thrown);
        verifyNoInteractions(tenantWorkScope, tenantContext, transactionTemplate);
    }
}
