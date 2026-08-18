'use client';

import { useEffect, useRef, useState } from 'react';
import { useRouter } from 'next/navigation';
import { toastError, toastSuccess } from '@/app/lib/toast';
import { useTranslations } from 'next-intl';

import { useFieldErrors } from '@/app/hooks/useFieldErrors';
import QuickEditDealSheet, { type DealDraft } from '@/app/components/records/deals/QuickEditDealSheet';
import { CustomFieldsEditSection, type CustomFieldsEditHandle } from '@/app/components/records/CustomFieldsEditSection';
import { actualValueForOutcome } from '@/app/components/records/deals/dealOutcome';
import { getPipelines, getStagesByPipelineId, updateDeal } from '@/app/lib/api';
import { type Deal, type Pipeline, type Stage, type UpdateDealPayload } from '@/app/lib/types';

function toDraft(d: Deal): DealDraft {
    return {
        name: d.name ?? '',
        value: d.value ?? 0,
        actualValue: d.actualValue ?? 0,
        currency: d.currency ?? 'USD',
        pipeline: d.pipeline ?? 0,
        stage: d.stage ?? 0,
        company: d.company ?? null,
        expectedCloseDate: d.expectedCloseDate ?? '',
        closedAt: d.closedAt ?? null,
        closedReason: d.closedReason ?? null,
        won: d.won ?? null,
    };
}

/** Persists one edit-sheet draft with outcome-canonicalized realized value. */
export async function submitDealDraftUpdate(dealId: number, draft: DealDraft): Promise<void> {
    const payload: UpdateDealPayload = {
        name: draft.name.trim(),
        value: draft.value,
        actualValue: actualValueForOutcome(draft.won, draft.actualValue),
        currency: draft.currency.trim() || 'USD',
        pipeline: draft.pipeline,
        stage: draft.stage,
        company: draft.company,
        expectedCloseDate: draft.expectedCloseDate || null,
        closedAt: draft.closedAt || null,
        closedReason: draft.closedReason || null,
        won: draft.won,
    };
    await updateDeal(dealId, payload);
}

export default function EditDealSheet({
    deal,
    open,
    onOpenChange,
    pipelines,
    stagesByPipeline,
}: {
    deal: Deal;
    open: boolean;
    onOpenChange: (open: boolean) => void;
    pipelines: Pipeline[];
    stagesByPipeline: Record<number, Stage[]>;
}) {
    const router = useRouter();
    const t = useTranslations('DealsEditSheet');
    const [draft, setDraft] = useState<DealDraft>(() => toDraft(deal));
    const [isSaving, setIsSaving] = useState(false);
    const [pipelineOptions, setPipelineOptions] = useState(pipelines);
    const [stageOptionsByPipeline, setStageOptionsByPipeline] = useState(stagesByPipeline);
    const [stageLoadRevision, setStageLoadRevision] = useState(0);
    const cfRef = useRef<CustomFieldsEditHandle>(null);
    const pipelinesLoaded = useRef(false);
    const { fieldErrors, setFieldErrors, reset: resetFieldErrors, clearError } = useFieldErrors();

    const [wasOpen, setWasOpen] = useState(open);
    if (open !== wasOpen) {
        setWasOpen(open);
        if (open) setDraft(toDraft(deal));
    }

    useEffect(() => {
        if (!open || pipelinesLoaded.current) return;
        let cancelled = false;
        getPipelines()
            .then((loaded) => {
                if (!cancelled) {
                    setPipelineOptions((current) => {
                        const byId = new Map(current.map((pipeline) => [pipeline.id, pipeline]));
                        for (const pipeline of loaded) byId.set(pipeline.id, pipeline);
                        return [...byId.values()];
                    });
                    pipelinesLoaded.current = true;
                }
            })
            .catch(() => undefined);
        return () => {
            cancelled = true;
        };
    }, [open]);

    useEffect(() => {
        if (!open || !draft.pipeline || stageOptionsByPipeline[draft.pipeline]) return;
        let cancelled = false;
        getStagesByPipelineId(draft.pipeline)
            .then((loaded) => {
                if (!cancelled) {
                    setStageOptionsByPipeline((current) => ({
                        ...current,
                        [draft.pipeline]: loaded,
                    }));
                }
            })
            .catch(() => {
                if (cancelled) return;
                toastError(t('stagesLoadFailed'), {
                    action: {
                        label: t('retry'),
                        onClick: () => setStageLoadRevision((current) => current + 1),
                    },
                });
            });
        return () => {
            cancelled = true;
        };
    }, [open, draft.pipeline, stageOptionsByPipeline, stageLoadRevision, t]);

    const saveEdits = async () => {
        resetFieldErrors();
        if (!draft.name.trim()) {
            setFieldErrors({ name: t('nameRequired') });
            requestAnimationFrame(() => document.getElementById(`deal-name-${deal.id}`)?.focus());
            return;
        }
        if (!draft.pipeline || !draft.stage) {
            setFieldErrors(draft.pipeline
                ? { stage: t('pipelineStageRequired') }
                : { pipeline: t('pipelineStageRequired') });
            requestAnimationFrame(() =>
                document.getElementById(draft.pipeline ? `deal-stage-${deal.id}` : `deal-pipeline-${deal.id}`)?.focus());
            return;
        }

        setIsSaving(true);
        try {
            await submitDealDraftUpdate(deal.id, draft);
            await cfRef.current?.save();
            toastSuccess(t('dealUpdated'));
            onOpenChange(false);
            router.refresh();
        } catch (err) {
            toastError(err instanceof Error ? err.message : t('failedToSave'));
        } finally {
            setIsSaving(false);
        }
    };

    return (
        <QuickEditDealSheet
            open={open}
            onOpenChange={onOpenChange}
            selectedIds={new Set([deal.id])}
            selectedDeals={[deal]}
            drafts={{ [deal.id]: draft }}
            updateDraft={(_id, patch) => {
                for (const key of Object.keys(patch)) clearError(key);
                setDraft((prev) => ({ ...prev, ...patch }));
            }}
            pipelines={pipelineOptions}
            stagesByPipeline={stageOptionsByPipeline}
            isSaving={isSaving}
            saveEdits={saveEdits}
            fieldErrors={{ [deal.id]: fieldErrors }}
            customFieldsSlot={<CustomFieldsEditSection ref={cfRef} entityType="deal" entityId={deal.id} />}
        />
    );
}
