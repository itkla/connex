package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.function.Supplier;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.scheduling.annotation.AsyncAnnotationAdvisor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import ooo.klae.connex.backend.mail.EmailTemplateRenderer;
import ooo.klae.connex.backend.mail.MailDispatchOutcome;
import ooo.klae.connex.backend.mail.MailMessage;
import ooo.klae.connex.backend.mail.MailProperties;
import ooo.klae.connex.backend.mail.MailService;
import ooo.klae.connex.backend.tenant.TenantWorkScope;
import ooo.klae.connex.backend.support.TestTransactionManager;

/** Invitation email outcomes never depend on looking up a recipient account. */
@ExtendWith(MockitoExtension.class)
class InviteEmailServiceTest {
    @Mock private MailService mailService;
    @Mock private EmailTemplateRenderer renderer;
    @Mock private TenantWorkScope tenantWorkScope;
    private InviteEmailService service;

    @BeforeEach
    void setUp() {
        service = new InviteEmailService(mailService, renderer, new MailProperties(), tenantWorkScope);
        when(tenantWorkScope.inWorkspace(anyInt(), any())).thenAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            return ((Supplier<?>) invocation.getArgument(1)).get();
        });
    }

    @Test
    void returnsTheActualSubmissionOutcomeAndKeepsTheInviteInTheFragment() {
        when(renderer.render(any(), any(), any())).thenReturn("Invite body");
        when(mailService.submitForWorkspace(anyInt(), any())).thenReturn(MailDispatchOutcome.ACCEPTED);

        MailDispatchOutcome outcome = service.sendInvite(7, "Team", "member@example.com", "Owner", "member", "token");

        assertEquals(MailDispatchOutcome.ACCEPTED, outcome);
        ArgumentCaptor<MailMessage> message = ArgumentCaptor.forClass(MailMessage.class);
        verify(mailService).submitForWorkspace(org.mockito.ArgumentMatchers.eq(7), message.capture());
        assertEquals("member@example.com", message.getValue().to());
        ArgumentCaptor<java.util.Map<String, String>> substitutions = ArgumentCaptor.captor();
        verify(renderer).render(org.mockito.ArgumentMatchers.eq("invite"), org.mockito.ArgumentMatchers.eq("en"), substitutions.capture());
        assertEquals("http://localhost:3000/invite#token=token", substitutions.getValue().get("acceptUrl"));
    }

    @Test
    void failedRenderingReportsFailureWithoutAttemptingDelivery() {
        when(renderer.render(any(), any(), any())).thenThrow(new IllegalStateException("Missing template"));

        assertEquals(MailDispatchOutcome.FAILED,
            service.sendInvite(7, "Team", "member@example.com", "Owner", "member", "token"));
        verifyNoInteractions(mailService);
    }

    @Test
    void skippedMailDoesNotBecomeASentClaim() {
        when(renderer.render(any(), any(), any())).thenReturn("Invite body");
        when(mailService.submitForWorkspace(anyInt(), any())).thenReturn(MailDispatchOutcome.SKIPPED);

        assertEquals(MailDispatchOutcome.SKIPPED,
            service.sendInvite(7, "Team", "member@example.com", "Owner", "member", "token"));
    }

    @Test
    void afterCommitQueuesMailUntilTheCallerReleasesItsTransactionResources() {
        when(renderer.render(any(), any(), any())).thenReturn("Invite body");
        when(mailService.submitForWorkspace(anyInt(), any())).thenAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            return MailDispatchOutcome.SKIPPED;
        });
        TestTransactionManager transactions = new TestTransactionManager();
        List<Runnable> pending = new ArrayList<>();
        Executor executor = pending::add;
        ProxyFactory proxy = new ProxyFactory(service);
        proxy.setProxyTargetClass(true);
        proxy.addAdvisor(new AsyncAnnotationAdvisor(executor, (exception, method, arguments) -> {
            throw new AssertionError(exception);
        }));
        InviteEmailService proxied = (InviteEmailService) proxy.getProxy();

        new TransactionTemplate(transactions).executeWithoutResult(status ->
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    proxied.sendInviteDeferred(7, "Team", "member@example.com", "Owner", "member", "token");
                    verifyNoInteractions(renderer, mailService);
                }
            }));

        assertEquals(1, pending.size());
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        pending.getFirst().run();
        verify(mailService).submitForWorkspace(anyInt(), any());
    }
}
