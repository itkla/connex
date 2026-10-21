package ooo.klae.connex.backend.ai;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.mappers.AiStandingApprovalPolicyMapper;

/** Bounded control-only reconciliation; independent of AI, provider and execution switches. */
@Component
@RequiredArgsConstructor
public class AiStandingApprovalPolicyReconciler {
    private final AiStandingApprovalControlAccess access;
    private final AiStandingApprovalControlOperations operations;
    private final AiStandingApprovalPolicyMapper policies;
    private int afterOrgId;

    /** Examines at most one hundred missing policies per invocation and revisits earlier gaps. */
    @Scheduled(fixedDelayString = "${connex.ai.standing-approvals.reconcile-delay-ms:60000}")
    public synchronized void reconcile() {
        var missing = access.executeRead(() -> policies.findMissing(afterOrgId, 100));
        for (int orgId : missing) {
            access.execute(() -> {
                operations.provision(orgId);
                return null;
            });
            afterOrgId = orgId;
        }
        if (missing.size() < 100) {
            afterOrgId = 0;
        }
    }
}
