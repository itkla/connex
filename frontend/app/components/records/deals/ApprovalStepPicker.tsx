'use client';

import { useMemo } from 'react';
import { useLocale, useTranslations } from 'next-intl';

import { Label } from '@/components/ui/label';
import {
    Select,
    SelectContent,
    SelectItem,
    SelectTrigger,
    SelectValue,
} from '@/components/ui/select';
import type { DocumentApprovalStep, WorkspaceMember } from '@/app/lib/types';
import type { ApprovalMemberDirectoryStatus } from './approvalStepActions';

const displayName = (member: WorkspaceMember) => member.displayName.trim() || member.username;

type Props = {
    steps: DocumentApprovalStep[];
    selectedStep: DocumentApprovalStep | null;
    memberLabelStatus: ApprovalMemberDirectoryStatus;
    memberLabels: WorkspaceMember[];
    busy: boolean;
    onStepChange: (stepId: number) => void;
};

/** Selects the approval step and summarizes its current effective approvers. */
export default function ApprovalStepPicker({
    steps,
    selectedStep,
    memberLabelStatus,
    memberLabels,
    busy,
    onStepChange,
}: Props) {
    const t = useTranslations('DealsDocuments');
    const locale = useLocale();
    const listFormatter = useMemo(
        () => new Intl.ListFormat(locale, { style: 'long', type: 'conjunction' }),
        [locale],
    );
    const memberLabelsById = useMemo(
        () => new Map(memberLabels.map((member) => [member.id, member])),
        [memberLabels],
    );
    const stepName = (step: DocumentApprovalStep) =>
        step.name?.trim() || t('chainStep', { number: step.stepOrder });
    const currentApprovers = () => {
        if (!selectedStep) return t('approvalStepChanged');
        if (selectedStep.effectiveAnyApprover) return t('approvalAnyApprover');
        if (memberLabelStatus === 'loading') return t('approvalMembersLoading');
        if (memberLabelStatus !== 'ready') return t('approvalMembersUnavailable');
        if (selectedStep.effectiveApproverIds.length === 0) return t('approvalNoCurrentApprovers');
        return listFormatter.format(selectedStep.effectiveApproverIds.map((id) => {
            const member = memberLabelsById.get(id);
            return member ? displayName(member) : t('chainFormerMember', { id });
        }));
    };

    return (
        <div className="space-y-2">
            <Label htmlFor={steps.length > 1 ? 'approval-management-step' : undefined}>
                {t('approvalStepLabel')}
            </Label>
            {steps.length > 1 ? (
                <Select
                    value={selectedStep ? String(selectedStep.id) : ''}
                    onValueChange={(value) => {
                        const stepId = Number(value);
                        if (Number.isInteger(stepId)) onStepChange(stepId);
                    }}
                    disabled={busy}
                >
                    <SelectTrigger id="approval-management-step" className="w-full" autoFocus>
                        <SelectValue placeholder={t('approvalStepPlaceholder')} />
                    </SelectTrigger>
                    <SelectContent>
                        {steps.map((step) => (
                            <SelectItem key={step.id} value={String(step.id)}>
                                {stepName(step)}
                            </SelectItem>
                        ))}
                    </SelectContent>
                </Select>
            ) : (
                <p className="text-sm font-medium text-foreground">
                    {selectedStep ? stepName(selectedStep) : t('approvalStepChanged')}
                </p>
            )}
            {selectedStep && (
                <p className="text-sm text-muted-foreground" aria-live="polite">
                    <span className="font-medium text-foreground">{t('approvalCurrentApprovers')}</span>{' '}
                    {currentApprovers()}
                </p>
            )}
        </div>
    );
}
