'use client';

import { useState, type WheelEvent } from 'react';
import { useTranslations } from 'next-intl';
import { Loader2Icon } from 'lucide-react';
import { Bars3BottomLeftIcon } from '@heroicons/react/24/outline';

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
import { type BulkOperationResult, type Stage } from '@/app/lib/types';

type Props = {
    open: boolean;
    onOpenChange: (open: boolean) => void;
    count: number;
    stages: Stage[];
    mixedPipelines: boolean;
    messages: BulkToastMessages;
    onApply: (stageId: number) => Promise<BulkOperationResult>;
    onSuccess?: () => void;
};

/**
 * Moves many selected deals to one stage in a single batched request. A target stage belongs to a
 * single pipeline, so when the selection spans pipelines the move is blocked here (the backend also
 * rejects cross-pipeline moves per record) and the user is asked to narrow the selection.
 */
export default function BulkChangeStageDialog({ open, onOpenChange, count, stages, mixedPipelines, messages, onApply, onSuccess }: Props) {
    const t = useTranslations('RecordsBulkStageDialog');
    const [selected, setSelected] = useState<Stage | null>(null);
    const [isSaving, setIsSaving] = useState(false);
    const [succeeded, setSucceeded] = useState(false);

    const handleListWheel = (e: WheelEvent<HTMLDivElement>) => {
        const lineHeightPx = 16;
        const delta = e.deltaMode === 1 ? e.deltaY * lineHeightPx : e.deltaY;
        e.currentTarget.scrollTop += delta;
    };

    const handleSave = async () => {
        if (!selected) return;
        setIsSaving(true);
        try {
            const result = await onApply(selected.id);
            const anySucceeded = notifyBulkResult(result, messages);
            if (anySucceeded) {
                setSucceeded(true);
                onSuccess?.();
                setTimeout(() => { setSucceeded(false); setSelected(null); onOpenChange(false); }, 900);
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
        if (!next) { setSucceeded(false); setSelected(null); }
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

                    {mixedPipelines ? (
                        <div className="ncd-rise rounded-lg bg-warning/10 px-4 py-3 text-sm text-warning-foreground ring-1 ring-warning/30" style={{ animationDelay: '90ms' }}>
                            {t('mixedPipelines')}
                        </div>
                    ) : (
                        <form
                            onSubmit={(e) => {
                                e.preventDefault();
                                if (isSaving) return;
                                handleSave();
                            }}
                        >
                            <div className="ncd-rise grid gap-2" style={{ animationDelay: '90ms' }}>
                                <Label htmlFor="bulk-stage">{t('stageLabel')}</Label>
                                <Combobox
                                    items={stages}
                                    itemToStringLabel={(stage: Stage) => stage.name}
                                    value={selected}
                                    onValueChange={(stage) => setSelected((stage as Stage | null) ?? null)}
                                >
                                    <ComboboxInput
                                        id="bulk-stage"
                                        placeholder={t('selectStagePlaceholder')}
                                        className="rounded-lg border-0 bg-muted shadow-none ring-1 ring-border dark:bg-muted has-[[data-slot=input-group-control]:focus-visible]:ring-2 has-[[data-slot=input-group-control]:focus-visible]:ring-brand"
                                    >
                                        <InputGroupAddon align="inline-start">
                                            <Bars3BottomLeftIcon className="size-4 text-muted-foreground transition-colors group-focus-within/input-group:text-brand" />
                                        </InputGroupAddon>
                                    </ComboboxInput>
                                    <ComboboxContent className="pointer-events-auto">
                                        <ComboboxList onWheel={handleListWheel}>
                                            <ComboboxEmpty>{t('noStagesFound')}</ComboboxEmpty>
                                            {stages.map((stage) => (
                                                <ComboboxItem key={stage.id} value={stage}>
                                                    {stage.name}
                                                </ComboboxItem>
                                            ))}
                                        </ComboboxList>
                                    </ComboboxContent>
                                </Combobox>
                            </div>

                            <DialogFooter className="ncd-rise mt-5" style={{ animationDelay: '140ms' }}>
                                <DialogClose asChild>
                                    <Button type="button" variant="outline" disabled={isSaving}>{t('cancel')}</Button>
                                </DialogClose>
                                <Button
                                    type="submit"
                                    disabled={isSaving || succeeded || !selected}
                                    className="min-w-24 bg-brand text-white shadow-sm transition hover:bg-brand-hover hover:shadow-md"
                                >
                                    {isSaving ? <Loader2Icon className="size-4 animate-spin" /> : t('apply')}
                                </Button>
                            </DialogFooter>
                        </form>
                    )}

                    {mixedPipelines && (
                        <DialogFooter className="ncd-rise mt-5" style={{ animationDelay: '140ms' }}>
                            <DialogClose asChild>
                                <Button type="button" variant="outline">{t('close')}</Button>
                            </DialogClose>
                        </DialogFooter>
                    )}
                </div>
            </DialogContent>
        </Dialog>
    );
}
