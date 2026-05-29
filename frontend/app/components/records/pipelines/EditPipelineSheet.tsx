'use client';

import { useState } from 'react';
import { useRouter } from 'next/navigation';
import { toast } from 'sonner';
import { toastError, toastSuccess } from '@/app/lib/toast';
import { useTranslations } from 'next-intl';

import { createStage, deleteStage, getStagesByPipelineId, updatePipeline, updateStage } from '@/app/lib/api';
import { Pipeline, Stage, UpdatePipelinePayload } from '@/app/lib/types';
import QuickEditPipelineSheet, { type PipelineDraft } from '@/app/components/records/pipelines/QuickEditPipelineSheet';

function toDraft(p: Pipeline, stages: Stage[] = []): PipelineDraft {
    return {
        name: p.name ?? '',
        stages: [...stages]
            .sort((a, b) => a.position - b.position)
            .map((s) => ({ id: s.id, name: s.name })),
    };
}

function diffDraft(a: PipelineDraft, b: PipelineDraft): boolean {
    if (a.name !== b.name) return true;
    if (a.stages.length !== b.stages.length) return true;
    for (let i = 0; i < a.stages.length; i++) {
        if (a.stages[i].id !== b.stages[i].id) return true;
        if (a.stages[i].name !== b.stages[i].name) return true;
    }
    return false;
}

export default function EditPipelineSheet({
    pipeline,
    stages,
    open,
    onOpenChange,
}: {
    pipeline: Pipeline;
    stages: Stage[];
    open: boolean;
    onOpenChange: (open: boolean) => void;
}) {
    const router = useRouter();
    const t = useTranslations('PipelinesEditSheet');
    const [draft, setDraft] = useState<PipelineDraft>(() => toDraft(pipeline, stages));
    const [isSaving, setIsSaving] = useState(false);

    const handleOpenChange = (next: boolean) => {
        onOpenChange(next);
        if (!next) setDraft(toDraft(pipeline, stages));
    };

    const updateStageName = (_pipelineId: number, index: number, name: string) => {
        setDraft((prev) => ({
            ...prev,
            stages: prev.stages.map((s, i) => (i === index ? { ...s, name } : s)),
        }));
    };

    const addStage = () => {
        setDraft((prev) => ({ ...prev, stages: [...prev.stages, { id: null, name: '' }] }));
    };

    const removeStage = (_pipelineId: number, index: number) => {
        setDraft((prev) => ({ ...prev, stages: prev.stages.filter((_, i) => i !== index) }));
    };

    const saveEdits = async () => {
        const original = toDraft(pipeline, stages);
        if (!diffDraft(original, draft)) {
            toast.info(t('noChangesToSave'));
            handleOpenChange(false);
            return;
        }
        if (!draft.name.trim()) {
            toast.error(t('nameRequired'));
            return;
        }
        if (draft.stages.some((s) => !s.name.trim())) {
            toast.error(t('stageNamesEmpty'));
            return;
        }

        setIsSaving(true);
        try {
            if (original.name !== draft.name) {
                const payload: UpdatePipelinePayload = { name: draft.name.trim() };
                await updatePipeline(pipeline.id, payload);
            }

            const draftIds = new Set(draft.stages.filter((s) => s.id !== null).map((s) => s.id as number));
            const toDelete = stages.filter((s) => !draftIds.has(s.id));
            const originalById = new Map(stages.map((s) => [s.id, s]));

            for (const stage of toDelete) {
                await deleteStage(stage.id);
            }

            const maxOriginalPosition = stages.reduce((max, s) => Math.max(max, s.position), -1);
            let nextNewPosition = maxOriginalPosition + 1;

            for (const s of draft.stages) {
                const name = s.name.trim();
                if (s.id !== null) {
                    const orig = originalById.get(s.id);
                    if (orig && orig.name !== name) {
                        await updateStage(s.id, { name, position: orig.position });
                    }
                } else {
                    await createStage(pipeline.id, { name, position: nextNewPosition });
                    nextNewPosition++;
                }
            }

            toastSuccess(t('pipelineUpdated'));
            handleOpenChange(false);

            const fresh = await getStagesByPipelineId(pipeline.id);
            setDraft(toDraft(pipeline, fresh));
            router.refresh();
        } catch (err) {
            toastError(err instanceof Error ? err.message : t('failedToSave'));
        } finally {
            setIsSaving(false);
        }
    };

    return (
        <QuickEditPipelineSheet
            open={open}
            onOpenChange={handleOpenChange}
            selectedIds={new Set([pipeline.id])}
            selectedPipelines={[pipeline]}
            drafts={{ [pipeline.id]: draft }}
            updateDraft={(_id, patch) => setDraft((prev) => ({ ...prev, ...patch }))}
            updateStageName={updateStageName}
            addStage={addStage}
            removeStage={removeStage}
            isSaving={isSaving}
            saveEdits={saveEdits}
        />
    );
}