'use client';

import { useMemo, useState } from 'react';
import { useLocale, useTranslations } from 'next-intl';
import { AnimatePresence, motion, useReducedMotion } from 'motion/react';
import {
    CheckCircleIcon,
    ChevronDownIcon,
    ClockIcon,
    ExclamationTriangleIcon,
    MinusCircleIcon,
    XCircleIcon,
} from '@heroicons/react/24/outline';

import { cn } from '@/lib/utils';
import { easeOut, instant } from '@/app/lib/motion';
import type {
    ApprovalStepAssignment,
    ApprovalStepStatus,
    DocumentApproval,
    DocumentApprovalStep,
    WorkspaceMember,
} from '@/app/lib/types';
import { formatUtcDateTime } from '@/app/lib/utils';
import type { ApprovalMemberDirectoryStatus } from './approvalStepActions';

type Props = {
    approval: DocumentApproval;
    activeStepId?: number | null;
    memberDirectoryStatus?: ApprovalMemberDirectoryStatus;
    members?: WorkspaceMember[];
};

const STEP_ICON = {
    pending: ClockIcon,
    active: ClockIcon,
    approved: CheckCircleIcon,
    rejected: XCircleIcon,
    cancelled: MinusCircleIcon,
    unsatisfiable: ExclamationTriangleIcon,
    expired: ExclamationTriangleIcon,
} satisfies Record<ApprovalStepStatus, typeof ClockIcon>;

const STEP_TONE: Record<ApprovalStepStatus, string> = {
    pending: 'text-muted-foreground',
    active: 'text-risk-medium',
    approved: 'text-chart-won',
    rejected: 'text-destructive',
    cancelled: 'text-muted-foreground',
    unsatisfiable: 'text-destructive',
    expired: 'text-destructive',
};

/**
 * Progress readout for an approval chain inside the deal-documents table. It stays collapsed to one
 * summary line so a ten-step chain cannot turn a table row into a paragraph, and expands in place on
 * demand. Status is carried by an icon plus assistive text, never by colour alone.
 */
