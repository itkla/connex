package ooo.klae.connex.backend.services;

import java.util.Objects;
import java.util.function.Supplier;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import ooo.klae.connex.backend.exceptions.ConflictException;

/** Runs approval mutations in bounded fresh transactions when recipient discovery races. */
@Service
public class ApprovalMutationRetryService {
    /** Bounds contention retries so a changing approval queue cannot monopolize a request thread. */
    public static final int MAX_ATTEMPTS = 3;

    private final PlatformTransactionManager transactionManager;

    /** Creates the transaction-bound retry coordinator. */
    public ApprovalMutationRetryService(PlatformTransactionManager transactionManager) {
        this.transactionManager = transactionManager;
    }

    /** Executes one approval mutation and retries only a changed recipient-lock snapshot. */
    public <T> T execute(Supplier<T> mutation) {
        Objects.requireNonNull(mutation, "mutation");
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                T result = template().execute(status -> mutation.get());
                return Objects.requireNonNull(result, "Approval mutation returned no result");
            } catch (ApprovalRecipientSetChangedException exception) {
                if (attempt == MAX_ATTEMPTS) {
                    throw new ConflictException("Approval changed; refresh and try again");
                }
            }
        }
        throw new IllegalStateException("Approval mutation retry loop did not terminate");
    }

    /** Executes one void approval mutation with the same bounded retry contract. */
    public void executeWithoutResult(Runnable mutation) {
        execute(() -> {
            mutation.run();
            return Boolean.TRUE;
        });
    }

    private TransactionTemplate template() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return template;
    }
}
