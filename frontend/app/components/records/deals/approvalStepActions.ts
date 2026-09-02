import type {
    ApprovalStepApproverChangePayload,
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

/** Shapes the mutually exclusive approver selection into the existing backend request contract. */
export function approvalStepApproverChangePayload(
    selection: ApprovalStepApproverSelection,
    comment: string,
): ApprovalStepApproverChangePayload {
    const approvers: ApprovalStepApproverChangePayload['approvers'] = selection.mode === 'any_approver'
        ? [{ approverKind: 'any_approver' }]
        : [...new Set(selection.memberIds)]
            .slice(0, MAX_APPROVAL_STEP_APPROVERS)
            .map((userId) => ({ approverKind: 'user', userId }));
    return {
        approvers,
        comment: comment.trim() || null,
    };
}
