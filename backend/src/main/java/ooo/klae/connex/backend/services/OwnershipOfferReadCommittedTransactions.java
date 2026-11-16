package ooo.klae.connex.backend.services;

import java.util.Objects;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Per-operation isolation without mutating the shared transaction-template bean. */
public final class OwnershipOfferReadCommittedTransactions {
    private OwnershipOfferReadCommittedTransactions() {}

    /** Retains propagation and timeout while selecting READ COMMITTED for membership cleanup. */
    public static TransactionTemplate from(TransactionTemplate configured) {
        TransactionTemplate scoped = new TransactionTemplate(
            Objects.requireNonNull(configured.getTransactionManager(), "transactionManager"), configured);
        scoped.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return scoped;
    }
}
