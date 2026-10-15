package ooo.klae.connex.backend.services;

import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.ApprovalPolicyStep;
import ooo.klae.connex.backend.beans.DocumentApprovalStep;
import ooo.klae.connex.backend.exceptions.SoleDocumentApproverException;
import ooo.klae.connex.backend.mappers.ApprovalPolicyMapper;
import ooo.klae.connex.backend.mappers.DocumentApprovalMapper;

/** Refuses departure while a committed active route has only this distinct named approver. */
@Service
@RequiredArgsConstructor
public class DepartureApprovalGuard {
    private final ApprovalPolicyMapper policyMapper;
    private final DocumentApprovalMapper approvalMapper;

    /** Checks fresh non-locking routes in the workspace being left. */
    public void assertCanDepart(int workspaceId, int userId) {
        assertCanDepart(userId, policyMapper.findActiveStepsForDeparture(workspaceId, userId),
            approvalMapper.findOpenStepsForDeparture(workspaceId, userId));
    }

    /** Checks fresh non-locking routes throughout the currently routed tenant catalog. */
    public void assertCanErase(int userId) {
        assertCanDepart(userId, policyMapper.findActiveStepsForDepartureAnywhere(userId),
            approvalMapper.findOpenStepsForDepartureAnywhere(userId));
    }

    private void assertCanDepart(int userId, List<ApprovalPolicyStep> policies,
            List<DocumentApprovalStep> requests) {
        ApprovalStepRouting routing = new ApprovalStepRouting();
        for (ApprovalPolicyStep policy : policies) {
            DocumentApprovalStep step = new DocumentApprovalStep();
            step.setApprovers(policy.getApprovers());
            requireReassignment(userId, routing.namedRoute(step));
        }
        for (DocumentApprovalStep step : requests) {
            if ("pending".equals(step.getStatus()) || "active".equals(step.getStatus())) {
                requireReassignment(userId, routing.namedRoute(step));
            }
        }
    }

    private void requireReassignment(int userId, ApprovalStepRouting.Route route) {
        if (!route.anyApprover() && route.named().equals(Set.of(userId))) {
            throw new SoleDocumentApproverException(
                "Member is the sole named approver on an active policy or pending approval; reassign first");
        }
    }
}
