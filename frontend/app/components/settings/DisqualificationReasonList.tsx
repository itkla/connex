'use client';

import { ArchiveBoxIcon, ArrowUturnLeftIcon, PencilSquareIcon } from '@heroicons/react/24/outline';
import { useTranslations } from 'next-intl';

import { disqualificationReasonLabel } from '@/app/lib/contactLifecycle';
import type { DisqualificationReason } from '@/app/lib/types';
import { Button } from '@/components/ui/button';

type DisqualificationReasonListProps = {
    reasons: DisqualificationReason[];
    onEdit: (reason: DisqualificationReason) => void;
    onArchive: (reason: DisqualificationReason) => void;
    onRestore: (reason: DisqualificationReason) => void;
};

/** Configured disqualification reasons with their available row actions. */
export function DisqualificationReasonList({
    reasons,
    onEdit,
    onArchive,
    onRestore,
}: DisqualificationReasonListProps) {
    const t = useTranslations('WorkspaceDisqualification');
    const tl = useTranslations('ContactLifecycle');

    return (
        <div className="overflow-hidden rounded-2xl border border-border bg-card">
            <ul className="divide-y divide-border">
                {reasons.map((reason) => (
                    <li key={reason.code} className="flex items-center gap-4 px-5 py-4">
                        <div className="min-w-0 flex-1">
                            <p className="truncate text-sm font-medium text-foreground">
                                {disqualificationReasonLabel(reason.code, reason.label, tl)}
                            </p>
                            <p className="mt-0.5 text-xs text-muted-foreground">
                                {reason.requiresNote ? t('noteRequired') : t('noteOptional')}
                                {' · '}{reason.code}
                            </p>
                        </div>
                        {reason.archivedAt ? (
                            <Button size="inline" variant="ghost" onClick={() => onRestore(reason)}>
                                <ArrowUturnLeftIcon className="size-4" />
                                {t('restore')}
                            </Button>
                        ) : (
                            <div className="flex gap-1">
                                <Button size="inline" variant="ghost" onClick={() => onEdit(reason)}>
                                    <PencilSquareIcon className="size-4" />
                                    {t('edit')}
                                </Button>
                                <Button size="inline" variant="ghost" onClick={() => onArchive(reason)}>
                                    <ArchiveBoxIcon className="size-4" />
                                    {t('archive')}
                                </Button>
                            </div>
                        )}
                    </li>
                ))}
            </ul>
        </div>
    );
}
