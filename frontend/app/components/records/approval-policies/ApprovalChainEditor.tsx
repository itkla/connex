'use client';

import { useRef, useState } from 'react';
import { useTranslations } from 'next-intl';
import { AnimatePresence, motion, useReducedMotion } from 'motion/react';
import {
    ArrowDownIcon,
    ArrowUpIcon,
    PlusIcon,
    XMarkIcon,
} from '@heroicons/react/24/outline';

import {
    Autocomplete,
    AutocompleteContent,
    AutocompleteEmpty,
    AutocompleteInput,
    AutocompleteItem,
    AutocompleteList,
} from '@/components/ui/autocomplete';
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
import { easeOut, instant, springSnappy } from '@/app/lib/motion';
import type { ApprovalChainMode, SeparationOfDuties, WorkspaceMember } from '@/app/lib/types';
import {
    availableApprovers,
    dueIntervalIsValid,
    isApprovalApproverKind,
    isApprovalChainMode,
    isApprovalStepExpiryAction,
    isSeparationOfDuties,
    MAX_DUE_INTERVAL_HOURS,
    newChainStep,
    type ChainStepDraft,
} from './approvalChainDraft';

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

/**
 * Editor for a policy's approver chain, composed as sentences rather than stacked field labels to
 * match the segment builder. Steps are reorderable because order is meaningful in a sequential
 * chain; moving one keeps focus on the button that moved it so the keyboard path survives.
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
    const reduceMotion = useReducedMotion();
    const moveButtons = useRef(new Map<string, HTMLButtonElement>());
    const [queries, setQueries] = useState<Record<string, string>>({});

    const updateStep = (index: number, patch: Partial<ChainStepDraft>) =>
        onStepsChange(steps.map((step, i) => (i === index ? { ...step, ...patch } : step)));

    const move = (index: number, delta: number) => {
        const target = index + delta;
        if (target < 0 || target >= steps.length) return;
        const reordered = [...steps];
        [reordered[index], reordered[target]] = [reordered[target], reordered[index]];
        onStepsChange(reordered);
        const landedAtBoundary = target === 0 ? 1 : target === steps.length - 1 ? -1 : delta;
        requestAnimationFrame(() => {
            moveButtons.current.get(`${steps[index].key}:${landedAtBoundary}`)?.focus();
        });
    };

    const addApprover = (index: number, userId: number) => {
        const step = steps[index];
        if (step.userIds.includes(userId)) return;
        updateStep(index, { userIds: [...step.userIds, userId] });
    };

    const removeApprover = (index: number, userId: number) => {
        const step = steps[index];
        const userIds = step.userIds.filter((id) => id !== userId);
        updateStep(index, {
            userIds,
            requiredCount: Math.min(step.requiredCount, Math.max(userIds.length, 1)),
        });
    };

    const nameOf = (userId: number) => {
        const member = members.find((candidate) => candidate.id === userId);
        return member?.displayName || member?.username || String(userId);
    };

    /**
     * Display names are not unique in a workspace, so the picker's option text carries the member's
     * email. Selecting by that string can never resolve to the wrong person.
     */
    const optionOf = (member: WorkspaceMember) =>
        `${member.displayName || member.username} (${member.email})`;

    return (
        <div className="flex flex-col gap-3">
            <div className="flex flex-wrap items-center gap-2 text-sm text-muted-foreground">
                <span>{t('chainRunLabel')}</span>
                <Select
                    value={mode}
                    onValueChange={(value) => {
                        if (isApprovalChainMode(value)) onModeChange(value);
                    }}
                    disabled={disabled}
                >
                    <SelectTrigger size="sm" aria-label={t('chainRunLabel')} className="w-44 shrink-0">
                        <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                        <SelectItem value="sequential">{t('modeSequential')}</SelectItem>
                        <SelectItem value="parallel">{t('modeParallel')}</SelectItem>
                    </SelectContent>
                </Select>
                <span>{t('sodLabel')}</span>
                <Select
                    value={separationOfDuties}
                    onValueChange={(value) => {
                        if (isSeparationOfDuties(value)) onSeparationOfDutiesChange(value);
                    }}
                    disabled={disabled}
                >
                    <SelectTrigger size="sm" aria-label={t('sodLabel')} className="w-64 shrink-0">
                        <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                        <SelectItem value="strict">{t('sodStrict')}</SelectItem>
                        <SelectItem value="requester">{t('sodRequester')}</SelectItem>
                        <SelectItem value="off">{t('sodOff')}</SelectItem>
                    </SelectContent>
                </Select>
            </div>

            {steps.length === 0 && (
                <div className="rounded-2xl border border-dashed border-border px-4 py-8 text-center">
                    <p className="text-sm font-medium text-foreground">{t('chainEmptyTitle')}</p>
                    <p className="mt-1 text-sm text-muted-foreground">{t('chainEmptyBody')}</p>
                </div>
            )}

            <AnimatePresence initial={false} mode="popLayout">
                {steps.map((step, index) => {
                    const quorumCeiling = availableApprovers(step);
                    const options = Array.from({ length: Math.max(quorumCeiling, 1) }, (_, i) => i + 1);
                    const unpicked = members.filter((member) => !step.userIds.includes(member.id));
                    return (
                        <motion.div
                            key={step.key}
                            layout={reduceMotion ? false : 'position'}
                            initial={reduceMotion ? false : { opacity: 0, y: -4 }}
                            animate={{ opacity: 1, y: 0 }}
                            exit={reduceMotion
                                ? { opacity: 0 }
                                : { opacity: 0, y: -4, transition: { duration: 0.12, ease: easeOut } }}
                            transition={reduceMotion ? instant : springSnappy}
                            className="rounded-2xl border border-border bg-card p-3 shadow-xs"
                        >
                            <div className="flex items-start gap-2">
                                <div className="flex min-w-0 flex-1 flex-col gap-2">
                                    <div className="flex flex-wrap items-center gap-2 text-sm text-muted-foreground">
                                        <span className="font-medium text-foreground">
                                            {t('stepNumber', { number: index + 1 })}
                                        </span>
                                        <span>{t('stepNeeds')}</span>
                                        <Select
                                            value={String(step.requiredCount)}
                                            onValueChange={(value) =>
                                                updateStep(index, { requiredCount: Number(value) })
                                            }
                                            disabled={disabled}
                                        >
                                            <SelectTrigger
                                                size="sm"
                                                aria-label={t('stepQuorumLabel')}
                                                className="w-[4.5rem] shrink-0"
                                            >
                                                <SelectValue />
                                            </SelectTrigger>
                                            <SelectContent>
                                                {options.map((option) => (
                                                    <SelectItem key={option} value={String(option)}>
                                                        {option}
                                                    </SelectItem>
                                                ))}
                                            </SelectContent>
                                        </Select>
                                        <span>{t('stepApprovalsFrom')}</span>
                                        <Select
                                            value={step.kind}
                                            onValueChange={(value) => {
                                                if (isApprovalApproverKind(value)) {
                                                    updateStep(index, {
                                                        kind: value,
                                                        requiredCount: 1,
                                                        ...(value === 'any_approver'
                                                            ? { onExpiry: 'expire' }
                                                            : {}),
                                                    });
                                                }
                                            }}
                                            disabled={disabled}
                                        >
                                            <SelectTrigger
                                                size="sm"
                                                aria-label={t('stepApproversLabel')}
                                                className="w-52 shrink-0"
                                            >
                                                <SelectValue />
                                            </SelectTrigger>
                                            <SelectContent>
                                                <SelectItem value="any_approver">{t('approverAny')}</SelectItem>
                                                <SelectItem value="user">{t('approverNamed')}</SelectItem>
                                            </SelectContent>
                                        </Select>
                                    </div>

                                    {step.kind === 'user' && (
                                        <div className="flex flex-col gap-2">
                                            <Autocomplete
                                                items={unpicked}
                                                value={queries[step.key] ?? ''}
                                                onValueChange={(value, eventDetails) => {
                                                    if (eventDetails.reason === 'escape-key') {
                                                        eventDetails.allowPropagation();
                                                        return;
                                                    }
                                                    const picked = unpicked.find(
                                                        (member) => optionOf(member) === value,
                                                    );
                                                    if (picked) {
                                                        addApprover(index, picked.id);
                                                        setQueries((prev) => ({ ...prev, [step.key]: '' }));
                                                        return;
                                                    }
                                                    setQueries((prev) => ({ ...prev, [step.key]: value }));
                                                }}
                                                mode="list"
                                                openOnInputClick
                                            >
                                                <AutocompleteInput
                                                    placeholder={t('addApproverPlaceholder')}
                                                    aria-label={t('addApproverPlaceholder')}
                                                    disabled={disabled}
                                                    className="w-full min-w-0"
                                                />
                                                <AutocompleteContent>
                                                    <AutocompleteEmpty>{t('noApproverMatches')}</AutocompleteEmpty>
                                                    <AutocompleteList>
                                                        {(member: WorkspaceMember) => (
                                                            <AutocompleteItem key={member.id} value={optionOf(member)}>
                                                                {optionOf(member)}
                                                            </AutocompleteItem>
                                                        )}
                                                    </AutocompleteList>
                                                </AutocompleteContent>
                                            </Autocomplete>
                                            {step.userIds.length > 0 && (
                                                <ul className="flex flex-wrap gap-1.5">
                                                    {step.userIds.map((userId) => (
                                                        <li key={userId}>
                                                            <span className="inline-flex items-center gap-1 rounded-full border border-border px-2 py-0.5 text-xs text-foreground">
                                                                {nameOf(userId)}
                                                                <Button
                                                                    variant="ghost"
                                                                    size="icon-sm"
                                                                    aria-label={t('removeApprover', {
                                                                        name: nameOf(userId),
                                                                    })}
                                                                    disabled={disabled}
                                                                    onClick={() => removeApprover(index, userId)}
                                                                    className="size-4 text-muted-foreground"
                                                                >
                                                                    <XMarkIcon className="size-3" />
                                                                </Button>
                                                            </span>
                                                        </li>
                                                    ))}
                                                </ul>
                                            )}
                                        </div>
                                    )}

                                    <Input
                                        value={step.name}
                                        maxLength={255}
                                        placeholder={t('stepNamePlaceholder')}
                                        aria-label={t('stepNameLabel')}
                                        onChange={(e) => updateStep(index, { name: e.target.value })}
                                        disabled={disabled}
                                        className="h-8 text-sm"
                                    />

                                    <div className="grid gap-3 sm:grid-cols-2">
                                        <div className="space-y-1.5">
                                            <Label htmlFor={`step-due-${step.key}`}>
                                                {t('stepDueIntervalLabel')}
                                            </Label>
                                            <Input
                                                id={`step-due-${step.key}`}
                                                type="number"
                                                min={1}
                                                max={MAX_DUE_INTERVAL_HOURS}
                                                step={1}
                                                value={step.dueIntervalHours}
                                                placeholder={t('stepDueIntervalPlaceholder')}
                                                onChange={(event) => {
                                                    const dueIntervalHours = event.target.value;
                                                    updateStep(index, {
                                                        dueIntervalHours,
                                                        ...(dueIntervalHours.trim() === ''
                                                            ? { onExpiry: 'expire' }
                                                            : {}),
                                                    });
                                                }}
                                                disabled={disabled}
                                                aria-invalid={!dueIntervalIsValid(step.dueIntervalHours) || undefined}
                                                className="h-8 text-sm"
                                            />
                                        </div>
                                        <div className="space-y-1.5">
                                            <Label>{t('stepOnExpiryLabel')}</Label>
                                            <Select
                                                value={step.onExpiry}
                                                onValueChange={(value) => {
                                                    if (isApprovalStepExpiryAction(value)) {
                                                        updateStep(index, { onExpiry: value });
                                                    }
                                                }}
                                                disabled={disabled
                                                    || step.kind === 'any_approver'
                                                    || step.dueIntervalHours.trim() === ''}
                                            >
                                                <SelectTrigger
                                                    size="sm"
                                                    aria-label={t('stepOnExpiryLabel')}
                                                    className="w-full"
                                                >
                                                    <SelectValue />
                                                </SelectTrigger>
                                                <SelectContent>
                                                    <SelectItem value="expire">
                                                        {t('stepOnExpiryExpire')}
                                                    </SelectItem>
                                                    {step.kind === 'user' && (
                                                        <SelectItem value="escalate">
                                                            {t('stepOnExpiryEscalate')}
                                                        </SelectItem>
                                                    )}
                                                </SelectContent>
                                            </Select>
                                        </div>
                                    </div>
                                    <p className={dueIntervalIsValid(step.dueIntervalHours)
                                        ? 'text-xs text-muted-foreground'
                                        : 'text-xs text-destructive'}
                                    >
                                        {dueIntervalIsValid(step.dueIntervalHours)
                                            ? t('stepDueIntervalHint')
                                            : t('stepDueIntervalInvalid')}
                                    </p>
                                </div>

                                <div className="flex shrink-0 items-center gap-0.5">
                                    <Button
                                        ref={(node) => {
                                            if (node) moveButtons.current.set(`${step.key}:-1`, node);
                                            else moveButtons.current.delete(`${step.key}:-1`);
                                        }}
                                        variant="ghost"
                                        size="icon-sm"
                                        aria-label={t('moveStepUp')}
                                        disabled={disabled || index === 0}
                                        onClick={() => move(index, -1)}
                                        className="text-muted-foreground"
                                    >
                                        <ArrowUpIcon className="size-4" />
                                    </Button>
                                    <Button
                                        ref={(node) => {
                                            if (node) moveButtons.current.set(`${step.key}:1`, node);
                                            else moveButtons.current.delete(`${step.key}:1`);
                                        }}
                                        variant="ghost"
                                        size="icon-sm"
                                        aria-label={t('moveStepDown')}
                                        disabled={disabled || index === steps.length - 1}
                                        onClick={() => move(index, 1)}
                                        className="text-muted-foreground"
                                    >
                                        <ArrowDownIcon className="size-4" />
                                    </Button>
                                    <Button
                                        variant="ghost"
                                        size="icon-sm"
                                        aria-label={t('removeStep')}
                                        disabled={disabled}
                                        onClick={() => onStepsChange(steps.filter((_, i) => i !== index))}
                                        className="text-muted-foreground"
                                    >
                                        <XMarkIcon className="size-4" />
                                    </Button>
                                </div>
                            </div>
                        </motion.div>
                    );
                })}
            </AnimatePresence>

            <div>
                <Button
                    variant="outline"
                    size="sm"
                    className="gap-1.5"
                    disabled={disabled || steps.length >= MAX_STEPS}
                    onClick={() => onStepsChange([...steps, newChainStep()])}
                >
                    <PlusIcon className="size-4" />
                    {t('addStep')}
                </Button>
            </div>
        </div>
    );
}
