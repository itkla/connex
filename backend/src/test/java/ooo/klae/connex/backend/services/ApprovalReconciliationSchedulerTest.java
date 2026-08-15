package ooo.klae.connex.backend.services;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

import ooo.klae.connex.backend.beans.DocumentApproval;

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
        Method terminate = DocumentApprovalService.class.getDeclaredMethod(
            "terminateIfUnsatisfiable", int.class, DocumentApproval.class);

        assertNull(ApprovalReconciliationScheduler.class.getAnnotation(Transactional.class));
        assertNull(reconcile.getAnnotation(Transactional.class));
        assertNotNull(terminate.getAnnotation(Transactional.class));
    }
}
