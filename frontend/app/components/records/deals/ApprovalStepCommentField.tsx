'use client';

import { useTranslations } from 'next-intl';

import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';

type Props = {
    comment: string;
    busy: boolean;
    onCommentChange: (comment: string) => void;
};

/** Optional audit comment for an approval-step approver change. */
export default function ApprovalStepCommentField({ comment, busy, onCommentChange }: Props) {
    const t = useTranslations('DealsDocuments');

    return (
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
    );
}
