'use client';

import { useTranslations } from 'next-intl';
import { CheckIcon, PlusIcon, XIcon } from 'lucide-react';

import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import {
    Select,
    SelectContent,
    SelectItem,
    SelectTrigger,
    SelectValue,
} from '@/components/ui/select';
import { cn } from '@/lib/utils';
import type {
    ApprovalApproverKind,
    ApprovalChainMode,
    SeparationOfDuties,
    WorkspaceMember,
} from '@/app/lib/types';

/** One step being edited. `userIds` is only meaningful while `kind` is `user`. */
export type ChainStepDraft = {
    name: string;
    requiredCount: number;
    kind: ApprovalApproverKind;
    userIds: number[];
};

type Props = {
    mode: ApprovalChainMode;
    onModeChange: (mode: ApprovalChainMode) => void;
    separationOfDuties: SeparationOfDuties;
    onSeparationOfDutiesChange: (value: SeparationOfDuties) => void;
    steps: ChainStepDraft[];
    onStepsChange: (steps: ChainStepDraft[]) => void;
    members: WorkspaceMember[];
    disabled?: boolean;
};

const MAX_STEPS = 10;

export const newChainStep = (): ChainStepDraft => ({
    name: '',
    requiredCount: 1,
    kind: 'any_approver',
    userIds: [],
});

/** The approvals a step can collect today, which is also the largest quorum it may require. */
export const availableApprovers = (step: ChainStepDraft, members: WorkspaceMember[]) =>
    step.kind === 'any_approver' ? members.length : step.userIds.length;

/**
 * Editor for a policy's approver chain: run order, separation of duties, and the ordered steps
 * with their quorum and approvers. An empty chain means one approval from anyone who can approve.
 */
