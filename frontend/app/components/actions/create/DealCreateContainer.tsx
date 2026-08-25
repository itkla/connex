'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { useRouter } from 'next/navigation';
import { useTranslations } from 'next-intl';

import NewDealDialog, { NewDealForm, isDealPayloadDirty } from '@/app/components/records/deals/NewDealDialog';
import { createDeal, getPipelines, getStagesByPipelineId, isFieldError } from '@/app/lib/api';
import { useApiErrorToast } from '@/app/hooks/useApiErrorToast';
import { toastSuccess } from '@/app/lib/toast';
import type { CreateDealPayload, Pipeline, Stage } from '@/app/lib/types';
import type { CreateDefaults } from '@/app/lib/actions/types';

const EMPTY_DRAFT: CreateDealPayload = {
    name: '',
    value: 0,
    actualValue: 0,
    currency: 'USD',
    pipeline: 0,
    stage: 0,
    company: null,
    expectedCloseDate: undefined,
};

/**
 * Shell-owned deal quick-create. Reuses {@link NewDealDialog} and mirrors the DealsBrowser create
 * flow, lazily loading pipelines and per-pipeline stages the first time it opens so the app
 * shell never fetches them just to render the launcher. Context prefills (company, pipeline) are seeded
 * on each open and remain fully editable.
 */
export default function DealCreateContainer({
    open,
    onOpenChange,
    defaults,
    embedded = false,
    onCancel,
    requestInit,
}: {
    open: boolean;
    onOpenChange: (open: boolean) => void;
    defaults?: CreateDefaults;
    /** Renders the shell-less {@link NewDealForm} directly, for embedding in the morphing launcher. */
    embedded?: boolean;
    /** Cancel handler for embedded mode — steps back to the launcher selector. */
    onCancel?: () => void;
    requestInit?: RequestInit;
}) {
    const router = useRouter();
    const t = useTranslations('Actions');
    const showApiError = useApiErrorToast('Actions');

    const [loaded, setLoaded] = useState(false);
    const [pipelines, setPipelines] = useState<Pipeline[]>([]);
    const [stagesByPipeline, setStagesByPipeline] = useState<Record<number, Stage[]>>({});
    const stagesByPipelineRef = useRef<Record<number, Stage[]>>({});
    useEffect(() => {
        stagesByPipelineRef.current = stagesByPipeline;
    }, [stagesByPipeline]);

    const [payload, setPayload] = useState<CreateDealPayload>(EMPTY_DRAFT);
    const [creating, setCreating] = useState(false);
    const [succeeded, setSucceeded] = useState(false);
    const closeTimerRef = useRef<number | null>(null);

    const clearPendingClose = useCallback(() => {
        if (closeTimerRef.current === null) return;
        window.clearTimeout(closeTimerRef.current);
        closeTimerRef.current = null;
    }, []);

    useEffect(() => () => clearPendingClose(), [clearPendingClose]);

    useEffect(() => {
        if (!open || loaded) return;
        let cancelled = false;
        void (async () => {
            const nextPipelines = await getPipelines(requestInit).catch(() => [] as Pipeline[]);
            if (cancelled || requestInit?.signal?.aborted) return;
            setPipelines(nextPipelines);
            const entries = await Promise.all(
                nextPipelines.map(
                    async (p) => [p.id, await getStagesByPipelineId(p.id, requestInit).catch(() => [] as Stage[])] as const,
                ),
            );
            if (cancelled || requestInit?.signal?.aborted) return;
            const nextStages = Object.fromEntries(entries);
            setStagesByPipeline(nextStages);
            setLoaded(true);
            const pipelineId = defaults?.pipelineId;
            if (pipelineId && nextStages[pipelineId]?.length) {
                setPayload((prev) =>
                    prev.pipeline === pipelineId && !prev.stage ? { ...prev, stage: nextStages[pipelineId][0].id } : prev,
                );
            }
        })();
        return () => {
            cancelled = true;
        };
    }, [open, loaded, defaults?.pipelineId, requestInit]);

    useEffect(() => {
        if (!open) return;
        const raf = window.requestAnimationFrame(() => {
            const pipelineId = defaults?.pipelineId ?? 0;
            const stages = pipelineId ? stagesByPipelineRef.current[pipelineId] : undefined;
            setPayload({
                ...EMPTY_DRAFT,
                company: defaults?.companyId ?? null,
                pipeline: pipelineId,
                stage: stages?.[0]?.id ?? 0,
            });
            setSucceeded(false);
        });
        return () => window.cancelAnimationFrame(raf);
    }, [open, defaults?.companyId, defaults?.pipelineId]);

    const handleOpenChange = (next: boolean) => {
        if (!next && creating) return;
        if (!next) clearPendingClose();
        onOpenChange(next);
    };

    const seedPipelineId = defaults?.pipelineId ?? 0;
    const seededBaseline: CreateDealPayload = {
        ...EMPTY_DRAFT,
        company: defaults?.companyId ?? null,
        pipeline: seedPipelineId,
        stage: (seedPipelineId ? stagesByPipeline[seedPipelineId] : undefined)?.[0]?.id ?? 0,
    };
    const isDirty = !creating && !succeeded && isDealPayloadDirty(payload, seededBaseline);

    const createNewDeal = async (duplicateReviewToken: string) => {
        clearPendingClose();
        setSucceeded(false);
        setCreating(true);
        try {
            await createDeal(
                {
                    ...payload,
                    name: payload.name.trim(),
                    value: Number.isFinite(payload.value) ? payload.value : 0,
                    actualValue: Number.isFinite(payload.actualValue) ? payload.actualValue : 0,
                    currency: payload.currency.trim() || 'USD',
                    pipeline: payload.pipeline || null,
                    stage: payload.stage || null,
                    duplicateReviewToken,
                    expectedCloseDate: payload.expectedCloseDate || undefined,
                },
                requestInit,
            );
            if (requestInit?.signal?.aborted) return;
            toastSuccess(t('feedback.dealCreated'));
            setCreating(false);
            setSucceeded(true);
            closeTimerRef.current = window.setTimeout(() => {
                closeTimerRef.current = null;
                if (requestInit?.signal?.aborted) return;
                onOpenChange(false);
                router.refresh();
            }, 900);
        } catch (err) {
            if (requestInit?.signal?.aborted) return;
            setCreating(false);
            if (isFieldError(err)) throw err;
            showApiError(err, 'feedback.dealCreateFailed');
        }
    };

    if (embedded) {
        return (
            <NewDealForm
                active
                onCancel={onCancel ?? (() => onOpenChange(false))}
                payload={payload}
                setPayload={setPayload}
                pipelines={pipelines}
                stagesByPipeline={stagesByPipeline}
                isCreating={creating}
                isSuccess={succeeded}
                createNewDeal={createNewDeal}
                requestInit={requestInit}
            />
        );
    }

    return (
        <NewDealDialog
            open={open}
            onOpenChange={handleOpenChange}
            payload={payload}
            setPayload={setPayload}
            pipelines={pipelines}
            stagesByPipeline={stagesByPipeline}
            isCreating={creating}
            isSuccess={succeeded}
            isDirty={isDirty}
            createNewDeal={createNewDeal}
            requestInit={requestInit}
        />
    );
}
