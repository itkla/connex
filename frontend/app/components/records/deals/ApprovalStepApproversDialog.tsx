'use client';

import { useMemo } from 'react';
import { useLocale, useTranslations } from 'next-intl';
import { Loader2Icon } from 'lucide-react';

import { Button } from '@/components/ui/button';
import {
    Combobox,
    ComboboxChip,
    ComboboxChips,
    ComboboxChipsInput,
    ComboboxContent,
    ComboboxEmpty,
    ComboboxItem,
    ComboboxList,
    ComboboxValue,
    useComboboxAnchor,
} from '@/components/ui/combobox';
import {
    ResponsiveDialog,
    ResponsiveDialogClose,
    ResponsiveDialogContent,
    ResponsiveDialogDescription,
    ResponsiveDialogFooter,
    ResponsiveDialogHeader,
    ResponsiveDialogTitle,
} from '@/components/ui/responsive-dialog';
import { Label } from '@/components/ui/label';
import {
    Select,
    SelectContent,
    SelectItem,
    SelectTrigger,
    SelectValue,
} from '@/components/ui/select';
import { SegmentedControl } from '@/components/ui/segmented-control';
import { Textarea } from '@/components/ui/textarea';
import ConfirmDiscardDialog from '@/app/components/ConfirmDiscardDialog';
import { useUnsavedChangesGuard } from '@/app/hooks/useUnsavedChangesGuard';
import type { DocumentApprovalStep, WorkspaceMember } from '@/app/lib/types';
import {
    MAX_APPROVAL_STEP_APPROVERS,
    approvalStepQuorumShortfall,
    type ApprovalMemberDirectoryStatus,
    type ApprovalStepManagementAction,
} from './approvalStepActions';

type ApproverMode = 'members' | 'any_approver';

const displayName = (member: WorkspaceMember) => member.displayName.trim() || member.username;

type Props = {
    open: boolean;
    action: ApprovalStepManagementAction;
    documentTitle: string;
    steps: DocumentApprovalStep[];
    selectedStepId: number | null;
    memberDirectoryStatus: ApprovalMemberDirectoryStatus;
    members: WorkspaceMember[];
    verifiedApproverIds: number[];
    memberLabelStatus: ApprovalMemberDirectoryStatus;
    memberLabels: WorkspaceMember[];
    mode: ApproverMode;
    selectedMembers: WorkspaceMember[];
    comment: string;
    busy: boolean;
    onOpenChange: (open: boolean) => void;
    onStepChange: (stepId: number) => void;
    onRetryMembers: () => void;
    onModeChange: (mode: ApproverMode) => void;
    onSelectedMembersChange: (members: WorkspaceMember[]) => void;
    onCommentChange: (comment: string) => void;
    onSubmit: () => void;
};

