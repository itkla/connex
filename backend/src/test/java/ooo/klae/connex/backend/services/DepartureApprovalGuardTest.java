package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.beans.ApprovalPolicyStep;
import ooo.klae.connex.backend.beans.ApprovalStepApprover;
import ooo.klae.connex.backend.beans.DocumentApprovalStep;
import ooo.klae.connex.backend.beans.DocumentApprovalStepAssignment;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.mappers.ApprovalPolicyMapper;
import ooo.klae.connex.backend.mappers.DocumentApprovalMapper;

@ExtendWith(MockitoExtension.class)
class DepartureApprovalGuardTest {
    @Mock private ApprovalPolicyMapper policyMapper;
    @Mock private DocumentApprovalMapper approvalMapper;
    @InjectMocks private DepartureApprovalGuard guard;

    @Test
    void soleDistinctPolicyApproverBlocksEvenWithDuplicateDeclarations() {
        ApprovalPolicyStep step = new ApprovalPolicyStep();
        step.setApprovers(List.of(named(9), named(9)));
        when(policyMapper.findActiveStepsForDeparture(7, 9)).thenReturn(List.of(step));

        assertThrows(ConflictException.class, () -> guard.assertCanDepart(7, 9));
    }

    @Test
    void multiplePolicyApproversAndAnyApproverRoutesAllowDeparture() {
        ApprovalPolicyStep multiple = new ApprovalPolicyStep();
        multiple.setApprovers(List.of(named(9), named(11)));
        ApprovalPolicyStep any = new ApprovalPolicyStep();
        any.setApprovers(List.of(named(9), anyApprover()));
        when(policyMapper.findActiveStepsForDeparture(7, 9)).thenReturn(List.of(multiple, any));

        assertDoesNotThrow(() -> guard.assertCanDepart(7, 9));
    }

    @Test
    void futureSequentialStepBlocksAndCatalogErasureUsesTheSameGuard() {
        DocumentApprovalStep future = step("pending", named(9));
        when(approvalMapper.findOpenStepsForDepartureAnywhere(9)).thenReturn(List.of(future));

        assertThrows(ConflictException.class, () -> guard.assertCanErase(9));
    }

    @Test
    void multipleNamedFrozenApproversAllowDepartureRegardlessOfRequiredCount() {
        DocumentApprovalStep step = step("active", named(9), named(11));
        step.setRequiredCount(2);
        when(approvalMapper.findOpenStepsForDeparture(7, 9)).thenReturn(List.of(step));

        assertDoesNotThrow(() -> guard.assertCanDepart(7, 9));
    }

    @Test
    void latestReassignmentReplacesFrozenAndEarlierRounds() {
        DocumentApprovalStep step = step("active", named(9));
        step.setAssignments(List.of(assignment(1, "reassignment", 1, 9, null),
            assignment(2, "reassignment", 2, 11, null)));
        when(approvalMapper.findOpenStepsForDeparture(7, 9)).thenReturn(List.of(step));

        assertDoesNotThrow(() -> guard.assertCanDepart(7, 9));
    }

    @Test
    void reassignmentCanNameTheDepartingUserWithoutAFrozenReference() {
        DocumentApprovalStep step = step("active", named(11));
        step.setAssignments(List.of(assignment(1, "reassignment", 1, 9, null)));
        when(approvalMapper.findOpenStepsForDeparture(7, 9)).thenReturn(List.of(step));

        assertThrows(ConflictException.class, () -> guard.assertCanDepart(7, 9));
    }

    @Test
    void escalationBelowLatestRoundIsInert() {
        DocumentApprovalStep step = step("active", named(11));
        step.setAssignments(List.of(assignment(1, "escalation", 0, null, null),
            assignment(2, "reassignment", 1, 9, null)));
        when(approvalMapper.findOpenStepsForDeparture(7, 9)).thenReturn(List.of(step));

        assertThrows(ConflictException.class, () -> guard.assertCanDepart(7, 9));
    }

    @Test
    void escalationAtLatestRoundWidensToAnyApprover() {
        DocumentApprovalStep step = step("active", named(9));
        step.setAssignments(List.of(assignment(1, "reassignment", 1, 9, null),
            assignment(2, "escalation", 1, null, null)));
        when(approvalMapper.findOpenStepsForDeparture(7, 9)).thenReturn(List.of(step));

        assertDoesNotThrow(() -> guard.assertCanDepart(7, 9));
    }

    @Test
    void delegationAwayUnblocksAndDelegationToDepartingUserBlocks() {
        DocumentApprovalStep step = step("active", named(9));
        step.setAssignments(List.of(assignment(1, "delegation", 0, 11, 9)));
        when(approvalMapper.findOpenStepsForDeparture(7, 9)).thenReturn(List.of(step));
        assertDoesNotThrow(() -> guard.assertCanDepart(7, 9));

        step.setApprovers(List.of(named(11)));
        step.setAssignments(List.of(assignment(1, "delegation", 0, 9, 11)));
        assertThrows(ConflictException.class, () -> guard.assertCanDepart(7, 9));
    }

    @Test
    void anInertDelegationCannotAddTheDepartingUser() {
        DocumentApprovalStep step = step("active", named(11));
        step.setAssignments(List.of(assignment(1, "delegation", 0, 9, 12)));
        when(approvalMapper.findOpenStepsForDeparture(7, 9)).thenReturn(List.of(step));

        assertDoesNotThrow(() -> guard.assertCanDepart(7, 9));
    }

    @Test
    void terminalAndAnyApproverStepsNeverBlock() {
        when(approvalMapper.findOpenStepsForDeparture(7, 9)).thenReturn(List.of(
            step("approved", named(9)), step("rejected", named(9)), step("cancelled", named(9)),
            step("unsatisfiable", named(9)), step("expired", named(9)),
            step("active", named(9), anyApprover())));

        assertDoesNotThrow(() -> guard.assertCanDepart(7, 9));
    }

    @Test
    void namedRoutingKeepsPermissionLostDelegatorDistinctFromEligibility() {
        DocumentApprovalStep step = step("active", named(9));
        step.setAssignments(List.of(assignment(1, "delegation", 0, 11, 9)));
        ApprovalStepRouting routing = new ApprovalStepRouting();

        assertEquals(Set.of(11), routing.namedRoute(step).named());
        assertEquals(Set.of(), routing.resolve(step, Set.of(11), Set.of(11)::contains).named());
    }

    private DocumentApprovalStep step(String status, ApprovalStepApprover... approvers) {
        DocumentApprovalStep step = new DocumentApprovalStep();
        step.setStatus(status);
        step.setApprovers(List.of(approvers));
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

    private DocumentApprovalStepAssignment assignment(int id, String kind, int round,
            Integer userId, Integer delegator) {
        DocumentApprovalStepAssignment assignment = new DocumentApprovalStepAssignment();
        assignment.setId(id);
        assignment.setAssignmentKind(kind);
        assignment.setAssignmentRound(round);
        assignment.setApproverKind(userId == null ? "any_approver" : "user");
        assignment.setUserId(userId);
        assignment.setDelegatedByUserId(delegator);
        return assignment;
    }
}
