package ooo.klae.connex.backend.ai.assistant;

import java.util.Objects;
import java.util.function.Supplier;

import ooo.klae.connex.backend.beans.AiAgentPrivacyBarrier;
import ooo.klae.connex.backend.beans.AiChatSession;
import ooo.klae.connex.backend.exceptions.AiPrivacyInitializationPendingException;
import ooo.klae.connex.backend.mappers.AiChatMapper;

/** Durable generation and immutable taint checks shared by ordinary and mission executions. */
public final class AiChatPrivacyGuard {
    private static final ThreadLocal<Runnable> EGRESS_CHECK = new ThreadLocal<>();

    private AiChatPrivacyGuard() {}

    /** Captures provenance while the caller holds the session root, before loading input. */
    public static AiChatPrivacyToken capture(AiChatMapper mapper, AiChatSession session) {
        AiAgentPrivacyBarrier barrier = mapper.getPrivacyBarrier(session.getWorkspaceId());
        if (barrier == null) {
            throw new AiPrivacyInitializationPendingException();
        }
        if (barrier.getGeneration() <= 0
                || (session.getMissionTaintSeq() != null && barrier.isBlocked())) {
            throw stale();
        }
        return new AiChatPrivacyToken(session.getWorkspaceId(), session.getId(),
                barrier.getGeneration(), session.getMissionTaintSeq());
    }

    /** Revalidates without locks before input reads, dispatch, and publication. */
    public static void requireCurrent(AiChatMapper mapper, AiChatPrivacyToken token) {
        if (token == null) {
            throw stale();
        }
        AiChatSession session = mapper.getPrivacySession(token.workspaceId(), token.sessionId());
        validate(token, session, mapper.getPrivacyBarrier(token.workspaceId()));
    }

    /** Takes the barrier S after content roots and keeps it through the caller's commit. */
    public static void retain(AiChatMapper mapper, AiChatPrivacyToken token) {
        if (token == null) {
            throw stale();
        }
        AiChatSession session = mapper.getPrivacySession(token.workspaceId(), token.sessionId());
        validate(token, session, mapper.lockPrivacyBarrierForShare(token.workspaceId()));
    }

    /** Runs a complete execution with the same input provenance at every provider attempt. */
    public static <T> T execute(AiChatMapper mapper, AiChatPrivacyToken token, Supplier<T> action) {
        Runnable previous = EGRESS_CHECK.get();
        Runnable check = () -> requireCurrent(mapper, token);
        check.run();
        EGRESS_CHECK.set(check);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                EGRESS_CHECK.remove();
            } else {
                EGRESS_CHECK.set(previous);
            }
        }
    }

    /** Called inside the local restriction egress fence; never acquires content roots. */
    public static void requireEgressCurrent() {
        Runnable check = EGRESS_CHECK.get();
        if (check != null) {
            check.run();
        }
    }

    private static void validate(AiChatPrivacyToken token, AiChatSession session,
            AiAgentPrivacyBarrier barrier) {
        if (session == null || barrier == null
                || !Objects.equals(token.taintSeqAtAdmission(), session.getMissionTaintSeq())
                || (session.getMissionTaintSeq() != null
                    && (barrier.isBlocked() || barrier.getGeneration() != token.privacyGeneration()))) {
            throw stale();
        }
    }

    private static AiAssistantLoopException stale() {
        return new AiAssistantLoopException("restrictions_changed", "restrictions_changed");
    }
}
