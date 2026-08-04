package ooo.klae.connex.backend.services;

import java.time.Duration;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.WorkflowTriggerOutbox;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.WorkflowTriggerOutboxMapper;

/** Records bounded retry or dead-letter state after a durable trigger delivery rollback. */
@Service
@RequiredArgsConstructor
public class WorkflowTriggerOutboxFailureService {

    private final WorkflowTriggerOutboxMapper outboxMapper;
    private final WorkflowRuntimeProperties properties;

    @Transactional(
        propagation = Propagation.REQUIRES_NEW,
        isolation = Isolation.READ_COMMITTED)
    public void record(
            int workspaceId,
            long outboxId,
            String leaseOwner,
            RuntimeException failure) {
        WorkflowTriggerOutbox outbox = outboxMapper.getOwnedForUpdate(
            workspaceId, outboxId, leaseOwner);
        if (outbox == null) {
            return;
        }
        String code = failure instanceof WorkflowExecutionException workflowFailure
            ? workflowFailure.code()
            : "trigger_delivery_failed";
        if (permanent(failure)
                || outbox.getDeliveryAttemptCount()
                    >= properties.maxTriggerDeliveryAttempts()) {
            requireUpdated(outboxMapper.deadLetter(
                workspaceId, outboxId, leaseOwner, code));
            return;
        }
        requireUpdated(outboxMapper.releaseForRetry(
            workspaceId,
            outboxId,
            leaseOwner,
            retryDelay(outbox.getDeliveryAttemptCount()).toSeconds(),
            code));
    }

    private Duration retryDelay(int attempt) {
        int exponent = Math.max(0, Math.min(attempt - 1, 8));
        long baseSeconds = properties.retryBase().toSeconds();
        long bounded = Math.min(
            properties.retryMaximum().toSeconds(),
            Math.multiplyExact(baseSeconds, 1L << exponent));
        return Duration.ofSeconds(Math.max(1L, bounded));
    }

    private static boolean permanent(RuntimeException failure) {
        return failure instanceof WorkflowExecutionException
            || failure instanceof BadRequestException
            || failure instanceof ConflictException
            || failure instanceof ForbiddenException
            || failure instanceof ResourceNotFoundException;
    }

    private static void requireUpdated(int updated) {
        if (updated != 1) {
            throw new IllegalStateException("Durable trigger failure ownership was lost");
        }
    }
}
