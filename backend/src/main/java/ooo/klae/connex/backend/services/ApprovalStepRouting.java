package ooo.klae.connex.backend.services;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import ooo.klae.connex.backend.beans.DocumentApprovalStep;
import ooo.klae.connex.backend.beans.DocumentApprovalStepAssignment;

/** Pure replay of frozen routes and appended assignment history, before separation of duties. */
final class ApprovalStepRouting {
    private static final String ANY_APPROVER = "any_approver";
    private static final String DELEGATION = "delegation";
    private static final String ESCALATION = "escalation";
    private static final String REASSIGNMENT = "reassignment";

    /** Resolves named routes without filtering membership or document-approval permissions. */
    Route namedRoute(DocumentApprovalStep step) {
        return resolve(step, Set.of(), userId -> true);
    }

    /** Applies permission filtering before delegation when resolving decision eligibility. */
    Route resolve(DocumentApprovalStep step, Set<Integer> anyApproverPool,
            Predicate<Integer> allowed) {
        List<DocumentApprovalStepAssignment> assignments = step.getAssignments();
        int round = assignments.stream()
            .filter(assignment -> REASSIGNMENT.equals(assignment.getAssignmentKind()))
            .mapToInt(DocumentApprovalStepAssignment::getAssignmentRound)
            .max().orElse(0);
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
        boolean anyApprover = declaration.stream()
            .anyMatch(declared -> ANY_APPROVER.equals(declared.approverKind()));
        Set<Integer> candidates = new LinkedHashSet<>();
        if (anyApprover) {
            candidates.addAll(anyApproverPool);
        } else {
            declaration.stream().map(Declared::userId)
                .filter(userId -> userId != null && allowed.test(userId))
                .forEach(candidates::add);
        }
        assignments.stream()
            .filter(assignment -> DELEGATION.equals(assignment.getAssignmentKind()))
            .sorted(Comparator.comparingInt(DocumentApprovalStepAssignment::getId))
            .forEach(assignment -> {
                Integer delegator = assignment.getDelegatedByUserId();
                if (delegator != null && candidates.remove(delegator)) {
                    Integer delegate = assignment.getUserId();
                    if (delegate != null && allowed.test(delegate)) {
                        candidates.add(delegate);
                    }
                }
            });
        return new Route(anyApprover, candidates);
    }

    /** Current route; named members remain distinct and deterministically ordered. */
    record Route(boolean anyApprover, Set<Integer> named) {
    }

    private record Declared(String approverKind, Integer userId) {
    }
}
