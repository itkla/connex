package ooo.klae.connex.backend.services;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.beans.ApprovalStepApprover;
import ooo.klae.connex.backend.beans.DocumentApprovalDecision;
import ooo.klae.connex.backend.beans.DocumentApprovalStep;
import ooo.klae.connex.backend.beans.DocumentApprovalStepAssignment;
import ooo.klae.connex.backend.beans.User;

class ApprovalStepEligibilityTest {

    private final ApprovalStepEligibility eligibility = new ApprovalStepEligibility();
    private final AtomicInteger nextAssignmentId = new AtomicInteger(1);

    private DocumentApprovalService.ApproverPool pool(int... approverIds) {
        List<User> members = new ArrayList<>();
        Set<Integer> approvers = new LinkedHashSet<>();
        for (int id : approverIds) {
            User member = new User();
            member.setId(id);
            members.add(member);
            approvers.add(id);
        }
        return new DocumentApprovalService.ApproverPool(members, approvers);
    }

    private DocumentApprovalStep step(int requiredCount, ApprovalStepApprover... frozen) {
        DocumentApprovalStep step = new DocumentApprovalStep();
        step.setId(10);
        step.setRequiredCount(requiredCount);
        step.setStatus("active");
        step.setApprovers(List.of(frozen));
        step.setAssignments(new ArrayList<>());
        step.setDecisions(new ArrayList<>());
        return step;
    }

    private ApprovalStepApprover named(int userId) {
        ApprovalStepApprover approver = new ApprovalStepApprover();
        approver.setApproverKind("user");
        approver.setUserId(userId);
        return approver;
    }

    private ApprovalStepApprover anyApprover() {
        ApprovalStepApprover approver = new ApprovalStepApprover();
        approver.setApproverKind("any_approver");
        return approver;
    }

    private DocumentApprovalStepAssignment assignment(DocumentApprovalStep step, String kind,
            int round, String approverKind, Integer userId, Integer delegatedBy) {
        DocumentApprovalStepAssignment assignment = new DocumentApprovalStepAssignment();
        assignment.setId(nextAssignmentId.getAndIncrement());
        assignment.setStepId(step.getId());
        assignment.setAssignmentKind(kind);
        assignment.setAssignmentRound(round);
        assignment.setApproverKind(approverKind);
        assignment.setUserId(userId);
        assignment.setDelegatedByUserId(delegatedBy);
        step.getAssignments().add(assignment);
        return assignment;
    }

    private void approve(DocumentApprovalStep step, int userId) {
        DocumentApprovalDecision decision = new DocumentApprovalDecision();
        decision.setStepId(step.getId());
        decision.setDecision("approved");
        decision.setDecidedBy(userId);
        step.getDecisions().add(decision);
    }

    @Test
    void frozenSetIsTheBaseWhenThereHasBeenNoReassignment() {
        DocumentApprovalStep step = step(1, named(2), named(3));

        ApprovalStepEligibility.EffectiveApprovers effective =
            eligibility.resolve(step, pool(2, 3, 4), Set.of());

        assertFalse(effective.anyApprover());
        assertEquals(List.of(2, 3), List.copyOf(effective.eligible()));
    }

    @Test
    void theHighestReassignmentRoundReplacesTheFrozenSet() {
        DocumentApprovalStep step = step(1, named(2));
        assignment(step, "reassignment", 1, "user", 3, null);
        assignment(step, "reassignment", 2, "user", 4, null);

        ApprovalStepEligibility.EffectiveApprovers effective =
            eligibility.resolve(step, pool(2, 3, 4), Set.of());

        assertEquals(List.of(4), List.copyOf(effective.eligible()));
    }

    @Test
    void escalationRowsBelowTheCurrentRoundAreDropped() {
        DocumentApprovalStep step = step(1, named(2));
        assignment(step, "escalation", 0, "user", 3, null);
        assignment(step, "reassignment", 1, "user", 4, null);

        ApprovalStepEligibility.EffectiveApprovers effective =
            eligibility.resolve(step, pool(2, 3, 4), Set.of());

        assertEquals(List.of(4), List.copyOf(effective.eligible()));
    }

