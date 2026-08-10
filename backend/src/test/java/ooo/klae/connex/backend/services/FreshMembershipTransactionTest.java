package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/** Proves fresh-membership entry points pin their target catalog before opening a transaction. */
@ExtendWith(MockitoExtension.class)
class FreshMembershipTransactionTest {
    @Mock private TenantWorkScope tenantWorkScope;
    @Mock private TransactionTemplate transactionTemplate;

    @InjectMocks private FreshMembershipTransaction transaction;

    @Test
    void opensTransactionAfterTargetWorkspaceScope() {
        when(tenantWorkScope.inWorkspace(
                eq(7), org.mockito.ArgumentMatchers.<Supplier<String>>any()))
            .thenAnswer(invocation ->
            ((Supplier<?>) invocation.getArgument(1)).get());
        when(transactionTemplate.execute(any())).thenAnswer(invocation ->
            ((TransactionCallback<?>) invocation.getArgument(0))
                .doInTransaction(new SimpleTransactionStatus()));

        String result = transaction.execute(7, () -> "joined");

        assertEquals("joined", result);
        InOrder order = inOrder(tenantWorkScope, transactionTemplate);
        order.verify(tenantWorkScope).inWorkspace(
            eq(7), org.mockito.ArgumentMatchers.<Supplier<String>>any());
        order.verify(transactionTemplate).execute(any());
    }

    @Test
    void onboardingEntrypointsDoNotOpenTransactionsBeforeWorkspaceRouting() throws Exception {
        assertNull(InviteService.class
            .getMethod("createInvite", int.class, User.class, String.class, String.class)
            .getAnnotation(Transactional.class));
        assertNull(InviteService.class
            .getMethod("acceptInvite", String.class, User.class)
            .getAnnotation(Transactional.class));
        assertNull(InviteService.class
            .getMethod("addExistingMember", int.class, int.class, String.class, String.class)
            .getAnnotation(Transactional.class));
        assertNull(InviteLinkService.class
            .getMethod("redeemLink", String.class, User.class)
            .getAnnotation(Transactional.class));
        assertNull(SsoLoginService.class
            .getMethod(
                "resolve",
                String.class,
                String.class,
                String.class,
                String.class,
                boolean.class,
                int.class,
                String.class)
            .getAnnotation(Transactional.class));
    }
}
