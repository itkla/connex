'use client';

import { useEffect, useRef, useState, type WheelEvent } from 'react';
import { useRouter } from 'next/navigation';
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
import { toastError, toastSuccess } from '@/app/lib/toast';
import { type WorkspaceMember } from '@/app/lib/types';

type Props = {
    open: boolean;
    onOpenChange: (open: boolean) => void;
    initialOwnerId?: number | null;
    members: WorkspaceMember[];
    onApply: (ownerId: number | null) => Promise<unknown>;
    onSuccess?: () => void;
};

/**
 * Assigns a single record's owner from the workspace members. Clearing the field unassigns the
 * owner (sends a null ownerId); on success it toasts and refreshes the current route.
 */
export default function OwnerAssignDialog({ open, onOpenChange, initialOwnerId, members, onApply, onSuccess }: Props) {
    const t = useTranslations('RecordsOwnerDialog');
    const router = useRouter();
    const [selected, setSelected] = useState<WorkspaceMember | null>(
        () => members.find((member) => member.id === initialOwnerId) ?? null,
    );
    const [isSaving, setIsSaving] = useState(false);
    const [succeeded, setSucceeded] = useState(false);

    const wasOpen = useRef(open);
    useEffect(() => {
        if (open && !wasOpen.current) {
            setSelected(members.find((member) => member.id === initialOwnerId) ?? null);
            setIsSaving(false);
            setSucceeded(false);
        }
        wasOpen.current = open;
    }, [open, initialOwnerId, members]);

    const handleListWheel = (e: WheelEvent<HTMLDivElement>) => {
        const lineHeightPx = 16;
        const delta = e.deltaMode === 1 ? e.deltaY * lineHeightPx : e.deltaY;
        e.currentTarget.scrollTop += delta;
    };

    const handleSave = async () => {
        setIsSaving(true);
        try {
            await onApply(selected ? selected.id : null);
            toastSuccess(t('toastSuccess'));
            setIsSaving(false);
            setSucceeded(true);
            onSuccess?.();
            setTimeout(() => {
                setSucceeded(false);
                onOpenChange(false);
                router.refresh();
            }, 900);
        } catch (err) {
            setIsSaving(false);
            toastError(err instanceof Error ? err.message : t('toastFailed'));
        }
    };

    const status = resolveDialogStatus({ isLoading: isSaving, isSuccess: succeeded });

    const handleOpenChange = (next: boolean) => {
        if (!next && isSaving) return;
        onOpenChange(next);
    };

    return (
        <Dialog open={open} onOpenChange={handleOpenChange}>
            <DialogContent className="gap-0 overflow-hidden p-0 sm:max-w-lg">
                <DialogStatusCover status={status} />

                <div className="px-6 pb-6">
                    <DialogHeader className="ncd-rise -mt-12 mb-5" style={{ animationDelay: '40ms' }}>
                        <DialogTitle className="text-xl font-semibold tracking-tight">{t('title')}</DialogTitle>
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
                            <Label htmlFor="record-owner">{t('ownerLabel')}</Label>
                            <Combobox
                                items={members}
                                itemToStringLabel={(member: WorkspaceMember) => member.displayName}
                                value={selected}
                                onValueChange={(member) => setSelected((member as WorkspaceMember | null) ?? null)}
                            >
                                <ComboboxInput
                                    id="record-owner"
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
                                variant="brand"
                                disabled={isSaving || succeeded}
                                className="min-w-24 shadow-sm transition hover:shadow-md"
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
