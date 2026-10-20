package ooo.klae.connex.backend.ai.assistant;

import java.util.Objects;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.AiAgentPrivacyBarrier;
import ooo.klae.connex.backend.beans.AiChatSession;
import ooo.klae.connex.backend.exceptions.AiPrivacyInitializationPendingException;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.mappers.AiChatMapper;

/** Durable generation and immutable taint checks shared by ordinary and mission executions. */
@Component
@RequiredArgsConstructor
public final class AiChatPrivacyGuard {
    private static final ThreadLocal<EgressExpectation> EGRESS_CHECK = new ThreadLocal<>();

    private final AiAssistantPrivacyService authority;

    /** Resolves server-owned organization identity for an active workspace. */
    public int organizationId(int workspaceId) {
        return authority.organizationId(workspaceId);
    }

    /** Reads current organization authority without creating missing coordination state. */
    public long currentGeneration(int workspaceId) {
        return authority.current(organizationId(workspaceId));
    }

    /** Retains current organization authority before admitting a new session. */
    public long retainCurrentOrganization(int workspaceId) {
        int organizationId = organizationId(workspaceId);
        long generation = authority.current(organizationId);
        authority.retain(organizationId, generation);
        return generation;
    }

    /** Retains original organization authority while a reader projects safe mission metadata. */
    public void retainOrganization(int workspaceId, long generation) {
        if (generation <= 0) {
            throw stale();
        }
        try {
            authority.retain(organizationId(workspaceId), generation);
        } catch (ConflictException exception) {
            throw stale();
        }
    }

    /** Captures provenance while the caller holds the session root, before loading input. */
    public AiChatPrivacyToken capture(AiChatMapper mapper, AiChatSession session) {
        int organizationId = organizationId(session.getWorkspaceId());
        long organizationGeneration = authority.current(organizationId);
        if (session.getOrganizationPrivacyGeneration() != organizationGeneration) {
            throw stale();
        }
        AiAgentPrivacyBarrier barrier = mapper.getPrivacyBarrier(session.getWorkspaceId());
        if (barrier == null) {
            throw new AiPrivacyInitializationPendingException();
        }
        if (barrier.getGeneration() <= 0
                || (session.getMissionTaintSeq() != null && barrier.isBlocked())) {
            throw stale();
        }
        return new AiChatPrivacyToken(session.getWorkspaceId(), session.getId(),
                barrier.getGeneration(), session.getMissionTaintSeq(), organizationId, organizationGeneration);
    }

    /** Revalidates without locks before input reads, dispatch, and publication. */
    public void requireCurrent(AiChatMapper mapper, AiChatPrivacyToken token) {
        if (token == null) {
            throw stale();
        }
        if (organizationId(token.workspaceId()) != token.organizationId()
                || authority.current(token.organizationId()) != token.organizationPrivacyGeneration()) {
            throw stale();
        }
        AiChatSession session = mapper.getPrivacySession(token.workspaceId(), token.sessionId());
        validate(token, session, mapper.getPrivacyBarrier(token.workspaceId()));
    }

    /** Takes the barrier S after content roots and keeps it through the caller's commit. */
    public void retain(AiChatMapper mapper, AiChatPrivacyToken token) {
        if (token == null) {
            throw stale();
        }
        if (organizationId(token.workspaceId()) != token.organizationId()) {
            throw stale();
        }
        try {
            authority.retain(token.organizationId(), token.organizationPrivacyGeneration());
        } catch (ConflictException exception) {
            throw stale();
        }
        AiAgentPrivacyBarrier barrier = mapper.lockPrivacyBarrierForShare(token.workspaceId());
        AiChatSession session = mapper.getPrivacySession(token.workspaceId(), token.sessionId());
        validate(token, session, barrier);
    }

    /** Runs a complete execution with the same input provenance at every provider attempt. */
    public <T> T execute(AiChatMapper mapper, AiChatPrivacyToken token, Supplier<T> action) {
        EgressExpectation previous = EGRESS_CHECK.get();
        Runnable check = () -> requireCurrent(mapper, token);
        check.run();
        EGRESS_CHECK.set(new EgressExpectation(token.organizationId(), () -> {
            if (authority.freshCurrent(token.organizationId()) != token.organizationPrivacyGeneration()) {
                throw stale();
            }
            check.run();
        }));
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
        EgressExpectation expected = EGRESS_CHECK.get();
        if (expected != null) {
            expected.check().run();
        }
    }

    /** Holds organization admission around the existing provider pipeline's actual attempt. */
    public static <T> T invokeAtEgress(Supplier<T> invocation) {
        EgressExpectation expected = EGRESS_CHECK.get();
        if (expected == null) {
            return invocation.get();
        }
        return AiAssistantPrivacyPublicationGuard.underPublicationRead(expected.organizationId(), () -> {
            expected.check().run();
            return invocation.get();
        });
    }

    private record EgressExpectation(int organizationId, Runnable check) {}

    private static void validate(AiChatPrivacyToken token, AiChatSession session,
            AiAgentPrivacyBarrier barrier) {
        if (session == null || barrier == null
                || session.getOrganizationPrivacyGeneration() != token.organizationPrivacyGeneration()
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
