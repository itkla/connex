'use client';

import { useTranslations } from 'next-intl';
import { Loader2Icon } from 'lucide-react';

import { Button } from '@/components/ui/button';
import { ResponsiveDialogClose, ResponsiveDialogFooter } from '@/components/ui/responsive-dialog';
import type { ApprovalStepManagementAction } from './approvalStepActions';

type Props = {
    action: ApprovalStepManagementAction;
    busy: boolean;
    canSubmit: boolean;
    onSubmit: () => void;
};

/** Cancel and submit controls for the approval-step approver dialog. */
export default function ApprovalStepApproversDialogFooter({
    action,
    busy,
    canSubmit,
    onSubmit,
}: Props) {
    const t = useTranslations('DealsDocuments');
    const confirmKey = action === 'escalate' ? 'widenConfirm' : 'reassignConfirm';

    return (
        <ResponsiveDialogFooter>
            <ResponsiveDialogClose asChild>
                <Button variant="outline" disabled={busy}>{t('dialogCancel')}</Button>
            </ResponsiveDialogClose>
            <Button
                variant="brand"
                disabled={!canSubmit}
                onClick={onSubmit}
                aria-label={t(confirmKey)}
            >
                {busy
                    ? <Loader2Icon className="size-4 animate-spin" aria-hidden="true" />
                    : t(confirmKey)}
            </Button>
        </ResponsiveDialogFooter>
    );
}
