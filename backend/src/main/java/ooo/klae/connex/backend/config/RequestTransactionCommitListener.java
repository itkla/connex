package ooo.klae.connex.backend.config;

import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionExecution;
import org.springframework.transaction.TransactionExecutionListener;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * Records successful physical Spring transaction commits in the current request. Boot registers
 * this listener on its JDBC transaction manager, including when its datasource is tenant-routed.
 * Savepoint releases do not commit the outer transaction; independent commits conservatively count.
 */
@Component
public final class RequestTransactionCommitListener implements TransactionExecutionListener {
    private static final String COMMITTED_ATTRIBUTE = RequestTransactionCommitListener.class.getName() + ".committed";

    /** Marks only successful physical commits; execution outside a bound request is a no-op. */
    @Override
    public void afterCommit(TransactionExecution transaction, Throwable commitFailure) {
        if (commitFailure == null && transaction.isNewTransaction()) {
            RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
            if (attributes != null) {
                attributes.setAttribute(COMMITTED_ATTRIBUTE, Boolean.TRUE, RequestAttributes.SCOPE_REQUEST);
            }
        }
    }

    /** Whether any Spring transaction has committed in the current request on this thread. */
    public static boolean committedDuringRequest() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        return attributes != null && Boolean.TRUE.equals(
            attributes.getAttribute(COMMITTED_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST));
    }
}
