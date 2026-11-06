'use client';

import { useTranslations } from 'next-intl';
import { useLayoutEffect, useRef, useState } from 'react';
import { PlusIcon, XMarkIcon, FunnelIcon } from '@heroicons/react/24/outline';

import { Input } from '@/components/ui/input';
import { Button } from '@/components/ui/button';
import { IconButton } from '@/components/ui/icon-button';
import { Label } from '@/components/ui/label';
import { Select, SelectTrigger, SelectContent, SelectItem, SelectValue } from '@/components/ui/select';
import { type Pipeline } from '@/app/lib/types';
import { type SelectionId } from '@/app/components/records/types';
import { cn } from '@/lib/utils';
import {
    QuickEditField,
    QuickEditRecordCard,
    QuickEditSheetShell,
} from '@/app/components/records/quick-edit/QuickEditSheetShell';
import { quickEditErrorId } from '@/app/hooks/useFieldErrors';
import { StageOrderControls } from './StageOrderControls';
import { moveStage } from './stageDrafts';

export type StageKind = 'normal' | 'won' | 'lost';

export function stageKindOf(s: { success: boolean; failure: boolean }): StageKind {
    if (s.success) return 'won';
    if (s.failure) return 'lost';
    return 'normal';
}

const STAGE_DOT: Record<StageKind, string> = {
    won: 'bg-brand',
    lost: 'bg-destructive',
    normal: 'bg-muted-foreground/40',
};

export type PipelineStageDraft = {
    id: number | null;
    name: string;
    success: boolean;
    failure: boolean;
};

export type PipelineDraft = {
    name: string;
    stages: PipelineStageDraft[];
};

type Props = {
    open: boolean;
    onOpenChange: (open: boolean) => void;
    selectedIds: Set<SelectionId>;
    selectedPipelines: Pipeline[];
    drafts: Record<number, PipelineDraft>;
    updateDraft: (id: number, patch: Partial<PipelineDraft>) => void;
    updateStageName: (pipelineId: number, index: number, name: string) => void;
    updateStageKind: (pipelineId: number, index: number, kind: StageKind) => void;
    addStage: (pipelineId: number) => void;
    removeStage: (pipelineId: number, index: number) => void;
    isSaving: boolean;
    saveEdits: () => void;
    /** Per-pipeline inline validation messages, keyed by pipeline id then `name` or `stages`. */
    fieldErrors?: Record<number, Record<string, string>>;
};

