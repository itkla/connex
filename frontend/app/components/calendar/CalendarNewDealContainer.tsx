'use client';

import { useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import { useTranslations } from 'next-intl';

import NewDealDialog, { isDealPayloadDirty } from '@/app/components/records/deals/NewDealDialog';
import { createDeal, getPipelines, getStagesByPipelineId } from '@/app/lib/api';
import { useApiErrorToast } from '@/app/hooks/useApiErrorToast';
import { toastSuccess } from '@/app/lib/toast';
import type { CreateDealPayload, Pipeline, Stage } from '@/app/lib/types';

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
 * Deal quick-create for the calendar. Reuses {@link NewDealDialog} and mirrors the
 * DealsBrowser create flow, but is self-contained: it lazily loads pipelines and
 * per-pipeline stages the first time it opens (so the calendar page's
 * server loader stays lean) and seeds the expected close date to the selected day.
 */
export default function CalendarNewDealContainer({
    open,
    onOpenChange,
    defaultExpectedCloseDate,
}: {
    open: boolean;
    onOpenChange: (open: boolean) => void;
    defaultExpectedCloseDate: string;
}) {
    const router = useRouter();
    const t = useTranslations('Calendar');
    const showApiError = useApiErrorToast('Calendar');

    const [loaded, setLoaded] = useState(false);
    const [pipelines, setPipelines] = useState<Pipeline[]>([]);
    const [stagesByPipeline, setStagesByPipeline] = useState<Record<number, Stage[]>>({});

    const [payload, setPayload] = useState<CreateDealPayload>(EMPTY_DRAFT);
    const [creating, setCreating] = useState(false);
    const [succeeded, setSucceeded] = useState(false);

    useEffect(() => {
        if (!open || loaded) return;
        let cancelled = false;
        void (async () => {
            const nextPipelines = await getPipelines().catch(() => [] as Pipeline[]);
            if (cancelled) return;
            setPipelines(nextPipelines);
            const entries = await Promise.all(
                nextPipelines.map(
                    async (p) => [p.id, await getStagesByPipelineId(p.id).catch(() => [] as Stage[])] as const,
                ),
            );
            if (cancelled) return;
            setStagesByPipeline(Object.fromEntries(entries));
            setLoaded(true);
        })();
        return () => {
            cancelled = true;
        };
    }, [open, loaded]);

    useEffect(() => {
        if (!open) return;
        const raf = window.requestAnimationFrame(() => {
            setPayload({ ...EMPTY_DRAFT, expectedCloseDate: defaultExpectedCloseDate || undefined });
            setSucceeded(false);
        });
        return () => window.cancelAnimationFrame(raf);
    }, [open, defaultExpectedCloseDate]);

    const handleOpenChange = (next: boolean) => {
        if (!next && creating) return;
        onOpenChange(next);
    };

    const seededBaseline: CreateDealPayload = { ...EMPTY_DRAFT, expectedCloseDate: defaultExpectedCloseDate || undefined };
    const isDirty = !creating && !succeeded && isDealPayloadDirty(payload, seededBaseline);

    const createNewDeal = async (duplicateReviewToken: string) => {
        setSucceeded(false);
        setCreating(true);
        try {
            await createDeal({
                ...payload,
                name: payload.name.trim(),
                value: Number.isFinite(payload.value) ? payload.value : 0,
                actualValue: Number.isFinite(payload.actualValue) ? payload.actualValue : 0,
                currency: payload.currency.trim() || 'USD',
                pipeline: payload.pipeline || null,
                stage: payload.stage || null,
                duplicateReviewToken,
                expectedCloseDate: payload.expectedCloseDate || undefined,
            });
            toastSuccess(t('dealCreated'));
            setCreating(false);
            setSucceeded(true);
            setTimeout(() => {
                onOpenChange(false);
                router.refresh();
            }, 900);
        } catch (err) {
            setCreating(false);
            showApiError(err, 'createFailed');
        }
    };

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
        />
    );
}
