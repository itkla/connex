'use client';

import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription, DialogFooter, DialogClose } from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';
import { Loader2Icon } from 'lucide-react';
import { useTranslations } from 'next-intl';
import { type SelectionId } from '@/app/components/records/types';

type Props<T> = {
    open: boolean;
    onOpenChange: (open: boolean) => void;
    mode: 'archive' | 'restore';
    selectedIds: Set<SelectionId>;
    selectedItems: T[];
    entityLabel: string;
    entityLabelPlural: string;
    getDisplayName?: (item: T) => string;
    isPending: boolean;
    onConfirm: () => void;
};

/**
 * Confirms archiving or restoring contacts and companies (issue #854). Archiving replaced deletion
 * for these record types, so the confirm button is a normal primary action rather than a
 * destructive one and the copy states plainly that nothing is lost.
 */
export default function ArchiveRecordDialog<T>({
    open,
    onOpenChange,
    mode,
    selectedIds,
    selectedItems,
    entityLabel,
    entityLabelPlural,
    getDisplayName,
    isPending,
    onConfirm,
}: Props<T>) {
    const t = useTranslations('RecordsArchiveDialog');
    const count = selectedIds.size;
    const single = count === 1 ? selectedItems[0] : null;
    const name = single && getDisplayName ? getDisplayName(single) : null;
    const title = count === 1
        ? t(mode === 'archive' ? 'archiveTitleSingle' : 'restoreTitleSingle', { entityLabel })
        : t(mode === 'archive' ? 'archiveTitleMultiple' : 'restoreTitleMultiple', { count, entityLabel: entityLabelPlural });
    const description = name
        ? t(mode === 'archive' ? 'archiveDescriptionNamed' : 'restoreDescriptionNamed', { name })
        : count === 1
            ? t(mode === 'archive' ? 'archiveDescriptionSingle' : 'restoreDescriptionSingle', { entityLabel })
            : t(mode === 'archive' ? 'archiveDescriptionMultiple' : 'restoreDescriptionMultiple', { count, entityLabel: entityLabelPlural });

    return (
        <Dialog open={open} onOpenChange={onOpenChange}>
            <DialogContent>
                <DialogHeader>
                    <DialogTitle>{title}</DialogTitle>
                    <DialogDescription>{description}</DialogDescription>
                </DialogHeader>
                <DialogFooter>
                    <DialogClose asChild>
                        <Button variant="outline" disabled={isPending}>{t('cancel')}</Button>
                    </DialogClose>
                    <Button disabled={isPending} onClick={onConfirm}>
                        {isPending
                            ? <Loader2Icon className="size-4 animate-spin" />
                            : t(mode === 'archive' ? 'archive' : 'restore')}
                    </Button>
                </DialogFooter>
            </DialogContent>
        </Dialog>
    );
}
