package ooo.klae.connex.backend.services;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import ooo.klae.connex.backend.beans.DocumentApprovalDecision;
import ooo.klae.connex.backend.beans.DocumentApprovalStep;
import ooo.klae.connex.backend.beans.DocumentApprovalStepAssignment;

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
    private static final String ANY_APPROVER = "any_approver";
    private static final String APPROVED = "approved";
    private static final String DELEGATION = "delegation";
    private static final String ESCALATION = "escalation";
    private static final String REASSIGNMENT = "reassignment";

    /**
     * Replays one step's appended approver facts over its frozen snapshot.
     *
     * @param step                  the frozen step, with its approvers, assignments, and decisions
     * @param pool                  the workspace's members and the subset that may approve today
     * @param separationExclusions  members the request's separation-of-duties rule excludes
     */
    EffectiveApprovers resolve(DocumentApprovalStep step, DocumentApprovalService.ApproverPool pool,
            Set<Integer> separationExclusions) {
        List<DocumentApprovalStepAssignment> assignments = step.getAssignments();
        int round = assignments.stream()
            .filter(assignment -> REASSIGNMENT.equals(assignment.getAssignmentKind()))
            .mapToInt(DocumentApprovalStepAssignment::getAssignmentRound)
            .max()
            .orElse(0);
        List<Declared> declaration = declaredApprovers(step, assignments, round);
        boolean anyApprover = declaration.stream()
            .anyMatch(declared -> ANY_APPROVER.equals(declared.approverKind()));
        Set<Integer> candidates = new LinkedHashSet<>();
        if (anyApprover) {
            candidates.addAll(pool.approvers());
        } else {
            declaration.stream()
                .map(Declared::userId)
                .filter(userId -> userId != null && pool.approvers().contains(userId))
                .forEach(candidates::add);
        }
        assignments.stream()
            .filter(assignment -> DELEGATION.equals(assignment.getAssignmentKind()))
            .sorted(Comparator.comparingInt(DocumentApprovalStepAssignment::getId))
            .forEach(assignment -> applyDelegation(candidates, pool, assignment));
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

    private List<Declared> declaredApprovers(DocumentApprovalStep step,
            List<DocumentApprovalStepAssignment> assignments, int round) {
        List<Declared> declaration = new ArrayList<>();
        if (round > 0) {
            assignments.stream()
                .filter(assignment -> REASSIGNMENT.equals(assignment.getAssignmentKind())
                    && assignment.getAssignmentRound() == round)
                .forEach(assignment -> declaration.add(
                    new Declared(assignment.getApproverKind(), assignment.getUserId())));
        } else {
            step.getApprovers().forEach(approver -> declaration.add(
                new Declared(approver.getApproverKind(), approver.getUserId())));
        }
        assignments.stream()
            .filter(assignment -> ESCALATION.equals(assignment.getAssignmentKind())
                && assignment.getAssignmentRound() >= round)
            .forEach(assignment -> declaration.add(
                new Declared(assignment.getApproverKind(), assignment.getUserId())));
        return declaration;
    }

    private void applyDelegation(Set<Integer> candidates, DocumentApprovalService.ApproverPool pool,
            DocumentApprovalStepAssignment delegation) {
        Integer delegator = delegation.getDelegatedByUserId();
        if (delegator == null || !candidates.remove(delegator)) {
            return;
        }
        Integer delegate = delegation.getUserId();
        if (delegate != null && pool.approvers().contains(delegate)) {
            candidates.add(delegate);
        }
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

    /** One entry of the step's current approver declaration, before pool and delegation filtering. */
    private record Declared(String approverKind, Integer userId) {
    }
}
