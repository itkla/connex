'use client';

import { useState } from 'react';
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
import type { ApprovalStepStatus, DocumentApproval, DocumentApprovalStep } from '@/app/lib/types';
import { formatDateTime } from '@/app/lib/utils';

type Props = {
    approval: DocumentApproval;
    activeStepId?: number | null;
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
export default function DocumentApprovalChain({ approval, activeStepId }: Props) {
    const t = useTranslations('DealsDocuments');
    const locale = useLocale();
    const reduceMotion = useReducedMotion();
    const [expanded, setExpanded] = useState(false);

    if (approval.steps.length === 0) return null;

    const ordered = [...approval.steps].sort((a, b) => a.stepOrder - b.stepOrder);
    const current = ordered.find((step) => step.id === activeStepId)
        ?? ordered.find((step) => step.status === 'active')
        ?? ordered[0];
    const label = (step: DocumentApprovalStep) =>
        step.name?.trim() || t('chainStep', { number: step.stepOrder });
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
                                                date: formatDateTime(step.dueAt, locale),
                                            })}
                                        </p>
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
