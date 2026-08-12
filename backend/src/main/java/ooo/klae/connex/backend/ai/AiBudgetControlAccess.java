package ooo.klae.connex.backend.ai;

import java.util.function.Supplier;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/** Executes shared organization AI budget work against the unrouted control catalog. */
@Component
@RequiredArgsConstructor
public class AiBudgetControlAccess {
    private final TenantWorkScope tenantWorkScope;
    private final TenantContext tenantContext;
    private final PlatformTransactionManager transactionManager;

    /** Runs one control-plane budget operation outside any routed tenant transaction. */
    public <T> T execute(Supplier<T> work) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || tenantContext.getCatalog() == null) {
            return tenantWorkScope.unrouted(work);
        }
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        return transaction.execute(status -> tenantWorkScope.unrouted(work));
    }
}
