package ooo.klae.connex.backend.services;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.beans.ApprovalPolicy;
import ooo.klae.connex.backend.beans.ApprovalPolicyStep;
import ooo.klae.connex.backend.beans.ApprovalStepApprover;

class ApprovalPolicyChangeClassifierTest {

    private final ApprovalPolicyChangeClassifier classifier =
        new ApprovalPolicyChangeClassifier();

    @Test
    void stableIdsKeepAnIdenticalRetryUnchanged() {
        ApprovalPolicy current = policy(
            anyStep(31, "Manager"),
            namedStep(32, "Finance", 7));
        ApprovalPolicy retry = policy(
            anyStep(31, "Manager"),
            namedStep(32, "Finance", 7));

        assertEquals(PolicyChangeClass.NONE, classifier.classify(current, retry));
    }

    @Test
    void leadingStepRemovalIsLoosening() {
        ApprovalPolicy current = policy(
            anyStep(31, "Manager"),
            namedStep(32, "Finance", 7));
        ApprovalPolicy requested = policy(namedStep(32, "Finance", 7));

        assertEquals(PolicyChangeClass.LOOSEN, classifier.classify(current, requested));
    }

    @Test
    void replacementWithANewEquivalentStepIsTightening() {
        ApprovalPolicy current = policy(
            anyStep(31, "Manager"),
            namedStep(32, "Finance", 7));
        ApprovalPolicy requested = policy(
            anyStep(31, "Manager"),
            namedStep(0, "Finance", 7));

        assertEquals(PolicyChangeClass.TIGHTEN, classifier.classify(current, requested));
    }

    @Test
    void reorderingStableIdsRetargetsASequentialChain() {
        ApprovalPolicy current = policy(
            anyStep(31, null),
            namedStep(32, null, 7));
        ApprovalPolicy reordered = policy(
            namedStep(32, null, 7),
            anyStep(31, null));

        assertEquals(PolicyChangeClass.RETARGET, classifier.classify(current, reordered));
    }

    @Test
    void reorderingStableIdsDoesNotChangeAParallelChain() {
        ApprovalPolicy current = policy("parallel",
            anyStep(31, null),
            namedStep(32, null, 7));
        ApprovalPolicy reordered = policy("parallel",
            namedStep(32, null, 7),
            anyStep(31, null));

        assertEquals(PolicyChangeClass.NONE, classifier.classify(current, reordered));
    }

    private ApprovalPolicy policy(ApprovalPolicyStep... steps) {
        return policy("sequential", steps);
    }

    private ApprovalPolicy policy(String mode, ApprovalPolicyStep... steps) {
        ApprovalPolicy policy = new ApprovalPolicy();
        policy.setName("Policy");
        policy.setActive(true);
        policy.setMode(mode);
        policy.setSeparationOfDuties("requester");
        policy.setSteps(List.of(steps));
        return policy;
    }

    private ApprovalPolicyStep anyStep(int id, String name) {
        return step(id, name, approver("any_approver", null));
    }

    private ApprovalPolicyStep namedStep(int id, String name, Integer... userIds) {
        return step(id, name, Arrays.stream(userIds)
            .map(userId -> approver("user", userId))
            .toArray(ApprovalStepApprover[]::new));
    }

    private ApprovalPolicyStep step(int id, String name, ApprovalStepApprover... approvers) {
        ApprovalPolicyStep step = new ApprovalPolicyStep();
        step.setId(id);
        step.setName(name);
        step.setRequiredCount(1);
        step.setApprovers(List.of(approvers));
        return step;
    }

    private ApprovalStepApprover approver(String kind, Integer userId) {
        ApprovalStepApprover approver = new ApprovalStepApprover();
        approver.setApproverKind(kind);
        approver.setUserId(userId);
        return approver;
    }
}
