package ooo.klae.connex.backend.ai.assistant;

/** A trusted pending proposal reached its original review deadline before mutation admission. */
public class AiAssistantProposalExpiredException extends RuntimeException {
    public AiAssistantProposalExpiredException() {
        super("Assistant proposal review window has expired");
    }
}