/** Presentational dialog for widening or replacing one active approval step's approver set. */
export default function ApprovalStepApproversDialog({
    open,
    action,
    documentTitle,
    steps,
    selectedStepId,
    memberDirectoryStatus,
    members,
    verifiedApproverIds,
    memberLabelStatus,
    memberLabels,
    mode,
    selectedMembers,
    comment,
    busy,
    onOpenChange,
    onStepChange,
    onRetryMembers,
    onModeChange,
    onSelectedMembersChange,
    onCommentChange,
    onSubmit,
}: Props) {
    const t = useTranslations('DealsDocuments');
    const locale = useLocale();
    const listFormatter = useMemo(
        () => new Intl.ListFormat(locale, { style: 'long', type: 'conjunction' }),
        [locale],
    );
    const memberAnchor = useComboboxAnchor();
    const selectedStep = steps.find((step) => step.id === selectedStepId) ?? null;
    const invalidStepSelection = steps.length === 0 || (selectedStepId !== null && selectedStep === null);
    const atMemberCap = selectedMembers.length >= MAX_APPROVAL_STEP_APPROVERS;
    const quorumShortfall = selectedStep === null
        ? 0
        : approvalStepQuorumShortfall(
            selectedStep,
            action,
            mode === 'any_approver'
                ? { mode: 'any_approver' }
                : { mode: 'members', memberIds: selectedMembers.map((member) => member.id) },
            verifiedApproverIds,
        );
    const canSubmit = selectedStep !== null
        && (mode === 'any_approver' || selectedMembers.length > 0)
        && selectedMembers.length <= MAX_APPROVAL_STEP_APPROVERS
        && comment.length <= 500
        && memberDirectoryStatus === 'ready'
        && quorumShortfall === 0
        && !busy;
    const stepName = (step: DocumentApprovalStep) =>
        step.name?.trim() || t('chainStep', { number: step.stepOrder });
    const currentApprovers = () => {
        if (!selectedStep) return t('approvalStepChanged');
        if (selectedStep.effectiveAnyApprover) return t('approvalAnyApprover');
        if (memberLabelStatus === 'loading') return t('approvalMembersLoading');
        if (memberLabelStatus !== 'ready') return t('approvalMembersUnavailable');
        if (selectedStep.effectiveApproverIds.length === 0) return t('approvalNoCurrentApprovers');
        const names = selectedStep.effectiveApproverIds.map((id) => {
            const member = memberLabels.find((candidate) => candidate.id === id);
            return member ? displayName(member) : t('chainFormerMember', { id });
        });
        return listFormatter.format(names);
    };
    const guard = useUnsavedChangesGuard({
        isDirty: mode === 'any_approver' || selectedMembers.length > 0 || comment.length > 0,
        onClose: () => onOpenChange(false),
        enabled: open && !busy,
    });

    return (
        <>
            <ResponsiveDialog open={open} onOpenChange={guard.onOpenChange}>
                <ResponsiveDialogContent className="gap-0 overflow-hidden p-0 sm:max-w-lg">
                    <div className="grid gap-6 p-6">
                    <ResponsiveDialogHeader>
                        <ResponsiveDialogTitle>
                            {t(action === 'escalate' ? 'widenDialogTitle' : 'reassignDialogTitle')}
                        </ResponsiveDialogTitle>
                        <ResponsiveDialogDescription>
                            {t(action === 'escalate' ? 'widenDialogBody' : 'reassignDialogBody', {
                                title: documentTitle,
                            })}
                        </ResponsiveDialogDescription>
                    </ResponsiveDialogHeader>

                    {invalidStepSelection ? (
                        <p role="alert" className="rounded-lg border border-destructive/30 bg-destructive/5 p-3 text-sm text-destructive">
                            {t('approvalStepChanged')}
                        </p>
                    ) : (
                        <div className="space-y-5">
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

                            {selectedStep && (
                                <>
                                <div className="space-y-2">
                                    <Label>{t('approvalApproverModeLabel')}</Label>
                                    <SegmentedControl<ApproverMode>
                                        value={mode}
                                        onChange={onModeChange}
                                        ariaLabel={t('approvalApproverModeLabel')}
                                        options={[
                                            { value: 'members', label: t('approvalNamedMembers'), disabled: busy },
                                            { value: 'any_approver', label: t('approvalAnyApprover'), disabled: busy },
                                        ]}
                                    />
                                    {mode === 'any_approver' && (
                                        <>
                                            <p className="text-xs text-muted-foreground">{t('approvalAnyApproverHint')}</p>
                                            {memberDirectoryStatus === 'loading' && (
                                                <p className="text-xs text-muted-foreground">{t('approvalCandidatesLoading')}</p>
                                            )}
                                            {memberDirectoryStatus === 'unavailable' && (
                                                <div role="alert" className="flex items-center justify-between gap-3 rounded-lg border border-destructive/30 bg-destructive/5 p-3 text-sm text-destructive">
                                                    <span>{t('approvalCandidatesUnavailable')}</span>
                                                    <Button type="button" variant="outline" size="inline" onClick={onRetryMembers}>
                                                        {t('approvalMembersRetry')}
                                                    </Button>
                                                </div>
                                            )}
                                            {memberDirectoryStatus === 'ready' && members.length === 0 && (
                                                <p role="alert" className="text-xs text-destructive">
                                                    {t('approvalCandidatesNoMatches')}
                                                </p>
                                            )}
                                        </>
                                    )}
                                </div>

                                {mode === 'members' && (
                                    <div className="space-y-2">
                                        <Label htmlFor={memberDirectoryStatus === 'unavailable'
                                            ? undefined
                                            : 'approval-management-members'}>
                                            {t('approvalMembersLabel')}
                                        </Label>
                                        {memberDirectoryStatus === 'unavailable' ? (
                                            <div role="alert" className="flex items-center justify-between gap-3 rounded-lg border border-destructive/30 bg-destructive/5 p-3 text-sm text-destructive">
                                                <span>{t('approvalCandidatesUnavailable')}</span>
                                                <Button type="button" variant="outline" size="inline" onClick={onRetryMembers}>
                                                    {t('approvalMembersRetry')}
                                                </Button>
                                            </div>
                                        ) : (
                                            <Combobox
                                                items={members}
                                                value={selectedMembers}
                                                onValueChange={(next) => onSelectedMembersChange(
                                                    next.slice(0, MAX_APPROVAL_STEP_APPROVERS),
                                                )}
                                                itemToStringLabel={displayName}
                                                isItemEqualToValue={(member, selected) => member.id === selected.id}
                                                multiple
                                                disabled={busy || memberDirectoryStatus !== 'ready'}
                                            >
                                                <ComboboxChips ref={memberAnchor}>
                                                    <ComboboxValue>
                                                        {(selected: WorkspaceMember[]) => (
                                                            <>
                                                                {selected.map((member) => (
                                                                    <ComboboxChip
                                                                        key={member.id}
                                                                        removeLabel={t('approvalRemoveMember', {
                                                                            name: displayName(member),
                                                                        })}
                                                                    >
                                                                        {displayName(member)}
                                                                    </ComboboxChip>
                                                                ))}
                                                                <ComboboxChipsInput
                                                                    id="approval-management-members"
                                                                    placeholder={selected.length === 0
                                                                        ? t(memberDirectoryStatus === 'loading'
                                                                            ? 'approvalCandidatesLoading'
                                                                            : 'approvalCandidatesPlaceholder')
                                                                        : undefined}
                                                                    aria-describedby="approval-management-members-limit"
                                                                    disabled={busy || memberDirectoryStatus !== 'ready'}
                                                                />
                                                            </>
                                                        )}
                                                    </ComboboxValue>
                                                </ComboboxChips>
                                                <ComboboxContent anchor={memberAnchor} className="pointer-events-auto">
                                                    <ComboboxList>
                                                        <ComboboxEmpty>{t('approvalCandidatesNoMatches')}</ComboboxEmpty>
                                                        {members.map((member) => {
                                                            const selected = selectedMembers.some(
                                                                (candidate) => candidate.id === member.id,
                                                            );
                                                            return (
                                                                <ComboboxItem
                                                                    key={member.id}
                                                                    value={member}
                                                                    disabled={!selected && atMemberCap}
                                                                >
                                                                    <span className="min-w-0">
                                                                        <span className="block truncate font-medium text-foreground">
                                                                            {displayName(member)}
                                                                        </span>
                                                                        <span className="block truncate text-xs text-muted-foreground">
                                                                            {member.email}
                                                                        </span>
                                                                    </span>
                                                                </ComboboxItem>
                                                            );
                                                        })}
                                                    </ComboboxList>
                                                </ComboboxContent>
                                            </Combobox>
                                        )}
                                        <p id="approval-management-members-limit" className="text-xs text-muted-foreground">
                                            {t('approvalMembersLimit', {
                                                selected: selectedMembers.length,
                                                maximum: MAX_APPROVAL_STEP_APPROVERS,
                                            })}
                                        </p>
                                    </div>
                                )}

                                {memberDirectoryStatus === 'ready'
                                    && selectedStep.requiredCount > 1
                                    && quorumShortfall > 0 && (
                                    <p role="alert" className="text-xs text-destructive">
                                        {t('approvalQuorumShortfall', { count: quorumShortfall })}
                                    </p>
                                )}

                                <div className="space-y-2">
                                    <Label htmlFor="approval-management-comment">{t('commentLabel')}</Label>
                                    <Textarea
                                        id="approval-management-comment"
                                        rows={3}
                                        maxLength={500}
                                        value={comment}
                                        placeholder={t('approvalManagementCommentPlaceholder')}
                                        onChange={(event) => onCommentChange(event.target.value)}
                                        disabled={busy}
                                    />
                                </div>
                                </>
                            )}
                        </div>
                    )}

                    <ResponsiveDialogFooter>
                        <ResponsiveDialogClose asChild>
                            <Button variant="outline" disabled={busy}>{t('dialogCancel')}</Button>
                        </ResponsiveDialogClose>
                        <Button
                            variant="brand"
                            disabled={!canSubmit}
                            onClick={onSubmit}
                            aria-label={t(action === 'escalate' ? 'widenConfirm' : 'reassignConfirm')}
                        >
                            {busy
                                ? <Loader2Icon className="size-4 animate-spin" aria-hidden="true" />
                                : t(action === 'escalate' ? 'widenConfirm' : 'reassignConfirm')}
                        </Button>
                    </ResponsiveDialogFooter>
                    </div>
                </ResponsiveDialogContent>
            </ResponsiveDialog>
            <ConfirmDiscardDialog
                open={guard.confirm.open}
                onKeepEditing={guard.confirm.onKeepEditing}
                onDiscard={guard.confirm.onDiscard}
            />
        </>
    );
}
