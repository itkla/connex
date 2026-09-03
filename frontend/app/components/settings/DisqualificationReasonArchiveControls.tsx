'use client';

import { useTranslations } from 'next-intl';

import { Label } from '@/components/ui/label';
import { Switch } from '@/components/ui/switch';

type DisqualificationReasonArchiveControlsProps = {
    archivedCount: number;
    showArchived: boolean;
    onShowArchivedChange: (showArchived: boolean) => void;
};

/** Visibility control for archived disqualification reasons. */
export function DisqualificationReasonArchiveControls({
    archivedCount,
    showArchived,
    onShowArchivedChange,
}: DisqualificationReasonArchiveControlsProps) {
    const t = useTranslations('WorkspaceDisqualification');

    if (archivedCount === 0) return null;

    return (
        <div className="flex items-center gap-3">
            <Switch
                id="show-archived-reasons"
                checked={showArchived}
                onCheckedChange={onShowArchivedChange}
            />
            <Label htmlFor="show-archived-reasons">
                {t('showArchived', { count: archivedCount })}
            </Label>
        </div>
    );
}
