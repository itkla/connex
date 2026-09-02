import type {
    ApprovalStepApproverChangePayload,
    CustomRole,
    DealDocument,
    DocumentApprovalStep,
    WorkspaceMember,
} from '@/app/lib/types';

export const MAX_APPROVAL_STEP_APPROVERS = 20;

export type ApprovalStepManagementAction = 'escalate' | 'reassign';

export type ApprovalMemberDirectoryStatus = 'hidden' | 'loading' | 'ready' | 'unavailable';

export type ApprovalStepApproverSelection =
    | { mode: 'any_approver' }
    | { mode: 'members'; memberIds: readonly number[] };

export type ApprovalCandidateDirectory = {
    members: WorkspaceMember[];
    verifiedApproverIds: number[];
};

/**
 * Filters the active member directory with role definitions the viewer could read. A role that
 * cannot be resolved remains selectable, but is not claimed as verified approval capacity; the
 * approval mutation remains authoritative for that member.
 */
export function approvalCandidateDirectory(
    members: readonly WorkspaceMember[],
    builtInRoles: readonly CustomRole[],
    customRoles: readonly CustomRole[],
): ApprovalCandidateDirectory {
    const builtInByName = new Map(builtInRoles.map((role) => [role.name, role]));
    const customById = new Map(customRoles.map((role) => [role.id, role]));
    const candidates: WorkspaceMember[] = [];
    const verifiedApproverIds: number[] = [];

    for (const member of members) {
        if (member.status === 'pending') continue;
        const role = member.roleId == null
            ? builtInByName.get(member.builtInRole)
            : customById.get(member.roleId);
        const roleCanApprove = role?.permissions.includes('DOCUMENT_APPROVE');
        if (roleCanApprove === false) continue;
        candidates.push(member);
        if (roleCanApprove === true) verifiedApproverIds.push(member.id);
    }

    return { members: candidates, verifiedApproverIds };
}

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

/** Returns how many additional verified, undecided approvers the proposed change still needs. */
export function approvalStepQuorumShortfall(
    step: DocumentApprovalStep,
    action: ApprovalStepManagementAction,
    selection: ApprovalStepApproverSelection,
    verifiedApproverIds: readonly number[],
): number {
    const approvedIds = new Set(
        step.decisions
            .filter((decision) => decision.decision === 'approved')
            .map((decision) => decision.decidedBy),
    );
    const verifiedIds = new Set(verifiedApproverIds);
    const proposedIds = selection.mode === 'any_approver'
        ? verifiedApproverIds
        : selection.memberIds.filter((id) => verifiedIds.has(id));
    const eligibleIds = new Set((
        action === 'escalate'
            ? [...step.effectiveApproverIds, ...proposedIds]
            : proposedIds
    ).filter((id) => verifiedIds.has(id)));
    const undecidedCount = [...eligibleIds].filter((id) => !approvedIds.has(id)).length;
    const remainingCount = Math.max(0, step.requiredCount - step.approvedCount);
    return Math.max(0, remainingCount - undecidedCount);
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
