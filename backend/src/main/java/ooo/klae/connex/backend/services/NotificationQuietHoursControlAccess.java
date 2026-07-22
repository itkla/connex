package ooo.klae.connex.backend.services;

import java.time.Instant;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.NotificationQuietHours;
import ooo.klae.connex.backend.mappers.NotificationQuietHoursMapper;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/**
 * Routes notification quiet-hours persistence and evaluation to the control catalog.
 */
@Component
@RequiredArgsConstructor
public class NotificationQuietHoursControlAccess {
    private final NotificationQuietHoursMapper quietHoursMapper;
    private final NotificationQuietHoursEvaluator evaluator;
    private final TenantWorkScope tenantWorkScope;
    private final TenantContext tenantContext;
    private final PlatformTransactionManager transactionManager;

    /**
     * Loads one user's global quiet-hours preference from the control catalog.
     *
     * @param userId the preference owner
     * @return the stored preference, or {@code null} when absent
     */
    public NotificationQuietHours findByUserId(int userId) {
        return execute(() -> quietHoursMapper.findByUserId(userId));
    }

    /**
     * Replaces one user's global quiet-hours preference in the control catalog.
     *
     * @param quietHours the validated preference
     */
    public void upsert(NotificationQuietHours quietHours) {
        execute(() -> quietHoursMapper.upsert(quietHours));
    }

    /**
     * Evaluates one user's control-plane quiet-hours preference at an instant.
     *
     * @param userId the preference owner
     * @param asOf the evaluation instant
     * @return the active state and next transition
     */
    public NotificationQuietHoursEvaluator.Evaluation evaluateForUser(int userId, Instant asOf) {
        return execute(() -> {
            NotificationQuietHours quietHours = quietHoursMapper.findByUserId(userId);
            if (quietHours == null) {
                return new NotificationQuietHoursEvaluator.Evaluation(false, null);
            }
            return evaluator.evaluate(quietHours, asOf);
        });
    }

    private <T> T execute(Supplier<T> work) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || tenantContext.getCatalog() == null) {
            return tenantWorkScope.unrouted(work);
        }
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        return transaction.execute(status -> tenantWorkScope.unrouted(work));
    }
}
