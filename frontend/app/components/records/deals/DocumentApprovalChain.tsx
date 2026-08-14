'use client';

import { useTranslations } from 'next-intl';

import { cn } from '@/lib/utils';
import type { ApprovalStepStatus, DocumentApproval } from '@/app/lib/types';

type Props = {
    approval: DocumentApproval;
    activeStepId?: number | null;
};

const STEP_DOT: Record<ApprovalStepStatus, string> = {
    pending: 'bg-muted-foreground/40',
    active: 'bg-risk-medium',
    approved: 'bg-chart-won',
    rejected: 'bg-destructive',
    cancelled: 'bg-muted-foreground/40',
};

/**
 * Compact progress readout for an approval chain: one pill per frozen step, showing its label,
 * how many of the required approvals it holds, and which step is waiting on the current user.
 */
export default function DocumentApprovalChain({ approval, activeStepId }: Props) {
    const t = useTranslations('DealsDocuments');
    if (approval.steps.length === 0) return null;

    return (
        <div className="mt-1.5 flex flex-wrap items-center gap-1.5">
            {approval.steps.map((step) => (
                <span
                    key={step.id}
                    className={cn(
                        'inline-flex items-center gap-1.5 rounded-full border px-2 py-0.5 text-xs',
                        step.id === activeStepId
                            ? 'border-brand text-foreground'
                            : 'border-border text-muted-foreground',
                    )}
                >
                    <span className={cn('size-1.5 rounded-full', STEP_DOT[step.status])} aria-hidden="true" />
                    {step.name?.trim() || t('chainStep', { number: step.stepOrder })}
                    <span className="tabular-nums">
                        {t('chainProgress', { approved: step.approvedCount, required: step.requiredCount })}
                    </span>
                </span>
            ))}
            {approval.mode === 'parallel' ? (
                <span className="text-xs text-muted-foreground">{t('chainParallel')}</span>
            ) : null}
        </div>
    );
}
