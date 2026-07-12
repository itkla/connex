'use client';

import { ResponsiveDialog, ResponsiveDialogContent, ResponsiveDialogHeader, ResponsiveDialogTitle, ResponsiveDialogDescription, ResponsiveDialogFooter, ResponsiveDialogClose } from '@/components/ui/responsive-dialog';
import { Button } from '@/components/ui/button';
import { Loader2Icon } from 'lucide-react';
import { PlusIcon, XMarkIcon, FunnelIcon, FlagIcon } from '@heroicons/react/24/outline';
import { Label } from '@/components/ui/label';
import { type CreatePipelinePayload } from '@/app/lib/types';
import { stageKindOf, type StageKind } from '@/app/components/records/pipelines/QuickEditPipelineSheet';
import { Dispatch, SetStateAction, useEffect } from 'react';
import { useTranslations } from 'next-intl';
import { Select, SelectItem, SelectContent, SelectValue, SelectTrigger } from '@/components/ui/select';
import { useFieldErrors } from '@/app/hooks/useFieldErrors';
import { isFieldError } from '@/app/lib/api';
import { cn } from '@/lib/utils';
import { DialogStatusCover, resolveDialogStatus, fieldInputClass, fieldErrorClass, fieldLeadIconClass } from '@/components/ui/dialog-status-cover';

type Props = {
    open: boolean;
    onOpenChange: (open: boolean) => void;
    payload: CreatePipelinePayload;
    setPayload: Dispatch<SetStateAction<CreatePipelinePayload>>;
    isCreating: boolean;
    isSuccess?: boolean;
    createNewPipeline: () => void | Promise<void>;
};

