package ooo.klae.connex.backend.services;

import java.util.LinkedHashSet;
import java.util.Set;

import ooo.klae.connex.backend.beans.DocumentApprovalDecision;
import ooo.klae.connex.backend.beans.DocumentApprovalStep;

/**
 * Resolves who may currently decide one frozen approval step. This is the single place the frozen
 * approver snapshot and the appended delegation, escalation, and reassignment facts are combined,
 * so authorization, satisfiability projection, notification fan-out, and the My Work inbox can
 * never disagree about the same step.
 *
 * <p>The replay is: take the highest reassignment round as the base declaration (or the frozen
 * snapshot when no reassignment has happened), union in every escalation at or above that round,
 * intersect named approvers with the members who can approve documents today, replay delegations in
 * insertion order, and finally remove the request's separation-of-duties exclusions. A delegation is
 * inert unless its delegator is still in the set, so a delegator who was reassigned out, already
 * delegated, or lost the permission never smuggles a delegate in.
 *
 * <p>Every returned set preserves a deterministic iteration order so the projected DTO is stable
 * across repeated reads.
 */
final class ApprovalStepEligibility {
    private static final String APPROVED = "approved";

    /**
     * Replays one step's appended approver facts over its frozen snapshot.
     *
     * @param step                  the frozen step, with its approvers, assignments, and decisions
     * @param pool                  the workspace's members and the subset that may approve today
     * @param separationExclusions  members the request's separation-of-duties rule excludes
     */
    EffectiveApprovers resolve(DocumentApprovalStep step, DocumentApprovalService.ApproverPool pool,
            Set<Integer> separationExclusions) {
        ApprovalStepRouting.Route route = new ApprovalStepRouting().resolve(
            step, pool.approvers(), pool.approvers()::contains);
        boolean anyApprover = route.anyApprover();
        Set<Integer> candidates = new LinkedHashSet<>(route.named());
        candidates.removeAll(separationExclusions);
        Set<Integer> approvedBy = new LinkedHashSet<>();
        step.getDecisions().stream()
            .filter(decision -> APPROVED.equals(decision.getDecision()))
            .map(DocumentApprovalDecision::getDecidedBy)
            .forEach(approvedBy::add);
        Set<Integer> undecided = new LinkedHashSet<>(candidates);
        undecided.removeAll(approvedBy);
        return new EffectiveApprovers(anyApprover, candidates, undecided,
            Math.max(0, step.getRequiredCount() - approvedBy.size()));
    }

    /**
     * The current approver resolution for one step.
     *
     * @param anyApprover     the step currently resolves to the whole approver pool
     * @param eligible        members who may decide, ignoring who has already decided
     * @param undecided       eligible members who have not yet approved this step
     * @param remainingNeeded approvals still outstanding before the step passes
     */
    record EffectiveApprovers(
            boolean anyApprover,
            Set<Integer> eligible,
            Set<Integer> undecided,
            int remainingNeeded) {
    }
}
