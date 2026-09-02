'use client';

import { useMemo } from 'react';
import { useTranslations } from 'next-intl';

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
import { Label } from '@/components/ui/label';
import { SegmentedControl } from '@/components/ui/segmented-control';
import type { DocumentApprovalStep, WorkspaceMember } from '@/app/lib/types';
import {
    MAX_APPROVAL_STEP_APPROVERS,
    type ApprovalMemberDirectoryStatus,
    type ApprovalStepApproverSelection,
} from './approvalStepActions';

type ApproverMode = ApprovalStepApproverSelection['mode'];

const displayName = (member: WorkspaceMember) => member.displayName.trim() || member.username;

type Props = {
    selectedStep: DocumentApprovalStep;
    memberDirectoryStatus: ApprovalMemberDirectoryStatus;
    members: WorkspaceMember[];
    mode: ApproverMode;
    selectedMembers: WorkspaceMember[];
    quorumShortfall: number;
    busy: boolean;
    onRetryMembers: () => void;
    onModeChange: (mode: ApproverMode) => void;
    onSelectedMembersChange: (members: WorkspaceMember[]) => void;
};

function DirectoryUnavailable({ onRetry }: { onRetry: () => void }) {
    const t = useTranslations('DealsDocuments');

    return (
        <div role="alert" className="flex items-center justify-between gap-3 rounded-lg border border-destructive/30 bg-destructive/5 p-3 text-sm text-destructive">
            <span>{t('approvalCandidatesUnavailable')}</span>
            <Button type="button" variant="outline" size="inline" onClick={onRetry}>
                {t('approvalMembersRetry')}
            </Button>
        </div>
    );
}

/** Chooses the approver mode and, when needed, the capped member set. */
export default function ApprovalStepApproverPicker({
    selectedStep,
    memberDirectoryStatus,
    members,
    mode,
    selectedMembers,
    quorumShortfall,
    busy,
    onRetryMembers,
    onModeChange,
    onSelectedMembersChange,
}: Props) {
    const t = useTranslations('DealsDocuments');
    const memberAnchor = useComboboxAnchor();
    const selectedMemberIds = useMemo(
        () => new Set(selectedMembers.map((member) => member.id)),
        [selectedMembers],
    );
    const atMemberCap = selectedMembers.length >= MAX_APPROVAL_STEP_APPROVERS;

    return (
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
                            <DirectoryUnavailable onRetry={onRetryMembers} />
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
                        <DirectoryUnavailable onRetry={onRetryMembers} />
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
                                    {members.map((member) => (
                                        <ComboboxItem
                                            key={member.id}
                                            value={member}
                                            disabled={!selectedMemberIds.has(member.id) && atMemberCap}
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
                                    ))}
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
        </>
    );
}
