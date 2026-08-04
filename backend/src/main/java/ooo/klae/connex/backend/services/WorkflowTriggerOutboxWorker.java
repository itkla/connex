package ooo.klae.connex.backend.services;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/** Executes one leased outbox target and delegates durable failure recording. */
@Service
@RequiredArgsConstructor
public class WorkflowTriggerOutboxWorker {

    private final WorkflowTriggerOutboxDeliveryService deliveryService;
    private final WorkflowTriggerOutboxFailureService failureService;

    public void process(int workspaceId, long outboxId, String leaseOwner) {
        try {
            deliveryService.deliver(workspaceId, outboxId, leaseOwner);
        } catch (RuntimeException failure) {
            failureService.record(workspaceId, outboxId, leaseOwner, failure);
        }
    }
}
