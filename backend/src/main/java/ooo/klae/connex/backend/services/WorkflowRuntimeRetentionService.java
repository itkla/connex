package ooo.klae.connex.backend.services;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.mappers.WorkflowTriggerOutboxMapper;

/** Purges bounded terminal outbox metadata after its configured retention horizon. */
@Service
@RequiredArgsConstructor
public class WorkflowRuntimeRetentionService {

    private final WorkflowTriggerOutboxMapper outboxMapper;
    private final WorkflowRuntimeProperties properties;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int purge(int workspaceId) {
        LocalDateTime now = LocalDateTime.now();
        int limit = properties.maxRetentionDeletesPerWorkspace();
        int completed = outboxMapper.purgeCompletedBefore(
            workspaceId,
            now.minus(properties.completedOutboxRetention()),
            limit);
        int remaining = limit - completed;
        if (remaining <= 0) {
            return completed;
        }
        return completed + outboxMapper.purgeDeadBefore(
            workspaceId,
            now.minus(properties.deadOutboxRetention()),
            remaining);
    }
}