export default function NewPipelineDialog({
    open,
    onOpenChange,
    payload,
    setPayload,
    isCreating,
    isSuccess = false,
    createNewPipeline,
}: Props) {
    const t = useTranslations('PipelinesNewDialog');
    const stages = payload.stages ?? [];
    const { fieldErrors, reset: resetFieldErrors, clearError, captureFieldErrors } = useFieldErrors();

    const duplicateStageNames = new Set<string>();
    const seenStageNames = new Set<string>();
    for (const s of stages) {
        const key = (s.name ?? '').trim().toLowerCase();
        if (!key) continue;
        if (seenStageNames.has(key)) duplicateStageNames.add(key);
        else seenStageNames.add(key);
    }

    useEffect(() => {
        if (!open) resetFieldErrors();
    }, [open, resetFieldErrors]);

    const handleCreate = async () => {
        resetFieldErrors();
        if (duplicateStageNames.size > 0) return;
        try {
            await createNewPipeline();
        } catch (err) {
            captureFieldErrors(err);
            if (isFieldError(err) && err.fieldErrors.name) {
                requestAnimationFrame(() => document.getElementById('pipeline-name')?.focus());
            }
        }
    };

    const updateStageName = (index: number, name: string) => {
        setPayload((prev) => {
            const next = [...(prev.stages ?? [])];
            next[index] = { ...next[index], name };
            return { ...prev, stages: next };
        });
    };

    const updateStageKind = (index: number, kind: StageKind) => {
        setPayload((prev) => {
            const next = [...(prev.stages ?? [])];
            next[index] = { ...next[index], success: kind === 'won', failure: kind === 'lost' };
            return { ...prev, stages: next };
        });
    };

    const addStage = () => {
        setPayload((prev) => ({
            ...prev,
            stages: [...(prev.stages ?? []), { name: '', success: false, failure: false }],
        }));
    };

    const removeStage = (index: number) => {
        setPayload((prev) => ({
            ...prev,
            stages: (prev.stages ?? []).filter((_, i) => i !== index),
        }));
    };

    const hasErrors = Object.keys(fieldErrors).length > 0;
    const status = resolveDialogStatus({ isLoading: isCreating, hasErrors, isSuccess });

    const handleOpenChange = (next: boolean) => {
        if (!next && isCreating) return;
        onOpenChange(next);
    };

    return (
        <ResponsiveDialog open={open} onOpenChange={handleOpenChange}>
            <ResponsiveDialogContent className="gap-0 overflow-hidden p-0 sm:max-w-lg">
                <DialogStatusCover status={status} />

                <div className="px-6 pb-6">
                    <ResponsiveDialogHeader className="ncd-rise -mt-12 mb-5" style={{ animationDelay: '40ms' }}>
                        <ResponsiveDialogTitle className="text-xl font-semibold tracking-tight">{t('title')}</ResponsiveDialogTitle>
                        <ResponsiveDialogDescription>
                            {t('description')}
                        </ResponsiveDialogDescription>
                    </ResponsiveDialogHeader>

                    <form
                        onSubmit={(e) => {
                            e.preventDefault();
                            if (isCreating) return;
                            handleCreate();
                        }}
                        className="grid gap-5"
                    >
                        <div className="ncd-rise grid gap-1.5" style={{ animationDelay: '90ms' }}>
                            <Label htmlFor="pipeline-name">{t('name')}</Label>
                            <div className="group relative">
                                <FunnelIcon className={fieldLeadIconClass} />
                                <input
                                    id="pipeline-name"
                                    type="text"
                                    value={payload.name ?? ''}
                                    onChange={(e) => {
                                        setPayload((prev) => ({ ...prev, name: e.target.value }));
                                        clearError('name');
                                    }}
                                    className={cn(fieldInputClass, 'pl-9 pr-3', fieldErrors.name && fieldErrorClass)}
                                    placeholder={t('namePlaceholder')}
                                    aria-invalid={Boolean(fieldErrors.name)}
                                    aria-describedby={fieldErrors.name ? 'pipeline-name-error' : undefined}
                                    autoFocus
                                    required
                                />
                            </div>
                            {fieldErrors.name && (
                                <p id="pipeline-name-error" className="text-sm text-destructive">{fieldErrors.name}</p>
                            )}
                        </div>

                        <div className="ncd-rise grid gap-1.5" style={{ animationDelay: '140ms' }}>
                            <Label>{t('stages')}</Label>
                            <p className="text-xs text-muted-foreground">
                                {t('stagesHint')}
                            </p>
                            <div className="flex flex-col gap-2">
                                {stages.map((s, i) => {
                                    const isDuplicate = duplicateStageNames.has((s.name ?? '').trim().toLowerCase());
                                    return (
                                        <div key={i} className="flex items-center gap-2">
                                            <div className="group relative flex-1">
                                                <FlagIcon className={fieldLeadIconClass} />
                                                <input
                                                    type="text"
                                                    value={s.name}
                                                    onChange={(e) => updateStageName(i, e.target.value)}
                                                    className={cn(fieldInputClass, 'pl-9 pr-3', isDuplicate && fieldErrorClass)}
                                                    aria-invalid={isDuplicate}
                                                    placeholder={t('stageNamePlaceholder')}
                                                />
                                            </div>
                                            <Select
                                                value={stageKindOf({ success: s.success ?? false, failure: s.failure ?? false })}
                                                onValueChange={(value) => updateStageKind(i, value as StageKind)}
                                                aria-label={t('stageKindAriaLabel')}
                                            >
                                                <SelectTrigger className="h-9 w-32 shrink-0 rounded-lg border-0 bg-muted px-3 shadow-none ring-1 ring-border transition focus-visible:ring-2 focus-visible:ring-brand dark:bg-muted">
                                                    <SelectValue placeholder={t('stageKindPlaceholder')} />
                                                </SelectTrigger>

                                                <SelectContent>
                                                    <SelectItem value="normal">{t('stageInProgress')}</SelectItem>
                                                    <SelectItem value="won">{t('stageWon')}</SelectItem>
                                                    <SelectItem value="lost">{t('stageLost')}</SelectItem>
                                                </SelectContent>
                                            </Select>
                                            <button
                                                type="button"
                                                aria-label={t('removeStageAriaLabel')}
                                                onClick={() => removeStage(i)}
                                                className="flex size-9 shrink-0 items-center justify-center rounded-lg text-muted-foreground transition hover:bg-muted hover:text-destructive active:scale-95"
                                            >
                                                <XMarkIcon className="size-4" />
                                            </button>
                                        </div>
                                    );
                                })}
                                <button
                                    type="button"
                                    onClick={addStage}
                                    className="flex w-full items-center justify-center gap-2 rounded-lg border border-dashed border-border py-2 text-sm text-muted-foreground transition hover:border-brand hover:bg-muted/50 hover:text-foreground active:scale-[0.99]"
                                >
                                    <PlusIcon className="size-4" />
                                    {t('addStage')}
                                </button>
                            </div>
                            {duplicateStageNames.size > 0 && (
                                <p className="text-sm text-destructive">{t('duplicateStageName')}</p>
                            )}
                        </div>

                        <ResponsiveDialogFooter className="ncd-rise mt-5" style={{ animationDelay: '190ms' }}>
                            <ResponsiveDialogClose asChild>
                                <Button type="button" variant="outline" disabled={isCreating}>{t('cancel')}</Button>
                            </ResponsiveDialogClose>
                            <Button
                                type="submit"
                                disabled={isCreating || isSuccess}
                                className="min-w-24 bg-brand text-white shadow-sm transition hover:bg-brand-hover hover:shadow-md"
                            >
                                {isCreating ? <Loader2Icon className="size-4 animate-spin" /> : t('create')}
                            </Button>
                        </ResponsiveDialogFooter>
                    </form>
                </div>
            </ResponsiveDialogContent>
        </ResponsiveDialog>
    );
}