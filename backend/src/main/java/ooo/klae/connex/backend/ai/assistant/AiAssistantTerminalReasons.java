package ooo.klae.connex.backend.ai.assistant;

import java.util.Set;

/** Stable assistant turn terminal reasons shared by the loop, generation callbacks, and durable state. */
public final class AiAssistantTerminalReasons {
    /** The requester lost access to the session, the workspace, or a record the turn was reading. */
    public static final String ACCESS_REVOKED = "access_revoked";
    /** The workspace processing-restriction epoch advanced while the turn was still running. */
    public static final String RESTRICTIONS_CHANGED = "restrictions_changed";
    /**
     * The organization's configured model declares a context window below
     * {@link AiAssistantPromptBudget#ASSISTANT_MIN_CONTEXT_TOKENS}.
     *
     * <p>Refused before provider egress and decided per turn, so an administrator who configures a
     * larger model fixes it without restarting anything. Asking the same question again cannot help.
     */
    public static final String CONTEXT_WINDOW_TOO_SMALL = "context_window_too_small";

    /**
     * The terminal reasons that withdraw the requester's authorization to read what the turn
     * produced.
     *
     * <p>A durable partial answer carries no resource metadata, so a read cannot re-authorize it per
     * viewer the way a resolved transcript message is re-authorized. Terminating for one of these
     * reasons therefore purges the partial rather than retaining it.
     */
    public static final Set<String> AUTHORIZATION_WITHDRAWN =
            Set.of(ACCESS_REVOKED, RESTRICTIONS_CHANGED);

    private AiAssistantTerminalReasons() {
    }

    /**
     * Returns whether a terminal reason withdrew the requester's authorization to read the turn.
     * @param reason stable terminal reason, or null
     * @return true when the turn's durable partial answer must not survive the transition
     */
    public static boolean withdrawsAuthorization(String reason) {
        return reason != null && AUTHORIZATION_WITHDRAWN.contains(reason);
    }
}