export default function DocumentApprovalChain({
    approval,
    activeStepId,
    memberDirectoryStatus = 'hidden',
    members = [],
}: Props) {
    const t = useTranslations('DealsDocuments');
    const locale = useLocale();
    const listFormatter = useMemo(
        () => new Intl.ListFormat(locale, { style: 'long', type: 'conjunction' }),
        [locale],
    );
    const reduceMotion = useReducedMotion();
    const [expanded, setExpanded] = useState(false);

    if (approval.steps.length === 0) return null;

    const ordered = [...approval.steps].sort((a, b) => a.stepOrder - b.stepOrder);
    const current = ordered.find((step) => step.id === activeStepId)
        ?? ordered.find((step) => step.status === 'active')
        ?? ordered[0];
    const label = (step: DocumentApprovalStep) =>
        step.name?.trim() || t('chainStep', { number: step.stepOrder });
    const memberLabel = (id: number | null | undefined, displayName: string | null | undefined) =>
        displayName?.trim() || t('chainFormerMember', { id: id ?? 0 });
    const assignmentDescription = (assignment: ApprovalStepAssignment) => {
        if (assignment.assignmentKind === 'delegation') {
            return t('chainAssignmentDelegation', {
                from: memberLabel(assignment.delegatedByUserId, assignment.delegatedByDisplayName),
                to: memberLabel(assignment.userId, assignment.userDisplayName),
            });
        }
        const actor = assignment.createdByUserId == null
            ? null
            : memberLabel(assignment.createdByUserId, assignment.createdByDisplayName);
        if (assignment.assignmentKind === 'escalation') {
            if (assignment.approverKind === 'any_approver') {
                return actor == null
                    ? t('chainAssignmentDeadlineEscalation')
                    : t('chainAssignmentEscalationAny', { actor });
            }
            return t('chainAssignmentEscalationUser', {
                actor: actor ?? t('chainAutomatedActor'),
                user: memberLabel(assignment.userId, assignment.userDisplayName),
            });
        }
        if (assignment.approverKind === 'any_approver') {
            return t('chainAssignmentReassignmentAny', {
                actor: actor ?? t('chainAutomatedActor'),
            });
        }
        return t('chainAssignmentReassignmentUser', {
            actor: actor ?? t('chainAutomatedActor'),
            user: memberLabel(assignment.userId, assignment.userDisplayName),
        });
    };
    const effectiveApproverDescription = (step: DocumentApprovalStep) => {
        if (step.effectiveAnyApprover) return t('approvalAnyApprover');
        if (memberDirectoryStatus === 'loading') return t('approvalMembersLoading');
        if (memberDirectoryStatus !== 'ready') return t('approvalMembersUnavailable');
        if (step.effectiveApproverIds.length === 0) return t('approvalNoCurrentApprovers');
        const labels = step.effectiveApproverIds.map((id) => {
            const member = members.find((candidate) => candidate.id === id);
            return member?.displayName.trim() || member?.username || t('chainFormerMember', { id });
        });
        return listFormatter.format(labels);
    };
    const blocked = approval.status === 'pending' && !approval.satisfiable;

    return (
        <div className="mt-1.5">
            <button
                type="button"
                aria-expanded={expanded}
                onClick={() => setExpanded((open) => !open)}
                className={cn(
                    'inline-flex min-h-8 items-center gap-1.5 rounded-full py-1 pr-1 text-xs text-muted-foreground hover:text-foreground focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ring',
                    !reduceMotion && 'transition-colors active:scale-[0.98]',
                )}
            >
                <span>
                    {approval.mode === 'parallel'
                        ? t('chainSummaryParallel', {
                            total: ordered.length,
                            done: ordered.filter((step) => step.status === 'approved').length,
                        })
                        : t('chainSummary', {
                            step: current.stepOrder,
                            total: ordered.length,
                            name: label(current),
                            approved: current.approvedCount,
                            required: current.requiredCount,
                        })}
                </span>
                {blocked && (
                    <ExclamationTriangleIcon
                        className="size-3.5 shrink-0 text-destructive"
                        aria-hidden="true"
                    />
                )}
                <ChevronDownIcon
                    className={cn(
                        'size-3',
                        !reduceMotion && 'transition-transform duration-150',
                        expanded && 'rotate-180',
                    )}
                    aria-hidden="true"
                />
            </button>

            {blocked && (
                <p className="mt-1 text-xs text-destructive">
                    {approval.blockedReason?.trim() || t('chainBlocked')}
                </p>
            )}

            <AnimatePresence initial={false}>
                {expanded && (
                    <motion.ul
                        initial={reduceMotion ? { opacity: 0 } : { opacity: 0, y: -2 }}
                        animate={{ opacity: 1, y: 0 }}
                        exit={reduceMotion ? { opacity: 0 } : { opacity: 0, y: -2 }}
                        transition={reduceMotion ? instant : { duration: 0.15, ease: easeOut }}
                        className="mt-1.5 flex flex-col gap-1"
                    >
                        {ordered.map((step) => {
                            const open = step.status === 'active' || step.status === 'pending';
                            const stuck = open && !step.satisfiable;
                            const Icon = stuck ? ExclamationTriangleIcon : STEP_ICON[step.status];
                            return (
                                <li key={step.id} className="text-xs text-muted-foreground">
                                    <div className="flex items-center gap-1.5">
                                        <Icon
                                            className={cn(
                                                'size-3.5 shrink-0',
                                                stuck ? 'text-destructive' : STEP_TONE[step.status],
                                            )}
                                            aria-hidden="true"
                                        />
                                        <span className={cn(step.id === activeStepId && 'text-foreground')}>
                                            {label(step)}
                                        </span>
                                        <span className="tabular-nums">
                                            {t('chainProgress', {
                                                approved: step.approvedCount,
                                                required: step.requiredCount,
                                            })}
                                        </span>
                                        <span className="sr-only">
                                            {stuck
                                                ? t('chainStatus_unsatisfiable')
                                                : t(`chainStatus_${step.status}`)}
                                        </span>
                                    </div>
                                    {stuck && (
                                        <p className="ml-5 text-destructive">
                                            {step.unsatisfiableReason?.trim() || t('chainBlocked')}
                                        </p>
                                    )}
                                    {step.dueAt && (
                                        <p className="ml-5">
                                            {t('chainDueAt', {
                                                date: formatUtcDateTime(step.dueAt, locale),
                                            })}
                                        </p>
                                    )}
                                    {step.status === 'active' && memberDirectoryStatus !== 'hidden' && (
                                        <p className="ml-5">
                                            <span className="font-medium text-foreground">
                                                {t('approvalCurrentApprovers')}
                                            </span>{' '}
                                            {effectiveApproverDescription(step)}
                                        </p>
                                    )}
                                    {step.assignments.length > 0 && (
                                        <ul className="ml-5 mt-1 space-y-1 border-l border-border pl-3">
                                            {step.assignments.map((assignment) => (
                                                <li key={assignment.id}>
                                                    <p className="text-foreground">
                                                        {assignmentDescription(assignment)}
                                                    </p>
                                                    <p className="text-muted-foreground">
                                                        {formatUtcDateTime(assignment.createdAt, locale)}
                                                    </p>
                                                    {assignment.comment?.trim() && (
                                                        <p className="text-muted-foreground">
                                                            {t('chainAssignmentComment', {
                                                                comment: assignment.comment.trim(),
                                                            })}
                                                        </p>
                                                    )}
                                                </li>
                                            ))}
                                        </ul>
                                    )}
                                </li>
                            );
                        })}

                    </motion.ul>
                )}
            </AnimatePresence>
        </div>
    );
}
