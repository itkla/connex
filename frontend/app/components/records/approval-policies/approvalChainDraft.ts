import type {
    ApprovalApproverKind,
    ApprovalChainMode,
    ApprovalStepExpiryAction,
    SeparationOfDuties,
} from '@/app/lib/types';

/** One approval-chain step being edited before it is submitted to the server. */
export type ChainStepDraft = {
    key: string;
    id?: number;
    name: string;
    requiredCount: number;
    dueIntervalHours: string;
    onExpiry: ApprovalStepExpiryAction;
    kind: ApprovalApproverKind;
    userIds: number[];
};

/** The largest deadline interval accepted by the approval-policy API. */
export const MAX_DUE_INTERVAL_HOURS = 8760;

const MAX_QUORUM = 20;

let nextStepKey = 0;

/** Creates a new approval-chain step with the server defaults. */
export const newChainStep = (): ChainStepDraft => ({
    key: `new-${nextStepKey++}`,
    name: '',
    requiredCount: 1,
    dueIntervalHours: '',
    onExpiry: 'expire',
    kind: 'any_approver',
    userIds: [],
});

/**
 * Returns the largest quorum the current draft can express. Named steps are capped by the selected
 * members; open steps use the server maximum because permission eligibility is server-owned.
 */
export const availableApprovers = (step: ChainStepDraft) =>
    step.kind === 'any_approver' ? MAX_QUORUM : step.userIds.length;

/** Whether an optional deadline is a whole number of hours accepted by the backend. */
export const dueIntervalIsValid = (value: string) => {
    if (value.trim() === '') return true;
    const hours = Number(value);
    return Number.isInteger(hours) && hours >= 1 && hours <= MAX_DUE_INTERVAL_HOURS;
};

/** Whether a select value is a supported approval-chain execution mode. */
export const isApprovalChainMode = (value: string): value is ApprovalChainMode =>
    value === 'sequential' || value === 'parallel';

/** Whether a select value is a supported separation-of-duties mode. */
export const isSeparationOfDuties = (value: string): value is SeparationOfDuties =>
    value === 'strict' || value === 'requester' || value === 'off';

/** Whether a select value is a supported approval-step approver kind. */
export const isApprovalApproverKind = (value: string): value is ApprovalApproverKind =>
    value === 'any_approver' || value === 'user';

/** Whether a select value is a supported approval-step deadline action. */
export const isApprovalStepExpiryAction = (value: string): value is ApprovalStepExpiryAction =>
    value === 'expire' || value === 'escalate';
