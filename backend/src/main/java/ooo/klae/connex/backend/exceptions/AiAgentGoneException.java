package ooo.klae.connex.backend.exceptions;

/** A consumed mission identity or withheld output cannot be recreated or disclosed. */
public class AiAgentGoneException extends RuntimeException {
    public AiAgentGoneException(String reason) {
        super(reason);
    }
}