export default function ApprovalChainEditor({
    mode,
    onModeChange,
    separationOfDuties,
    onSeparationOfDutiesChange,
    steps,
    onStepsChange,
    members,
    disabled,
}: Props) {
    const t = useTranslations('ApprovalPolicyDialog');

    const updateStep = (index: number, patch: Partial<ChainStepDraft>) =>
        onStepsChange(steps.map((step, i) => (i === index ? { ...step, ...patch } : step)));

    const toggleApprover = (index: number, userId: number) => {
        const step = steps[index];
        const userIds = step.userIds.includes(userId)
            ? step.userIds.filter((id) => id !== userId)
            : [...step.userIds, userId];
        updateStep(index, {
            userIds,
            requiredCount: Math.min(step.requiredCount, Math.max(userIds.length, 1)),
        });
    };

    return (
        <div className="space-y-3">
            <div className="grid grid-cols-2 gap-3">
                <div className="space-y-2">
                    <Label>{t('modeLabel')}</Label>
                    <Select
                        value={mode}
                        onValueChange={(value) => onModeChange(value as ApprovalChainMode)}
                        disabled={disabled}
                    >
                        <SelectTrigger aria-label={t('modeLabel')} className="w-full"><SelectValue /></SelectTrigger>
                        <SelectContent>
                            <SelectItem value="sequential">{t('modeSequential')}</SelectItem>
                            <SelectItem value="parallel">{t('modeParallel')}</SelectItem>
                        </SelectContent>
                    </Select>
                </div>
                <div className="space-y-2">
                    <Label>{t('sodLabel')}</Label>
                    <Select
                        value={separationOfDuties}
                        onValueChange={(value) => onSeparationOfDutiesChange(value as SeparationOfDuties)}
                        disabled={disabled}
                    >
                        <SelectTrigger aria-label={t('sodLabel')} className="w-full"><SelectValue /></SelectTrigger>
                        <SelectContent>
                            <SelectItem value="strict">{t('sodStrict')}</SelectItem>
                            <SelectItem value="requester">{t('sodRequester')}</SelectItem>
                            <SelectItem value="off">{t('sodOff')}</SelectItem>
                        </SelectContent>
                    </Select>
                </div>
            </div>

            {steps.length === 0 ? (
                <p className="text-xs text-muted-foreground">{t('chainEmptyHint')}</p>
            ) : null}

            {steps.map((step, index) => {
                const available = availableApprovers(step, members);
                const quorumTooHigh = step.requiredCount > available;
                return (
                    <div key={index} className="space-y-3 rounded-xl border border-border p-3">
                        <div className="flex items-center justify-between gap-2">
                            <span className="text-xs font-medium text-muted-foreground">
                                {t('stepNumber', { number: index + 1 })}
                            </span>
                            <Button
                                variant="ghost"
                                size="icon"
                                aria-label={t('removeStep')}
                                disabled={disabled}
                                onClick={() => onStepsChange(steps.filter((_, i) => i !== index))}
                            >
                                <XIcon className="size-4" />
                            </Button>
                        </div>
                        <div className="grid grid-cols-[1fr_auto] gap-3">
                            <div className="space-y-2">
                                <Label htmlFor={`step-name-${index}`}>{t('stepNameLabel')}</Label>
                                <Input
                                    id={`step-name-${index}`}
                                    value={step.name}
                                    maxLength={255}
                                    placeholder={t('stepNamePlaceholder')}
                                    onChange={(e) => updateStep(index, { name: e.target.value })}
                                    disabled={disabled}
                                />
                            </div>
                            <div className="w-28 space-y-2">
                                <Label htmlFor={`step-quorum-${index}`}>{t('stepQuorumLabel')}</Label>
                                <Input
                                    id={`step-quorum-${index}`}
                                    type="number"
                                    min={1}
                                    max={20}
                                    value={step.requiredCount}
                                    onChange={(e) =>
                                        updateStep(index, { requiredCount: Math.max(1, Number(e.target.value) || 1) })
                                    }
                                    disabled={disabled}
                                    aria-invalid={quorumTooHigh || undefined}
                                />
                            </div>
                        </div>
                        <div className="space-y-2">
                            <Label>{t('stepApproversLabel')}</Label>
                            <Select
                                value={step.kind}
                                onValueChange={(value) =>
                                    updateStep(index, {
                                        kind: value as ApprovalApproverKind,
                                        requiredCount: value === 'user' ? 1 : step.requiredCount,
                                    })
                                }
                                disabled={disabled}
                            >
                                <SelectTrigger aria-label={t('stepApproversLabel')} className="w-full">
                                    <SelectValue />
                                </SelectTrigger>
                                <SelectContent>
                                    <SelectItem value="any_approver">{t('approverAny')}</SelectItem>
                                    <SelectItem value="user">{t('approverNamed')}</SelectItem>
                                </SelectContent>
                            </Select>
                        </div>
                        {step.kind === 'user' ? (
                            <div className="flex flex-wrap gap-2">
                                {members.map((member) => {
                                    const selected = step.userIds.includes(member.id);
                                    return (
                                        <button
                                            key={member.id}
                                            type="button"
                                            disabled={disabled}
                                            aria-pressed={selected}
                                            onClick={() => toggleApprover(index, member.id)}
                                            className={cn(
                                                'inline-flex items-center gap-1.5 rounded-full border px-3 py-1 text-xs transition-colors',
                                                selected
                                                    ? 'border-brand bg-brand/10 text-foreground'
                                                    : 'border-border text-muted-foreground hover:text-foreground',
                                            )}
                                        >
                                            {selected ? <CheckIcon className="size-3" /> : null}
                                            {member.displayName || member.username}
                                        </button>
                                    );
                                })}
                                {members.length === 0 ? (
                                    <p className="text-xs text-muted-foreground">{t('noEligibleApprovers')}</p>
                                ) : null}
                            </div>
                        ) : null}
                        {quorumTooHigh ? (
                            <p className="text-xs text-destructive">{t('quorumTooHigh')}</p>
                        ) : null}
                    </div>
                );
            })}

            <Button
                variant="outline"
                size="sm"
                disabled={disabled || steps.length >= MAX_STEPS}
                onClick={() => onStepsChange([...steps, newChainStep()])}
            >
                <PlusIcon className="size-4" />
                {t('addStep')}
            </Button>
        </div>
    );
}
