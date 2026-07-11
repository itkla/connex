'use client';

import { useEffect, useRef, useState } from 'react';
import { useRouter } from 'next/navigation';
import { useTranslations } from 'next-intl';

import NewDealDialog from '@/app/components/records/deals/NewDealDialog';
import { createDeal, getCompanies, getPipelines, getStagesByPipelineId } from '@/app/lib/api';
import { toastError, toastSuccess } from '@/app/lib/toast';
import type { Company, CreateDealPayload, Pipeline, Stage } from '@/app/lib/types';
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
 * flow, lazily loading companies, pipelines and per-pipeline stages the first time it opens so the app
 * shell never fetches them just to render the launcher. Context prefills (company, pipeline) are seeded
 * on each open and remain fully editable.
 */
export default function DealCreateContainer({
    open,
    onOpenChange,
    defaults,
}: {
    open: boolean;
    onOpenChange: (open: boolean) => void;
    defaults?: CreateDefaults;
}) {
    const router = useRouter();
    const t = useTranslations('Actions');

    const [loaded, setLoaded] = useState(false);
    const [companies, setCompanies] = useState<Company[]>([]);
    const [pipelines, setPipelines] = useState<Pipeline[]>([]);
    const [stagesByPipeline, setStagesByPipeline] = useState<Record<number, Stage[]>>({});
    const stagesByPipelineRef = useRef<Record<number, Stage[]>>({});
    useEffect(() => {
        stagesByPipelineRef.current = stagesByPipeline;
    }, [stagesByPipeline]);

    const [payload, setPayload] = useState<CreateDealPayload>(EMPTY_DRAFT);
    const [creating, setCreating] = useState(false);
    const [succeeded, setSucceeded] = useState(false);

    useEffect(() => {
        if (!open || loaded) return;
        let cancelled = false;
        void (async () => {
            const [nextCompanies, nextPipelines] = await Promise.all([
                getCompanies().catch(() => [] as Company[]),
                getPipelines().catch(() => [] as Pipeline[]),
            ]);
            if (cancelled) return;
            setCompanies(nextCompanies);
            setPipelines(nextPipelines);
            const entries = await Promise.all(
                nextPipelines.map(
                    async (p) => [p.id, await getStagesByPipelineId(p.id).catch(() => [] as Stage[])] as const,
                ),
            );
            if (cancelled) return;
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
    }, [open, loaded, defaults?.pipelineId]);

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
        onOpenChange(next);
    };

    const createNewDeal = async () => {
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
                expectedCloseDate: payload.expectedCloseDate || undefined,
            });
            toastSuccess(t('feedback.dealCreated'));
            setCreating(false);
            setSucceeded(true);
            setTimeout(() => {
                onOpenChange(false);
                router.refresh();
            }, 900);
        } catch (err) {
            setCreating(false);
            toastError(err instanceof Error ? err.message : t('feedback.createFailed'));
        }
    };

    return (
        <NewDealDialog
            open={open}
            onOpenChange={handleOpenChange}
            payload={payload}
            setPayload={setPayload}
            companies={companies}
            pipelines={pipelines}
            stagesByPipeline={stagesByPipeline}
            isCreating={creating}
            isSuccess={succeeded}
            createNewDeal={createNewDeal}
        />
    );
}