export default function QuickEditPipelineSheet({
    open,
    onOpenChange,
    selectedPipelines,
    drafts,
    updateDraft,
    updateStageName,
    updateStageKind,
    addStage,
    removeStage,
    isSaving,
    saveEdits,
    fieldErrors,
}: Props) {
    const t = useTranslations('PipelinesQuickEditSheet');
    const total = selectedPipelines.length;
    const tOrder = useTranslations('PipelineStageOrder');
    const pendingFocus = useRef<string | null>(null);
    const [announcement, setAnnouncement] = useState('');

    useLayoutEffect(() => {
        if (!pendingFocus.current) return;
        document.getElementById(pendingFocus.current)?.focus();
        pendingFocus.current = null;
    });

    return (
        <QuickEditSheetShell
            open={open}
            onOpenChange={onOpenChange}
            icon={<FunnelIcon />}
            title={total === 1 ? t('titleSingle') : t('titleMultiple', { count: total })}
            description={t('description')}
            count={total}
            isSaving={isSaving}
            onSave={saveEdits}
            saveLabel={t('save')}
            cancelLabel={t('cancel')}
            dirtySnapshot={drafts}
        >
            <p role="status" className="sr-only">{announcement}</p>
            {selectedPipelines.map((p, idx) => {
                const draft = drafts[p.id];
                if (!draft) return null;
                return (
                    <QuickEditRecordCard key={p.id} index={idx} total={total} title={p.name}>
                        <QuickEditField
                            label={t('name')}
                            htmlFor={`name-${p.id}`}
                            required
                            error={fieldErrors?.[p.id]?.name}
                        >
                            <Input
                                id={`name-${p.id}`}
                                type="text"
                                value={draft.name}
                                onChange={(e) => updateDraft(p.id, { name: e.target.value })}
                                aria-invalid={Boolean(fieldErrors?.[p.id]?.name)}
                                aria-describedby={fieldErrors?.[p.id]?.name ? quickEditErrorId(`name-${p.id}`) : undefined}
                                required
                            />
                        </QuickEditField>

                        <div className="grid gap-1.5">
                            <Label>{t('stages')}</Label>
                            <div className="flex flex-col gap-2">
                                {draft.stages.map((s, i) => (
                                    <div key={s.id ?? `new-${i}`} className="flex flex-wrap items-center gap-2">
                                        <span className={cn('size-2 shrink-0 rounded-full', STAGE_DOT[stageKindOf(s)])} />
                                        <Input
                                            id={`pipeline-stage-${p.id}-${i}`}
                                            type="text"
                                            value={s.name}
                                            onChange={(e) => updateStageName(p.id, i, e.target.value)}
                                            className="min-w-24 flex-1"
                                            placeholder={t('stageNamePlaceholder')}
                                            aria-label={tOrder('stageName', { position: i + 1 })}
                                            disabled={isSaving}
                                        />
                                        <Select
                                            value={stageKindOf(s)}
                                            onValueChange={(value) => {
                                                if (value === 'normal' || value === 'won' || value === 'lost') updateStageKind(p.id, i, value);
                                            }}
                                            disabled={isSaving}
                                            aria-label={t('stageKindAriaLabel')}
                                        >
                                            <SelectTrigger className="w-32 shrink-0">
                                                <SelectValue placeholder={t('stageKindPlaceholder')} />
                                            </SelectTrigger>
                                            <SelectContent>
                                                <SelectItem value="normal">{t('stageInProgress')}</SelectItem>
                                                <SelectItem value="won">{t('stageWon')}</SelectItem>
                                                <SelectItem value="lost">{t('stageLost')}</SelectItem>
                                            </SelectContent>
                                        </Select>
                                        <StageOrderControls
                                            id={`pipeline-stage-${p.id}-${i}`}
                                            index={i}
                                            count={draft.stages.length}
                                            disabled={isSaving}
                                            onMove={(to, direction) => {
                                                pendingFocus.current = `pipeline-stage-${p.id}-${to}-${direction}`;
                                                updateDraft(p.id, { stages: moveStage(draft.stages, i, to) });
                                                setAnnouncement(tOrder('moved', { name: s.name, position: to + 1, count: draft.stages.length }));
                                            }}
                                        />
                                        <IconButton
                                            type="button"
                                            variant="ghost"
                                            size="icon-inline"
                                            label={t('removeStageAriaLabel')}
                                            disabled={isSaving}
                                            onClick={() => removeStage(p.id, i)}
                                            className="shrink-0 text-muted-foreground hover:text-destructive"
                                        >
                                            <XMarkIcon className="size-4" />
                                        </IconButton>
                                    </div>
                                ))}
                                <Button
                                    type="button"
                                    variant="outline"
                                    disabled={isSaving}
                                    onClick={() => {
                                        const terminalIndex = draft.stages.findIndex((stage) => stage.success || stage.failure);
                                        pendingFocus.current = `pipeline-stage-${p.id}-${terminalIndex < 0 ? draft.stages.length : terminalIndex}`;
                                        addStage(p.id);
                                    }}
                                >
                                    <PlusIcon className="size-4" />
                                    {t('addStage')}
                                </Button>
                            </div>
                            {fieldErrors?.[p.id]?.stages ? (
                                <p className="text-sm text-destructive">{fieldErrors[p.id].stages}</p>
                            ) : null}
                        </div>
                    </QuickEditRecordCard>
                );
            })}
        </QuickEditSheetShell>
    );
}
