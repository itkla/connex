package ooo.klae.connex.backend.services;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import ooo.klae.connex.backend.beans.ApprovalPolicy;
import ooo.klae.connex.backend.beans.ApprovalPolicyStep;
import ooo.klae.connex.backend.beans.ApprovalStepApprover;

/** Classifies the semantic effect of an approval-policy edit using the product precedence. */
final class ApprovalPolicyChangeClassifier {
    private static final String ANY_APPROVER = "any_approver";

    PolicyChangeClass classify(ApprovalPolicy before, ApprovalPolicy after) {
        boolean tighten = separationRank(after.getSeparationOfDuties())
            > separationRank(before.getSeparationOfDuties());
        boolean loosen = separationRank(after.getSeparationOfDuties())
            < separationRank(before.getSeparationOfDuties());
        List<ApprovalPolicyStep> beforeSteps = before.getSteps();
        List<ApprovalPolicyStep> afterSteps = after.getSteps();
        List<StepPair> pairs = pairSteps(beforeSteps, afterSteps);
        tighten |= pairs.stream().anyMatch(pair -> pair.before() == null);
        loosen |= pairs.stream().anyMatch(pair -> pair.after() == null);
        for (StepPair pair : pairs) {
            ApprovalPolicyStep oldStep = pair.before();
            ApprovalPolicyStep newStep = pair.after();
            if (oldStep == null || newStep == null) {
                continue;
            }
            if (newStep.getRequiredCount() > oldStep.getRequiredCount()) {
                tighten = true;
            }
            if (newStep.getRequiredCount() < oldStep.getRequiredCount()) {
                loosen = true;
            }
            ApproverSetChange approverChange = compareApprovers(oldStep, newStep);
            tighten |= approverChange.tighten();
            loosen |= approverChange.loosen();
        }
        if (tighten) {
            return PolicyChangeClass.TIGHTEN;
        }
        if (loosen) {
            return PolicyChangeClass.LOOSEN;
        }
        boolean dueConfigChanged = pairs.stream().anyMatch(pair -> pair.before() != null
            && pair.after() != null
            && (!Objects.equals(pair.before().getDueIntervalHours(),
                    pair.after().getDueIntervalHours())
                || !Objects.equals(pair.before().getOnExpiry(), pair.after().getOnExpiry())));
        return retargeted(before, after) || dueConfigChanged
            ? PolicyChangeClass.RETARGET : PolicyChangeClass.NONE;
    }

    private List<StepPair> pairSteps(List<ApprovalPolicyStep> before, List<ApprovalPolicyStep> after) {
        List<ApprovalPolicyStep> unmatchedBefore = new ArrayList<>(before);
        List<StepPair> pairs = new ArrayList<>();
        for (ApprovalPolicyStep requested : after) {
            ApprovalPolicyStep persisted = requested.getId() <= 0 ? null
                : unmatchedBefore.stream()
                    .filter(step -> step.getId() == requested.getId())
                    .findFirst().orElse(null);
            if (persisted != null) {
                unmatchedBefore.remove(persisted);
            }
            pairs.add(new StepPair(persisted, requested));
        }
        unmatchedBefore.forEach(step -> pairs.add(new StepPair(step, null)));
        return pairs;
    }

    private ApproverSetChange compareApprovers(ApprovalPolicyStep before, ApprovalPolicyStep after) {
        boolean beforeAny = hasAnyApprover(before);
        boolean afterAny = hasAnyApprover(after);
        if (beforeAny && !afterAny) {
            return new ApproverSetChange(true, false);
        }
        if (!beforeAny && afterAny) {
            return new ApproverSetChange(false, true);
        }
        if (beforeAny) {
            return new ApproverSetChange(false, false);
        }
        Set<Integer> beforeUsers = namedApprovers(before);
        Set<Integer> afterUsers = namedApprovers(after);
        return new ApproverSetChange(
            !afterUsers.containsAll(beforeUsers),
            !beforeUsers.containsAll(afterUsers));
    }

    private boolean hasAnyApprover(ApprovalPolicyStep step) {
        return step.getApprovers().stream()
            .anyMatch(approver -> ANY_APPROVER.equals(approver.getApproverKind()));
    }

    private Set<Integer> namedApprovers(ApprovalPolicyStep step) {
        return step.getApprovers().stream()
            .map(ApprovalStepApprover::getUserId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
    }

    private boolean retargeted(ApprovalPolicy before, ApprovalPolicy after) {
        return !Objects.equals(before.getName(), after.getName())
            || before.isActive() != after.isActive()
            || !Objects.equals(before.getDocumentType(), after.getDocumentType())
            || !Objects.equals(before.getCurrency(), after.getCurrency())
            || !decimalEquals(before.getMinTotal(), after.getMinTotal())
            || !decimalEquals(before.getMinDiscountPercent(), after.getMinDiscountPercent())
            || !Objects.equals(before.getMode(), after.getMode())
            || sequentialStepOrderChanged(before, after);
    }

    private boolean sequentialStepOrderChanged(ApprovalPolicy before, ApprovalPolicy after) {
        if (!"sequential".equals(before.getMode()) || !"sequential".equals(after.getMode())) {
            return false;
        }
        return !persistedStepIds(before).equals(persistedStepIds(after));
    }

    private List<Integer> persistedStepIds(ApprovalPolicy policy) {
        return policy.getSteps().stream()
            .map(ApprovalPolicyStep::getId)
            .filter(id -> id > 0)
            .toList();
    }

    private boolean decimalEquals(BigDecimal left, BigDecimal right) {
        return left == null ? right == null : right != null && left.compareTo(right) == 0;
    }

    private int separationRank(String separationOfDuties) {
        return switch (separationOfDuties) {
            case "off" -> 0;
            case "requester" -> 1;
            default -> 2;
        };
    }

    private record ApproverSetChange(boolean tighten, boolean loosen) {
    }

    private record StepPair(ApprovalPolicyStep before, ApprovalPolicyStep after) {
    }
}

/** Semantic policy-change classes in invalidation precedence order. */
enum PolicyChangeClass {
    TIGHTEN,
    LOOSEN,
    RETARGET,
    NONE
}
