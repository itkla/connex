package ooo.klae.connex.backend.config;

import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionExecution;
import org.springframework.transaction.TransactionExecutionListener;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Records writable physical Spring transaction commits after handler execution begins. Boot registers
 * this listener on its JDBC transaction manager, including when its datasource is tenant-routed.
 * The last MVC interceptor arms the request after authentication and tenant bookkeeping. Savepoint
 * releases do not commit the outer transaction; writable independent commits conservatively count.
 */
@Component
public final class RequestTransactionCommitListener implements TransactionExecutionListener, HandlerInterceptor {
    private static final String ARMED_ATTRIBUTE = RequestTransactionCommitListener.class.getName() + ".armed";
    private static final String COMMITTED_ATTRIBUTE = RequestTransactionCommitListener.class.getName() + ".committed";

    /** Arms commit tracking after every earlier interceptor has admitted the request. */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        request.setAttribute(ARMED_ATTRIBUTE, Boolean.TRUE);
        return true;
    }

    /** Marks successful writable physical commits only within an armed, bound request. */
    @Override
    public void afterCommit(TransactionExecution transaction, Throwable commitFailure) {
        if (commitFailure == null && transaction.isNewTransaction() && !transaction.isReadOnly()) {
            RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
            if (attributes != null && Boolean.TRUE.equals(
                    attributes.getAttribute(ARMED_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST))) {
                attributes.setAttribute(COMMITTED_ATTRIBUTE, Boolean.TRUE, RequestAttributes.SCOPE_REQUEST);
            }
        }
    }

    /** Whether a writable Spring transaction committed in this request's handler phase on this thread. */
    public static boolean committedDuringRequest() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        return attributes != null && Boolean.TRUE.equals(
            attributes.getAttribute(COMMITTED_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST));
    }
}
