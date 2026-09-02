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
    const builtInCanApproveByName = new Map(builtInRoles.map((role) => [
        role.name,
        new Set(role.permissions).has('DOCUMENT_APPROVE'),
    ]));
    const customCanApproveById = new Map(customRoles.map((role) => [
        role.id,
        new Set(role.permissions).has('DOCUMENT_APPROVE'),
    ]));
    const candidates: WorkspaceMember[] = [];
    const verifiedApproverIds: number[] = [];

    for (const member of members) {
        if (member.status === 'pending') continue;
        const roleCanApprove = member.roleId == null
            ? builtInCanApproveByName.get(member.builtInRole)
            : customCanApproveById.get(member.roleId);
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
    const approvedIds = new Set<number>();
    for (const decision of step.decisions) {
        if (decision.decision === 'approved') approvedIds.add(decision.decidedBy);
    }
    const verifiedIds = new Set(verifiedApproverIds);
    const eligibleIds = new Set<number>();
    if (action === 'escalate') {
        for (const id of step.effectiveApproverIds) {
            if (verifiedIds.has(id)) eligibleIds.add(id);
        }
    }
    const proposedIds = selection.mode === 'any_approver'
        ? verifiedApproverIds
        : selection.memberIds;
    for (const id of proposedIds) {
        if (verifiedIds.has(id)) eligibleIds.add(id);
    }
    let undecidedCount = 0;
    for (const id of eligibleIds) {
        if (!approvedIds.has(id)) undecidedCount += 1;
    }
    const remainingCount = Math.max(0, step.requiredCount - step.approvedCount);
    return Math.max(0, remainingCount - undecidedCount);
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
