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
import {
    Select,
    SelectContent,
    SelectItem,
    SelectTrigger,
    SelectValue,
} from '@/components/ui/select';
import { easeOut, instant, springSnappy } from '@/app/lib/motion';
import type {
    ApprovalApproverKind,
    ApprovalChainMode,
    SeparationOfDuties,
    WorkspaceMember,
} from '@/app/lib/types';

/**
 * One step being edited. `userIds` is only meaningful while `kind` is `user`, and `key` is a stable
 * client-side identity so reordering never re-keys the rows around it.
 *
 * `id` is the server's identity for a step that already exists and must survive editing: the
 * backend classifies a step with no id as newly added, which makes the whole save a tightening and
 * invalidates approvals pending under the policy. A step the user just added has no id by design.
 */
export type ChainStepDraft = {
    key: string;
    id?: number;
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
const MAX_QUORUM = 20;

let nextStepKey = 0;

export const newChainStep = (): ChainStepDraft => ({
    key: `new-${nextStepKey++}`,
    name: '',
    requiredCount: 1,
    kind: 'any_approver',
    userIds: [],
});

/**
 * The largest quorum a step may require. A step naming specific members is capped by how many are
 * named; a step open to anyone is capped only by the server's maximum, because the client cannot see
 * which members hold the approve permission and must not guess from the member count.
 */
export const availableApprovers = (step: ChainStepDraft) =>
    step.kind === 'any_approver' ? MAX_QUORUM : step.userIds.length;

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
                    onValueChange={(value) => onModeChange(value as ApprovalChainMode)}
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
                    onValueChange={(value) => onSeparationOfDutiesChange(value as SeparationOfDuties)}
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
                                            onValueChange={(value) =>
                                                updateStep(index, {
                                                    kind: value as ApprovalApproverKind,
                                                    requiredCount: 1,
                                                })
                                            }
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
