'use client';

import { type ReactNode } from 'react';
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription, DialogFooter, DialogClose } from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';
import { Loader2Icon } from 'lucide-react';
import { useTranslations } from 'next-intl';
import { type SelectionId } from '@/app/components/records/types';

type Props<T> = {
    open: boolean;
    onOpenChange: (open: boolean) => void;
    selectedIds: Set<SelectionId>;
    selectedItems: T[];
    entityLabel: string;
    getDisplayName?: (item: T) => string;
    /** Surface-specific consequence or preview shown under the shared body — never a second grammar. */
    details?: ReactNode;
    isDeleting: boolean;
    confirmDelete: () => void;
};

/**
 * The one destructive confirmation: "Delete {object}?", a body naming what goes and ending
 * "This can't be undone.", and a destructive "Delete". Surfaces add facts through `details`
 * rather than rewriting the grammar; "Delete permanently" is reserved for organization-level
 * permanent deletion, which asks for typed confirmation of its own.
 */
export default function DeleteRecordDialog<T>({
    open,
    onOpenChange,
    selectedIds,
    selectedItems,
    entityLabel,
    getDisplayName,
    details,
    isDeleting,
    confirmDelete,
}: Props<T>) {
    const t = useTranslations('RecordsDeleteDialog');
    const count = selectedIds.size;
    const single = count === 1 ? selectedItems[0] : null;
    return (
        <Dialog open={open} onOpenChange={onOpenChange}>
            <DialogContent>
                <DialogHeader>
                    <DialogTitle>
                        {count === 1 ? t('titleSingle', { entityLabel }) : t('titleMultiple', { count, entityLabel })}
                    </DialogTitle>
                    <DialogDescription>
                        {single && getDisplayName
                            ? t('descriptionNamed', { name: getDisplayName(single) })
                            : count === 1
                                ? t('descriptionSingle', { entityLabel })
                                : t('descriptionMultiple', { count, entityLabel })}
                    </DialogDescription>
                </DialogHeader>
                {details}
                <DialogFooter>
                    <DialogClose asChild>
                        <Button variant="outline" disabled={isDeleting}>{t('cancel')}</Button>
                    </DialogClose>
                    <Button
                        variant="destructive"
                        className="bg-destructive text-destructive-foreground hover:bg-destructive/90"
                        disabled={isDeleting}
                        onClick={confirmDelete}
                    >
                        {isDeleting ? <Loader2Icon className="size-4 animate-spin" /> : t('delete')}
                    </Button>
                </DialogFooter>
            </DialogContent>
        </Dialog>
    );
}