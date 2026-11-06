'use client';

import { ResponsiveDialog, ResponsiveDialogContent, ResponsiveDialogHeader, ResponsiveDialogTitle, ResponsiveDialogDescription, ResponsiveDialogFooter, ResponsiveDialogClose } from '@/components/ui/responsive-dialog';
import { Button } from '@/components/ui/button';
import { IconButton } from '@/components/ui/icon-button';
import { Loader2Icon } from 'lucide-react';
import { PlusIcon, XMarkIcon, FunnelIcon, FlagIcon } from '@heroicons/react/24/outline';
import { Label } from '@/components/ui/label';
import { type CreatePipelinePayload } from '@/app/lib/types';
import { stageKindOf, type StageKind } from '@/app/components/records/pipelines/QuickEditPipelineSheet';
import { Dispatch, SetStateAction, useEffect, useLayoutEffect, useRef, useState } from 'react';
import { useTranslations } from 'next-intl';
import { Select, SelectItem, SelectContent, SelectValue, SelectTrigger } from '@/components/ui/select';
import { useFieldErrors } from '@/app/hooks/useFieldErrors';
import { isFieldError } from '@/app/lib/api';
import { cn } from '@/lib/utils';
import { DialogStatusCover, resolveDialogStatus, fieldInputClass, fieldErrorClass, fieldLeadIconClass } from '@/components/ui/dialog-status-cover';
import { StageOrderControls } from './StageOrderControls';
import { insertOpenStage, moveStage } from './stageDrafts';
import { isSubmitShortcut } from '@/app/lib/submitShortcut';

type Props = {
    open: boolean;
    onOpenChange: (open: boolean) => void;
    payload: CreatePipelinePayload;
    setPayload: Dispatch<SetStateAction<CreatePipelinePayload>>;
    isCreating: boolean;
    isSuccess?: boolean;
    createNewPipeline: () => void | Promise<void>;
    stageError?: string;
    onCloseComplete?: () => void;
};

export default function NewPipelineDialog({
    open,
    onOpenChange,
    payload,
    setPayload,
    isCreating,
    isSuccess = false,
    createNewPipeline,
    stageError,
    onCloseComplete,
}: Props) {
    const t = useTranslations('PipelinesNewDialog');
    const tOrder = useTranslations('PipelineStageOrder');
    const stages = payload.stages ?? [];
    const pendingFocus = useRef<string | null>(null);
    const [announcement, setAnnouncement] = useState('');
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

    useLayoutEffect(() => {
        if (!pendingFocus.current) return;
        document.getElementById(pendingFocus.current)?.focus();
        pendingFocus.current = null;
    });

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
        const terminalIndex = stages.findIndex((stage) => stage.success || stage.failure);
        pendingFocus.current = `new-pipeline-stage-${terminalIndex < 0 ? stages.length : terminalIndex}`;
        setPayload((prev) => ({
            ...prev,
            stages: insertOpenStage(prev.stages ?? [], { name: '', success: false, failure: false }),
        }));
    };

    const removeStage = (index: number) => {
        setPayload((prev) => ({
            ...prev,
            stages: (prev.stages ?? []).filter((_, i) => i !== index),
        }));
    };

    const hasErrors = Object.keys(fieldErrors).length > 0 || Boolean(stageError);
    const status = resolveDialogStatus({ isLoading: isCreating, hasErrors, isSuccess });

    const handleOpenChange = (next: boolean) => {
        if (!next && isCreating) return;
        onOpenChange(next);
    };

    return (
        <ResponsiveDialog open={open} onOpenChange={handleOpenChange} onCloseComplete={onCloseComplete}>
            <ResponsiveDialogContent className="max-h-[calc(100dvh-1rem)] gap-0 overflow-y-auto p-0 sm:max-w-lg">
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
                            e.stopPropagation();
                            if (isCreating) return;
                            handleCreate();
                        }}
                        onKeyDown={(e) => {
                            if (isSubmitShortcut(e)) {
                                e.preventDefault();
                                e.stopPropagation();
                                if (!isCreating && !isSuccess) e.currentTarget.requestSubmit();
                            }
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
                                    disabled={isCreating}
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
                                <p role="status" className="sr-only">{announcement}</p>
                                {stages.map((s, i) => {
                                    const isDuplicate = duplicateStageNames.has((s.name ?? '').trim().toLowerCase());
                                    return (
                                        <div key={i} className="flex flex-wrap items-center gap-2">
                                            <div className="group relative min-w-24 flex-1">
                                                <FlagIcon className={fieldLeadIconClass} />
                                                <input
                                                    id={`new-pipeline-stage-${i}`}
                                                    type="text"
                                                    value={s.name}
                                                    onChange={(e) => updateStageName(i, e.target.value)}
                                                    className={cn(fieldInputClass, 'pl-9 pr-3', isDuplicate && fieldErrorClass)}
                                                    aria-invalid={isDuplicate}
                                                    placeholder={t('stageNamePlaceholder')}
                                                    aria-label={tOrder('stageName', { position: i + 1 })}
                                                    disabled={isCreating}
                                                />
                                            </div>
                                            <Select
                                                value={stageKindOf({ success: s.success ?? false, failure: s.failure ?? false })}
                                                onValueChange={(value) => {
                                                    if (value === 'normal' || value === 'won' || value === 'lost') updateStageKind(i, value);
                                                }}
                                                disabled={isCreating}
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
                                            <StageOrderControls
                                                id={`new-pipeline-stage-${i}`}
                                                index={i}
                                                count={stages.length}
                                                disabled={isCreating}
                                                onMove={(to, direction) => {
                                                    pendingFocus.current = `new-pipeline-stage-${to}-${direction}`;
                                                    setPayload((prev) => ({ ...prev, stages: moveStage(prev.stages ?? [], i, to) }));
                                                    setAnnouncement(tOrder('moved', { name: s.name, position: to + 1, count: stages.length }));
                                                }}
                                            />
                                            <IconButton
                                                type="button"
                                                variant="ghost"
                                                size="icon-inline"
                                                label={t('removeStageAriaLabel')}
                                                disabled={isCreating}
                                                onClick={() => removeStage(i)}
                                                className="shrink-0 text-muted-foreground hover:text-destructive"
                                            >
                                                <XMarkIcon className="size-4" />
                                            </IconButton>
                                        </div>
                                    );
                                })}
                                <Button
                                    type="button"
                                    variant="outline"
                                    disabled={isCreating}
                                    onClick={addStage}
                                >
                                    <PlusIcon className="size-4" />
                                    {t('addStage')}
                                </Button>
                            </div>
                            {duplicateStageNames.size > 0 && (
                                <p className="text-sm text-destructive">{t('duplicateStageName')}</p>
                            )}
                            {stageError && <p role="alert" className="text-sm text-destructive">{stageError}</p>}
                        </div>

                        <ResponsiveDialogFooter className="ncd-rise mt-5" style={{ animationDelay: '190ms' }}>
                            <ResponsiveDialogClose asChild>
                                <Button type="button" variant="outline" disabled={isCreating}>{t('cancel')}</Button>
                            </ResponsiveDialogClose>
                            <Button
                                type="submit"
                                variant="brand"
                                disabled={isCreating || isSuccess}
                                className="min-w-24 shadow-sm transition hover:shadow-md"
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
