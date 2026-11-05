'use client';

import { useEffect, useRef, useState } from 'react';
import { useRouter } from 'next/navigation';
import { toastError, toastSuccess } from '@/app/lib/toast';
import { useTranslations } from 'next-intl';

import DealEditConflictDialog from './DealEditConflictDialog';
import { useRecordEditConflict } from '@/app/hooks/useRecordEditConflict';
import { useFieldErrors } from '@/app/hooks/useFieldErrors';
import { useApiErrorToast } from '@/app/hooks/useApiErrorToast';
import QuickEditDealSheet, { type DealDraft } from '@/app/components/records/deals/QuickEditDealSheet';
import { CustomFieldsEditSection, type CustomFieldsEditHandle } from '@/app/components/records/CustomFieldsEditSection';
import { reconcileSavedDealDraft, submitDealDraftUpdate, toDealDraft as toDraft } from './dealEditConflict';
import { getDealById, getPipelines, getStagesByPipelineId } from '@/app/lib/api';
import { renderedWorkspaceId, workspacePinnedRequest } from '@/app/lib/renderedWorkspace';
import { type Deal, type Pipeline, type Stage } from '@/app/lib/types';


export default function EditDealSheet({
    deal,
    timezone,
    open,
    onOpenChange,
    pipelines,
    stagesByPipeline,
    onDealUpdated,
}: {
    deal: Deal;
    timezone: string;
    open: boolean;
    onOpenChange: (open: boolean) => void;
    pipelines: Pipeline[];
    stagesByPipeline: Record<number, Stage[]>;
    onDealUpdated?: (saved: Deal) => void;
}) {
    const router = useRouter();
    const t = useTranslations('DealsEditSheet');
    const reportApiError = useApiErrorToast('DealsEditSheet');
    const [base, setBase] = useState(deal);
    const [reloadRevision, setReloadRevision] = useState(0);
    const { conflict, captureConflict, clearConflict } = useRecordEditConflict(getDealById);
    const [draft, setDraft] = useState<DealDraft>(() => toDraft(deal));
    const draftRevisionRef = useRef(0);
    const [isSaving, setIsSaving] = useState(false);
    const [pipelineOptions, setPipelineOptions] = useState(pipelines);
    const [stageOptionsByPipeline, setStageOptionsByPipeline] = useState(stagesByPipeline);
    const [stageLoadRevision, setStageLoadRevision] = useState(0);
    const cfRef = useRef<CustomFieldsEditHandle>(null);
    const [customFieldsDirty, setCustomFieldsDirty] = useState(false);
    const pipelinesLoaded = useRef(false);
    const { fieldErrors, setFieldErrors, reset: resetFieldErrors, clearError } = useFieldErrors();

    const [wasOpen, setWasOpen] = useState(open);
    if (open !== wasOpen) {
        setWasOpen(open);
        resetFieldErrors();
        clearConflict();
        if (open) {
            setBase(deal);
            setDraft(toDraft(deal));
        }
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
        const origin = workspacePinnedRequest(renderedWorkspaceId());
        resetFieldErrors();
        if (!draft.name.trim()) {
            setFieldErrors({ name: t('nameRequired') });
            requestAnimationFrame(() => document.getElementById(`deal-name-${base.id}`)?.focus());
            return;
        }
        if (!draft.pipeline || !draft.stage) {
            setFieldErrors(draft.pipeline
                ? { stage: t('pipelineStageRequired') }
                : { pipeline: t('pipelineStageRequired') });
            requestAnimationFrame(() =>
                document.getElementById(draft.pipeline ? `deal-stage-${base.id}` : `deal-pipeline-${base.id}`)?.focus());
            return;
        }

        const submittedRevision = draftRevisionRef.current;
        setIsSaving(true);
        try {
            const saved = await submitDealDraftUpdate(base.id, draft, origin);
            setBase(saved);
            onDealUpdated?.(saved);
            setDraft((current) => reconcileSavedDealDraft(current, draft, saved));
            await cfRef.current?.save(origin);
            if (draftRevisionRef.current !== submittedRevision) return;
            toastSuccess(t('dealUpdated'));
            onOpenChange(false);
            router.refresh();
        } catch (err) {
            if (!await captureConflict(err, base, origin)) reportApiError(err, 'failedToSave');
        } finally {
            setIsSaving(false);
        }
    };

    return (
        <>
            <QuickEditDealSheet
                key={reloadRevision}
                timezone={timezone}
                open={open}
                onOpenChange={onOpenChange}
                selectedIds={new Set([base.id])}
                selectedDeals={[base]}
                drafts={{ [base.id]: draft }}
                updateDraft={(_id, patch) => {
                    draftRevisionRef.current += 1;
                    for (const key of Object.keys(patch)) clearError(key);
                    setDraft((prev) => ({ ...prev, ...patch }));
                }}
                pipelines={pipelineOptions}
                stagesByPipeline={stageOptionsByPipeline}
                isSaving={isSaving}
                saveEdits={saveEdits}
                fieldErrors={{ [base.id]: fieldErrors }}
                customFieldsDirty={customFieldsDirty}
                customFieldsSlot={(
                    <CustomFieldsEditSection
                        ref={cfRef}
                        entityType="deal"
                        entityId={base.id}
                        onDirtyChange={setCustomFieldsDirty}
                    />
                )}
            />
            {open && conflict && <DealEditConflictDialog
                conflict={conflict}
                onKeepEditing={clearConflict}
                onReload={(current) => {
                    setBase(current);
                    setDraft(toDraft(current));
                    resetFieldErrors();
                    setCustomFieldsDirty(false);
                    setReloadRevision((revision) => revision + 1);
                    clearConflict();
                }}
            />}
        </>
    );
}