    @Test
    void escalationRowsAtOrAboveTheCurrentRoundWiden() {
        DocumentApprovalStep step = step(1, named(2));
        assignment(step, "reassignment", 1, "user", 4, null);
        assignment(step, "escalation", 1, "user", 5, null);

        ApprovalStepEligibility.EffectiveApprovers effective =
            eligibility.resolve(step, pool(2, 4, 5), Set.of());

        assertEquals(List.of(4, 5), List.copyOf(effective.eligible()));
    }

    @Test
    void delegationRemovesTheDelegatorAndAddsTheDelegate() {
        DocumentApprovalStep step = step(1, named(2));
        assignment(step, "delegation", 0, "user", 3, 2);

        ApprovalStepEligibility.EffectiveApprovers effective =
            eligibility.resolve(step, pool(2, 3), Set.of());

        assertEquals(List.of(3), List.copyOf(effective.eligible()));
    }

    @Test
    void delegationIsInertWhenTheDelegatorIsNotInTheCurrentSet() {
        DocumentApprovalStep step = step(1, named(2));
        assignment(step, "delegation", 0, "user", 3, 2);
        assignment(step, "reassignment", 1, "user", 4, null);

        ApprovalStepEligibility.EffectiveApprovers effective =
            eligibility.resolve(step, pool(2, 3, 4), Set.of());

        assertEquals(List.of(4), List.copyOf(effective.eligible()));
    }

    @Test
    void delegationOnAnAnyApproverStepOnlyRemovesTheDelegator() {
        DocumentApprovalStep step = step(1, anyApprover());
        assignment(step, "delegation", 0, "user", 3, 2);

        ApprovalStepEligibility.EffectiveApprovers effective =
            eligibility.resolve(step, pool(2, 3, 4), Set.of());

        assertTrue(effective.anyApprover());
        assertEquals(List.of(3, 4), List.copyOf(effective.eligible()));
    }

    @Test
    void aDelegateWithoutDocumentApproveIsNotAdded() {
        DocumentApprovalStep step = step(1, named(2));
        assignment(step, "delegation", 0, "user", 3, 2);

        ApprovalStepEligibility.EffectiveApprovers effective =
            eligibility.resolve(step, pool(2), Set.of());

        assertTrue(effective.eligible().isEmpty());
    }

    @Test
    void separationExclusionsApplyAfterDelegation() {
        DocumentApprovalStep step = step(1, named(2));
        assignment(step, "delegation", 0, "user", 3, 2);

        ApprovalStepEligibility.EffectiveApprovers effective =
            eligibility.resolve(step, pool(2, 3), Set.of(3));

        assertTrue(effective.eligible().isEmpty());
    }

    @Test
    void alreadyApprovedDecidersLeaveTheUndecidedSetButNotTheEligibleSet() {
        DocumentApprovalStep step = step(2, named(2), named(3));
        approve(step, 2);

        ApprovalStepEligibility.EffectiveApprovers effective =
            eligibility.resolve(step, pool(2, 3), Set.of());

        assertEquals(List.of(2, 3), List.copyOf(effective.eligible()));
        assertEquals(List.of(3), List.copyOf(effective.undecided()));
        assertEquals(1, effective.remainingNeeded());
    }

    @Test
    void remainingNeededNeverGoesNegative() {
        DocumentApprovalStep step = step(1, named(2), named(3));
        approve(step, 2);
        approve(step, 3);

        ApprovalStepEligibility.EffectiveApprovers effective =
            eligibility.resolve(step, pool(2, 3), Set.of());

        assertEquals(0, effective.remainingNeeded());
        assertTrue(effective.undecided().isEmpty());
    }

    @Test
    void resolutionIsDeterministicAcrossRepeatedCalls() {
        DocumentApprovalStep step = step(1, named(4), named(2), named(3));
        assignment(step, "delegation", 0, "user", 5, 2);

        List<Integer> first = List.copyOf(
            eligibility.resolve(step, pool(2, 3, 4, 5), Set.of()).eligible());
        List<Integer> second = List.copyOf(
            eligibility.resolve(step, pool(2, 3, 4, 5), Set.of()).eligible());

        assertEquals(List.of(4, 3, 5), first);
        assertEquals(first, second);
    }
}
