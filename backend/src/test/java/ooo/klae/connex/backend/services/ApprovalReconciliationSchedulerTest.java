package ooo.klae.connex.backend.services;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.annotation.Transactional;

class ApprovalReconciliationSchedulerTest {

    @Test
    void schedulerUsesTheConfiguredGateCadenceAndBatchBound() throws ReflectiveOperationException {
        ConditionalOnProperty condition = ApprovalReconciliationScheduler.class
            .getAnnotation(ConditionalOnProperty.class);
        assertNotNull(condition);
        assertEquals("connex.approvals", condition.prefix());
        assertArrayEquals(new String[] {"reconciliation-enabled"}, condition.name());
        assertTrue(condition.matchIfMissing());

        Method reconcile = ApprovalReconciliationScheduler.class.getMethod("reconcile");
        Scheduled scheduled = reconcile.getAnnotation(Scheduled.class);
        assertNotNull(scheduled);
        assertEquals("${connex.approvals.reconciliation-delay-ms:900000}",
            scheduled.fixedDelayString());
        assertEquals("${connex.approvals.reconciliation-initial-delay-ms:900000}",
            scheduled.initialDelayString());

        Field batchSize = ApprovalReconciliationScheduler.class.getDeclaredField("batchSize");
        assertEquals("${connex.approvals.reconciliation-batch-size:200}",
            batchSize.getAnnotation(Value.class).value());
    }

    @Test
    void tenantScopeIsInstalledBeforeTheTerminationTransactionOpens()
            throws ReflectiveOperationException {
        Method reconcile = ApprovalReconciliationScheduler.class.getMethod("reconcile");

        assertNull(ApprovalReconciliationScheduler.class.getAnnotation(Transactional.class));
        assertNull(reconcile.getAnnotation(Transactional.class));
        assertNotNull(ApprovalReconciliationScheduler.class
            .getDeclaredField("transactionTemplate"));
    }

    /**
     * The termination methods are package-private so they stay off the RBAC-guarded surface, which
     * also means Spring's proxy-based transaction management would ignore {@code @Transactional} on
     * them. Asserting through Spring's own attribute source keeps that fact from being rediscovered
     * as a missing document lock in production: the scheduler, not the annotation, owns the
     * transaction.
     */
    @Test
    void terminationMethodsCannotRelyOnProxiedTransactionAdvice()
            throws ReflectiveOperationException {
        AnnotationTransactionAttributeSource source = new AnnotationTransactionAttributeSource();
        for (String name : new String[] {"terminateIfUnsatisfiable", "invalidateForPolicyChange"}) {
            Method method = java.util.Arrays.stream(
                    DocumentApprovalService.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(name))
                .findFirst()
                .orElseThrow();
            assertFalse(Modifier.isPublic(method.getModifiers()));
            assertNull(source.getTransactionAttribute(method, DocumentApprovalService.class));
        }
    }
}
