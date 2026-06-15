'use client';

import { Sheet, SheetContent, SheetHeader, SheetTitle, SheetDescription, SheetFooter, SheetClose } from '@/components/ui/sheet';
import { Button } from '@/components/ui/button';
import { Loader2Icon } from 'lucide-react';
import { PlusIcon, XMarkIcon } from '@heroicons/react/24/outline';
import { Label } from '@/components/ui/label';
import { type Pipeline } from '@/app/lib/types';
import { type SelectionId } from '@/app/components/records/types';
import { useTranslations } from 'next-intl';
import { Select, SelectTrigger, SelectContent, SelectItem, SelectValue } from '@/components/ui/select';

export type StageKind = 'normal' | 'won' | 'lost';

export function stageKindOf(s: { success: boolean; failure: boolean }): StageKind {
    if (s.success) return 'won';
    if (s.failure) return 'lost';
    return 'normal';
}

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
};

export default function QuickEditPipelineSheet({
    open,
    onOpenChange,
    selectedIds,
    selectedPipelines,
    drafts,
    updateDraft,
    updateStageName,
    updateStageKind,
    addStage,
    removeStage,
    isSaving,
    saveEdits,
}: Props) {
    const t = useTranslations('PipelinesQuickEditSheet');
    return (
        <Sheet open={open} onOpenChange={onOpenChange}>
            <SheetContent side="right" className="flex w-full flex-col sm:max-w-lg">
                <SheetHeader className="border-b">
                    <SheetTitle>
                        {selectedIds.size === 1 ? t('titleSingle') : t('titleMultiple', { count: selectedIds.size })}
                    </SheetTitle>
                    <SheetDescription>
                        {t('description')}
                    </SheetDescription>
                </SheetHeader>

                <div className="flex-1 overflow-y-auto px-4 py-2">
                    <div className="flex flex-col gap-6">
                        {selectedPipelines.map((p, idx) => {
                            const draft = drafts[p.id];
                            if (!draft) return null;
                            return (
                                <div key={p.id} className={idx > 0 ? 'border-t pt-6' : ''}>
                                    <div className="mb-3 flex items-center gap-3">
                                        <div className="text-lg font-medium text-muted-foreground">{p.name}</div>
                                    </div>

                                    <div className="grid gap-3">
                                        <div className="grid gap-1.5">
                                            <Label htmlFor={`name-${p.id}`}>{t('name')}</Label>
                                            <input
                                                id={`name-${p.id}`}
                                                type="text"
                                                value={draft.name}
                                                onChange={(e) => updateDraft(p.id, { name: e.target.value })}
                                                className="connex-input"
                                                required
                                            />
                                        </div>

                                        <div className="grid gap-1.5">
                                            <Label>{t('stages')}</Label>
                                            <div className="flex flex-col gap-2">
                                                {draft.stages.map((s, i) => (
                                                    <div
                                                        key={s.id ?? `new-${i}`}
                                                        className="flex items-center gap-2"
                                                    >
                                                        <input
                                                            type="text"
                                                            value={s.name}
                                                            onChange={(e) => updateStageName(p.id, i, e.target.value)}
                                                            className="connex-input flex-1"
                                                            placeholder={t('stageNamePlaceholder')}
                                                        />
                                                        {/* <select
                                                            value={stageKindOf(s)}
                                                            onChange={(e) => updateStageKind(p.id, i, e.target.value as StageKind)}
                                                            className="connex-input w-28 shrink-0"
                                                            aria-label={t('stageKindAriaLabel')}
                                                        >
                                                            <option value="normal">{t('stageInProgress')}</option>
                                                            <option value="won">{t('stageWon')}</option>
                                                            <option value="lost">{t('stageLost')}</option>
                                                        </select> */}
                                                        <Select
                                                            value={stageKindOf(s)}
                                                            onValueChange={(value) => updateStageKind(p.id, i, value as StageKind)}
                                                            // className="connex-input w-28 shrink-0"
                                                            aria-label={t('stageKindAriaLabel')}
                                                        >
                                                            <SelectTrigger>
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
                                                            onClick={() => removeStage(p.id, i)}
                                                            className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-muted text-muted-foreground transition hover:bg-muted/60 hover:text-destructive"
                                                        >
                                                            <XMarkIcon className="size-4" />
                                                        </button>
                                                    </div>
                                                ))}
                                                <button
                                                    type="button"
                                                    onClick={() => addStage(p.id)}
                                                    className="flex items-center gap-2 self-start rounded-full bg-muted px-3 py-1.5 text-sm text-foreground ring-1 ring-border transition hover:bg-muted/60"
                                                >
                                                    <PlusIcon className="size-4" />
                                                    {t('addStage')}
                                                </button>
                                            </div>
                                        </div>
                                    </div>
                                </div>
                            );
                        })}
                    </div>
                </div>

                <SheetFooter className="border-t">
                    <SheetClose asChild>
                        <Button variant="outline" disabled={isSaving}>{t('cancel')}</Button>
                    </SheetClose>
                    <Button onClick={saveEdits} disabled={isSaving} className="bg-brand text-white hover:bg-brand-dark">
                        {isSaving ? <Loader2Icon className="size-4 animate-spin" /> : t('save')}
                    </Button>
                </SheetFooter>
            </SheetContent>
        </Sheet>
    );
}