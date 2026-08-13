package ooo.klae.connex.backend.ai.provider;

/** Signals that provider transport exhausted a caller-owned deadline. */
public class AiProviderCallerDeadlineExceededException extends AiProviderException {
    public AiProviderCallerDeadlineExceededException() {
        super("AI provider attempt exceeded its caller deadline");
    }
}
