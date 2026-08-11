package ooo.klae.connex.backend.exceptions;

/** Explicit organization daily AI token-budget exhaustion. */
public class AiBudgetExhaustedException extends TooManyRequestsException {
    public AiBudgetExhaustedException() {
        super("The organization daily AI token budget is exhausted");
    }
}
