'use client';

import { useTranslations } from 'next-intl';

import {
    ResponsiveDialog,
    ResponsiveDialogContent,
    ResponsiveDialogDescription,
    ResponsiveDialogHeader,
    ResponsiveDialogTitle,
} from '@/components/ui/responsive-dialog';
import ConfirmDiscardDialog from '@/app/components/ConfirmDiscardDialog';
import { useUnsavedChangesGuard } from '@/app/hooks/useUnsavedChangesGuard';
import type { DocumentApprovalStep, WorkspaceMember } from '@/app/lib/types';
import ApprovalStepApproverPicker from './ApprovalStepApproverPicker';
import ApprovalStepApproversDialogFooter from './ApprovalStepApproversDialogFooter';
import ApprovalStepCommentField from './ApprovalStepCommentField';
import ApprovalStepPicker from './ApprovalStepPicker';
import {
    MAX_APPROVAL_STEP_APPROVERS,
    approvalStepQuorumShortfall,
    type ApprovalMemberDirectoryStatus,
    type ApprovalStepApproverSelection,
    type ApprovalStepManagementAction,
} from './approvalStepActions';

type ApproverMode = ApprovalStepApproverSelection['mode'];

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
    const selectedStep = steps.find((step) => step.id === selectedStepId) ?? null;
    const invalidStepSelection = steps.length === 0 || (selectedStepId !== null && selectedStep === null);
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
                                <ApprovalStepPicker
                                    steps={steps}
                                    selectedStep={selectedStep}
                                    memberLabelStatus={memberLabelStatus}
                                    memberLabels={memberLabels}
                                    busy={busy}
                                    onStepChange={onStepChange}
                                />

                                {selectedStep && (
                                    <>
                                        <ApprovalStepApproverPicker
                                            selectedStep={selectedStep}
                                            memberDirectoryStatus={memberDirectoryStatus}
                                            members={members}
                                            mode={mode}
                                            selectedMembers={selectedMembers}
                                            quorumShortfall={quorumShortfall}
                                            busy={busy}
                                            onRetryMembers={onRetryMembers}
                                            onModeChange={onModeChange}
                                            onSelectedMembersChange={onSelectedMembersChange}
                                        />
                                        <ApprovalStepCommentField
                                            comment={comment}
                                            busy={busy}
                                            onCommentChange={onCommentChange}
                                        />
                                    </>
                                )}
                            </div>
                        )}

                        <ApprovalStepApproversDialogFooter
                            action={action}
                            busy={busy}
                            canSubmit={canSubmit}
                            onSubmit={onSubmit}
                        />
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
