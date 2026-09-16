package ooo.klae.connex.backend.ai;

import ooo.klae.connex.backend.ai.assistant.AiAssistantLoopException;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.exceptions.TooManyRequestsException;

/** Keeps internal invocation refusals distinct from sanitized provider transport failures. */
public final class AiProviderGateExceptions {
    private AiProviderGateExceptions() {
    }

    /**
     * Preserves authorization, restriction, admission, and assistant turn-state refusals across
     * provider client and adapter normalization. Other failures retain transport sanitization.
     * @param exception failure intercepted around a provider attempt or its pre-send checkpoint
     */
    public static void rethrowIfGate(Exception exception) {
        if (exception instanceof RuntimeException failure
                && (failure instanceof ForbiddenException
                        || failure instanceof AiRestrictionEpoch.EgressRejectedException
                        || failure instanceof TooManyRequestsException
                        || failure instanceof AiAssistantLoopException
                        || failure instanceof ConflictException
                        || failure instanceof ResourceNotFoundException)) {
            throw failure;
        }
    }
}
