package ooo.klae.connex.backend.ai.mission;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.ai.lease.AiRunLeaseKey;
import ooo.klae.connex.backend.ai.lease.AiRunLeaseSubject;
import ooo.klae.connex.backend.ai.lease.AiRunLeaseSubjectHandler;
import ooo.klae.connex.backend.mappers.AiAgentRunMapper;

/** Owns mission liveness and recovery; mission turns never acquire CHAT_TURN leases. */
@Component
@RequiredArgsConstructor
public class AiAgentRunLeaseSubjectHandler implements AiRunLeaseSubjectHandler {
    private final AiAgentRunMapper runs;
    private final AiAgentRunPersistenceService persistence;

    @Override
    public AiRunLeaseSubject subject() {
        return AiRunLeaseSubject.AGENT_RUN;
    }

    @Override
    public boolean isSubjectRunning(int workspaceId, long subjectId) {
        var run = runs.getRun(workspaceId, Math.toIntExact(subjectId));
        return run != null && "running".equals(run.getStatus());
    }

    @Override
    public boolean settleOrphan(AiRunLeaseKey key, long expectedEpoch) {
        var run = runs.getRun(key.workspaceId(), Math.toIntExact(key.subjectId()));
        return run != null && persistence.settleOrphan(run, key, expectedEpoch);
    }
}
