import type {
    ApprovalStepApproverChangePayload,
    ApprovalStepCandidates,
    DealDocument,
    DocumentApprovalStep,
} from '@/app/lib/types';

export const MAX_APPROVAL_STEP_APPROVERS = 20;

export type ApprovalStepManagementAction = 'escalate' | 'reassign';

export type ApprovalMemberDirectoryStatus = 'hidden' | 'loading' | 'ready' | 'unavailable';

export type ApprovalStepApproverSelection =
    | { mode: 'any_approver' }
    | { mode: 'members'; memberIds: readonly number[] };

/** Returns the active steps the current viewer may manage, in policy order. */
export function manageableApprovalSteps(
    document: DealDocument,
    canManageApprovals: boolean,
): DocumentApprovalStep[] {
    const approval = document.latestApproval;
    if (
        !canManageApprovals
        || document.status !== 'pending_approval'
        || approval?.status !== 'pending'
    ) {
        return [];
    }
    const active = approval.steps
        .filter((step) => step.status === 'active')
        .toSorted((left, right) => left.stepOrder - right.stepOrder);
    return approval.mode === 'sequential' ? active.slice(0, 1) : active;
}

/** Returns manageable steps whose effective approver set can still be widened. */
export function widenableApprovalSteps(
    document: DealDocument,
    canManageApprovals: boolean,
): DocumentApprovalStep[] {
    return manageableApprovalSteps(document, canManageApprovals)
        .filter((step) => !step.effectiveAnyApprover);
}

/** Returns how many additional server-authorized, undecided approvers the proposed change still needs. */
export function approvalStepQuorumShortfall(
    snapshot: ApprovalStepCandidates,
    action: ApprovalStepManagementAction,
    selection: ApprovalStepApproverSelection,
): number {
    const decidedIds = new Set(snapshot.decidedByUserIds);
    const candidateIds = snapshot.candidates.map((candidate) => candidate.id);
    const availableIds = new Set(candidateIds);
    const eligibleIds = new Set<number>();
    if (action === 'escalate') {
        for (const id of snapshot.effectiveApproverIds) {
            if (availableIds.has(id)) eligibleIds.add(id);
        }
    }
    const proposedIds = selection.mode === 'any_approver'
        ? candidateIds
        : selection.memberIds;
    for (const id of proposedIds) {
        if (availableIds.has(id)) eligibleIds.add(id);
    }
    let undecidedCount = 0;
    for (const id of eligibleIds) {
        if (!decidedIds.has(id)) undecidedCount += 1;
    }
    return Math.max(0, snapshot.remainingRequiredCount - undecidedCount);
}

/** Shapes the mutually exclusive approver selection into the existing backend request contract. */
export function approvalStepApproverChangePayload(
    selection: ApprovalStepApproverSelection,
    comment: string,
): ApprovalStepApproverChangePayload {
    const approvers: ApprovalStepApproverChangePayload['approvers'] = [];
    if (selection.mode === 'any_approver') {
        approvers.push({ approverKind: 'any_approver' });
    } else {
        const addedIds = new Set<number>();
        for (const userId of selection.memberIds) {
            if (addedIds.has(userId)) continue;
            approvers.push({ approverKind: 'user', userId });
            addedIds.add(userId);
            if (approvers.length === MAX_APPROVAL_STEP_APPROVERS) break;
        }
    }
    return {
        approvers,
        comment: comment.trim() || null,
    };
}
