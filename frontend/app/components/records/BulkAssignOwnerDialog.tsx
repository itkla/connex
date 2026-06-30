'use client';

import { useEffect, useState, type WheelEvent } from 'react';
import { useTranslations } from 'next-intl';
import { Loader2Icon } from 'lucide-react';
import { UserCircleIcon } from '@heroicons/react/24/outline';

import {
    Combobox,
    ComboboxContent,
    ComboboxEmpty,
    ComboboxInput,
    ComboboxItem,
    ComboboxList,
} from '@/components/ui/combobox';
import { InputGroupAddon } from '@/components/ui/input-group';
import {
    Dialog,
    DialogClose,
    DialogContent,
    DialogDescription,
    DialogFooter,
    DialogHeader,
    DialogTitle,
} from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';
import { Label } from '@/components/ui/label';
import { DialogStatusCover, resolveDialogStatus } from '@/components/ui/dialog-status-cover';
import { notifyBulkResult, type BulkToastMessages } from '@/app/lib/bulkToast';
import { toastError } from '@/app/lib/toast';
import { type BulkOperationResult, type WorkspaceMember } from '@/app/lib/types';

type Props = {
    open: boolean;
    onOpenChange: (open: boolean) => void;
    count: number;
    members: WorkspaceMember[];
    messages: BulkToastMessages;
    onApply: (ownerId: number | null) => Promise<BulkOperationResult>;
    onSuccess?: () => void;
};

/**
 * Assigns one owner across many selected deals in a single batched request. Clearing the field
 * unassigns the owner (sends a null ownerId).
 */
export default function BulkAssignOwnerDialog({ open, onOpenChange, count, members, messages, onApply, onSuccess }: Props) {
    const t = useTranslations('RecordsBulkOwnerDialog');
    const [selected, setSelected] = useState<WorkspaceMember | null>(null);
    const [isSaving, setIsSaving] = useState(false);
    const [succeeded, setSucceeded] = useState(false);

    useEffect(() => {
        if (!open) return;
        setSelected(null);
        setSucceeded(false);
    }, [open]);

    const handleListWheel = (e: WheelEvent<HTMLDivElement>) => {
        const lineHeightPx = 16;
        const delta = e.deltaMode === 1 ? e.deltaY * lineHeightPx : e.deltaY;
        e.currentTarget.scrollTop += delta;
    };

    const handleSave = async () => {
        setIsSaving(true);
        try {
            const result = await onApply(selected ? selected.id : null);
            const anySucceeded = notifyBulkResult(result, messages);
            if (anySucceeded) {
                setSucceeded(true);
                onSuccess?.();
                setTimeout(() => onOpenChange(false), 900);
            }
        } catch (err) {
            toastError(err instanceof Error ? err.message : t('toastFailed'));
        } finally {
            setIsSaving(false);
        }
    };

    const status = resolveDialogStatus({ isLoading: isSaving, isSuccess: succeeded });

    const handleOpenChange = (next: boolean) => {
        if (!next && isSaving) return;
        if (!next) setSucceeded(false);
        onOpenChange(next);
    };

    return (
        <Dialog open={open} onOpenChange={handleOpenChange}>
            <DialogContent className="gap-0 overflow-hidden p-0 sm:max-w-lg">
                <DialogStatusCover status={status} />

                <div className="px-6 pb-6">
                    <DialogHeader className="ncd-rise -mt-12 mb-5" style={{ animationDelay: '40ms' }}>
                        <DialogTitle className="text-xl font-semibold tracking-tight">
                            {t('title', { count })}
                        </DialogTitle>
                        <DialogDescription>{t('description')}</DialogDescription>
                    </DialogHeader>

                    <form
                        onSubmit={(e) => {
                            e.preventDefault();
                            if (isSaving) return;
                            handleSave();
                        }}
                    >
                        <div className="ncd-rise grid gap-2" style={{ animationDelay: '90ms' }}>
                            <Label htmlFor="bulk-owner">{t('ownerLabel')}</Label>
                            <Combobox
                                items={members}
                                itemToStringLabel={(member: WorkspaceMember) => member.displayName}
                                value={selected}
                                onValueChange={(member) => setSelected((member as WorkspaceMember | null) ?? null)}
                            >
                                <ComboboxInput
                                    id="bulk-owner"
                                    showClear
                                    placeholder={t('unassignedPlaceholder')}
                                    className="rounded-lg border-0 bg-muted shadow-none ring-1 ring-border dark:bg-muted has-[[data-slot=input-group-control]:focus-visible]:ring-2 has-[[data-slot=input-group-control]:focus-visible]:ring-brand"
                                >
                                    <InputGroupAddon align="inline-start">
                                        <UserCircleIcon className="size-4 text-muted-foreground transition-colors group-focus-within/input-group:text-brand" />
                                    </InputGroupAddon>
                                </ComboboxInput>
                                <ComboboxContent className="pointer-events-auto">
                                    <ComboboxList onWheel={handleListWheel}>
                                        <ComboboxEmpty>{t('noMembersFound')}</ComboboxEmpty>
                                        {members.map((member) => (
                                            <ComboboxItem key={member.id} value={member}>
                                                {member.displayName}
                                            </ComboboxItem>
                                        ))}
                                    </ComboboxList>
                                </ComboboxContent>
                            </Combobox>
                            <p className="text-xs text-muted-foreground">{t('unassignHint')}</p>
                        </div>

                        <DialogFooter className="ncd-rise mt-5" style={{ animationDelay: '140ms' }}>
                            <DialogClose asChild>
                                <Button type="button" variant="outline" disabled={isSaving}>{t('cancel')}</Button>
                            </DialogClose>
                            <Button
                                type="submit"
                                disabled={isSaving || succeeded}
                                className="min-w-24 bg-brand text-white shadow-sm transition hover:bg-brand-hover hover:shadow-md"
                            >
                                {isSaving ? <Loader2Icon className="size-4 animate-spin" /> : t('apply')}
                            </Button>
                        </DialogFooter>
                    </form>
                </div>
            </DialogContent>
        </Dialog>
    );
}
