package ooo.klae.connex.backend.services;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

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

    @Test
    void dueIntervalChangeIsRetarget() {
        ApprovalPolicy current = policy(deadline(anyStep(31, "Manager"), 24, "expire"));
        ApprovalPolicy requested = policy(deadline(anyStep(31, "Manager"), 48, "expire"));

        assertEquals(PolicyChangeClass.RETARGET, classifier.classify(current, requested));
    }

    @Test
    void onExpiryChangeIsRetarget() {
        ApprovalPolicy current = policy(deadline(anyStep(31, "Manager"), 24, "expire"));
        ApprovalPolicy requested = policy(deadline(anyStep(31, "Manager"), 24, "escalate"));

        assertEquals(PolicyChangeClass.RETARGET, classifier.classify(current, requested));
    }

    @Test
    void dueConfigChangeDoesNotOverrideTighten() {
        ApprovalPolicy current = policy(deadline(namedStep(31, "Manager", 7), 24, "expire"));
        ApprovalPolicyStep tightened = deadline(namedStep(31, "Manager", 8), 48, "escalate");
        ApprovalPolicy requested = policy(tightened);

        assertEquals(PolicyChangeClass.TIGHTEN, classifier.classify(current, requested));
    }

    @Test
    void dueConfigChangeDoesNotOverrideLoosen() {
        ApprovalPolicy current = policy(deadline(namedStep(31, "Manager", 7), 24, "expire"));
        ApprovalPolicyStep loosened = deadline(namedStep(31, "Manager", 7, 8), 48, "escalate");
        ApprovalPolicy requested = policy(loosened);

        assertEquals(PolicyChangeClass.LOOSEN, classifier.classify(current, requested));
    }

    @Test
    void classificationFollowsTightenLoosenRetargetAndNonePrecedence() {
        ApprovalPolicy base = classificationPolicy();
        assertChange(PolicyChangeClass.TIGHTEN, "step added", base, after -> {
            List<ApprovalPolicyStep> steps = new ArrayList<>(after.getSteps());
            steps.add(anyStep(0, "Third"));
            after.setSteps(steps);
        });
        assertChange(PolicyChangeClass.TIGHTEN, "quorum raised", base,
            after -> after.getSteps().getFirst().setRequiredCount(2));
        assertChange(PolicyChangeClass.TIGHTEN, "named approver removed", base,
            after -> after.getSteps().getFirst().setApprovers(List.of(approver("user", 101))));
        assertChange(PolicyChangeClass.TIGHTEN, "any approver narrowed", base,
            after -> after.getSteps().get(1).setApprovers(List.of(approver("user", 101))));
        assertChange(PolicyChangeClass.TIGHTEN, "separation tightened", base,
            after -> after.setSeparationOfDuties("strict"));

        assertChange(PolicyChangeClass.LOOSEN, "step removed", base,
            after -> after.setSteps(List.of(after.getSteps().get(1))));
        ApprovalPolicy higherQuorum = copyPolicy(base);
        higherQuorum.getSteps().getFirst().setRequiredCount(2);
        assertChange(PolicyChangeClass.LOOSEN, "quorum lowered", higherQuorum,
            after -> after.getSteps().getFirst().setRequiredCount(1));
        assertChange(PolicyChangeClass.LOOSEN, "named approver added", base,
            after -> after.getSteps().getFirst().setApprovers(
                List.of(approver("user", 101), approver("user", 102), approver("user", 103))));
        assertChange(PolicyChangeClass.LOOSEN, "named approvers widened", base,
            after -> after.getSteps().getFirst().setApprovers(List.of(approver("any_approver", null))));
        assertChange(PolicyChangeClass.LOOSEN, "separation relaxed", base,
            after -> after.setSeparationOfDuties("off"));

        assertChange(PolicyChangeClass.RETARGET, "name", base,
            after -> after.setName("Retargeted"));
        assertChange(PolicyChangeClass.RETARGET, "active", base,
            after -> after.setActive(false));
        assertChange(PolicyChangeClass.RETARGET, "document type", base,
            after -> after.setDocumentType("contract"));
        assertChange(PolicyChangeClass.RETARGET, "currency", base,
            after -> after.setCurrency("USD"));
        assertChange(PolicyChangeClass.RETARGET, "minimum total", base,
            after -> after.setMinTotal(new BigDecimal("200")));
        assertChange(PolicyChangeClass.RETARGET, "minimum discount", base,
            after -> after.setMinDiscountPercent(new BigDecimal("20")));
        assertChange(PolicyChangeClass.RETARGET, "mode", base,
            after -> after.setMode("parallel"));
        assertChange(PolicyChangeClass.TIGHTEN, "tightening wins over retargeting", base, after -> {
            after.setName("Also retargeted");
            after.setSeparationOfDuties("strict");
        });
        assertEquals(PolicyChangeClass.NONE, classifier.classify(base, copyPolicy(base)));
    }

    private ApprovalPolicy classificationPolicy() {
        ApprovalPolicy policy = new ApprovalPolicy();
        policy.setName("Classification policy");
        policy.setActive(true);
        policy.setDocumentType("quote");
        policy.setCurrency("JPY");
        policy.setMinTotal(new BigDecimal("100.00"));
        policy.setMinDiscountPercent(new BigDecimal("10.000"));
        policy.setMode("sequential");
        policy.setSeparationOfDuties("requester");
        policy.setSteps(List.of(
            namedStep(11, "First", 101, 102),
            anyStep(12, "Second")));
        return policy;
    }

    private void assertChange(PolicyChangeClass expected, String label,
            ApprovalPolicy before, Consumer<ApprovalPolicy> mutation) {
        ApprovalPolicy after = copyPolicy(before);
        mutation.accept(after);
        assertEquals(expected, classifier.classify(before, after), label);
    }

    private ApprovalPolicy copyPolicy(ApprovalPolicy source) {
        ApprovalPolicy copy = new ApprovalPolicy();
        copy.setId(source.getId());
        copy.setWorkspaceId(source.getWorkspaceId());
        copy.setName(source.getName());
        copy.setActive(source.isActive());
        copy.setDocumentType(source.getDocumentType());
        copy.setCurrency(source.getCurrency());
        copy.setMinTotal(source.getMinTotal());
        copy.setMinDiscountPercent(source.getMinDiscountPercent());
        copy.setMode(source.getMode());
        copy.setSeparationOfDuties(source.getSeparationOfDuties());
        copy.setSteps(source.getSteps().stream().map(this::copyStep).toList());
        return copy;
    }

    private ApprovalPolicyStep copyStep(ApprovalPolicyStep source) {
        ApprovalPolicyStep copy = new ApprovalPolicyStep();
        copy.setId(source.getId());
        copy.setWorkspaceId(source.getWorkspaceId());
        copy.setPolicyId(source.getPolicyId());
        copy.setStepOrder(source.getStepOrder());
        copy.setName(source.getName());
        copy.setRequiredCount(source.getRequiredCount());
        copy.setDueIntervalHours(source.getDueIntervalHours());
        copy.setOnExpiry(source.getOnExpiry());
        copy.setApprovers(source.getApprovers().stream().map(this::copyApprover).toList());
        return copy;
    }

    private ApprovalStepApprover copyApprover(ApprovalStepApprover source) {
        ApprovalStepApprover copy = new ApprovalStepApprover();
        copy.setId(source.getId());
        copy.setWorkspaceId(source.getWorkspaceId());
        copy.setStepId(source.getStepId());
        copy.setApproverKind(source.getApproverKind());
        copy.setUserId(source.getUserId());
        return copy;
    }

    private ApprovalPolicyStep deadline(ApprovalPolicyStep step, Integer dueIntervalHours,
            String onExpiry) {
        step.setDueIntervalHours(dueIntervalHours);
        step.setOnExpiry(onExpiry);
        return step;
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
