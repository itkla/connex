package ooo.klae.connex.backend.ai.assistant;

import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;

import org.mockito.Answers;
import org.mockito.Mockito;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import ooo.klae.connex.backend.services.MutationAdmission;
import ooo.klae.connex.backend.services.TagService;

/** Adapts legacy domain fixtures to explicitly admit their mocked canonical mutation boundary. */
final class AiAssistantAdmissionTestSupport {
    private AiAssistantAdmissionTestSupport() {
    }

    static <T> T admittingMock(Class<T> type) {
        return Mockito.mock(type, invocation -> {
            Object[] arguments = invocation.getArguments();
            if (arguments.length > 0 && arguments[arguments.length - 1] instanceof MutationAdmission admission) {
                admission.beforeMutation();
                Class<?>[] parameters = invocation.getMethod().getParameterTypes();
                try {
                    return type.getMethod(invocation.getMethod().getName(),
                            Arrays.copyOf(parameters, parameters.length - 1))
                            .invoke(invocation.getMock(), Arrays.copyOf(arguments, arguments.length - 1));
                } catch (InvocationTargetException exception) {
                    Throwable cause = exception.getCause();
                    if (cause == null) {
                        throw exception;
                    }
                    throw cause;
                }
            }
            if (invocation.getMock() instanceof TagService service
                    && "lockDefinitionForShare".equals(invocation.getMethod().getName())) {
                int id = invocation.getArgument(0, Integer.class);
                return service.getAllTags().stream().filter(tag -> tag.getId() == id).findFirst()
                        .orElseThrow(() -> new AssertionError("Resolved tag fixture is missing"));
            }
            return Answers.RETURNS_DEFAULTS.answer(invocation);
        });
    }

    static void beginTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
    }

    static void endTransaction() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
            }
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }
}
