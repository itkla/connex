'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { useRouter } from 'next/navigation';
import { useTranslations } from 'next-intl';

import NewDealDialog, { NewDealForm, isDealPayloadDirty } from '@/app/components/records/deals/NewDealDialog';
import { createDeal, getCompaniesByIds, getPipelines, getStagesByPipelineId, isFieldError } from '@/app/lib/api';
import { useApiErrorToast } from '@/app/hooks/useApiErrorToast';
import { useFormDraft } from '@/app/hooks/useFormDraft';
import { useWorkspace } from '@/app/hooks/useWorkspace';
import { DRAFT_VERSIONS, type DealDraft } from '@/app/lib/formDrafts';
import { toastError, toastSuccess, toastWarn } from '@/app/lib/toast';
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

function toStoredDraft(payload: CreateDealPayload): DealDraft {
    return {
        name: payload.name,
        value: Number.isFinite(payload.value) ? payload.value : 0,
        currency: payload.currency,
        pipeline: payload.pipeline || null,
        stage: payload.stage || null,
        company: payload.company || null,
        expectedCloseDate: payload.expectedCloseDate ?? '',
    };
}

function toCreatePayload(draft: DealDraft): CreateDealPayload {
    return {
        ...EMPTY_DRAFT,
        name: draft.name,
        value: draft.value,
        currency: draft.currency,
        pipeline: draft.pipeline,
        stage: draft.stage,
        company: draft.company,
        expectedCloseDate: draft.expectedCloseDate || undefined,
    };
}

type DealCreateContainerProps = {
    open: boolean;
    onOpenChange: (open: boolean) => void;
    defaults?: CreateDefaults;
    /** Renders the shell-less {@link NewDealForm} directly, for embedding in the morphing launcher. */
    embedded?: boolean;
    /** Cancel handler for embedded mode — steps back to the launcher selector. */
    onCancel?: () => void;
    /** Reports embedded-form edits so the mobile launcher can guard every dismissal path. */
    onDirtyChange?: (dirty: boolean) => void;
    currentUserId?: number | null;
    initialDraft?: DealDraft;
    initialDraftGeneration?: number;
    /** Reserved for the shell overlay; embedded and routed composers remain persistence-free. */
    draftPersistence?: boolean;
    requestInit?: RequestInit;
};

/**
 * Deal quick-create shared by the persistent shell overlay and persistence-free embedded launcher.
 * Only the shell enables the draft controls; the embedded launcher reports dirtiness to its host.
 */
export default function DealCreateContainer({
    open,
    onOpenChange,
    defaults,
    embedded = false,
    onCancel,
    onDirtyChange,
    currentUserId = null,
    initialDraft,
    initialDraftGeneration,
    draftPersistence = false,
    requestInit,
}: DealCreateContainerProps) {
    const { activeWorkspaceId } = useWorkspace();
    const draft = useFormDraft<DealDraft>({
        keyParts: {
            userId: currentUserId,
            workspaceId: activeWorkspaceId,
            formType: 'deal',
            scope: 'global',
        },
        version: DRAFT_VERSIONS.deal,
        initialKeyGeneration: initialDraftGeneration,
    });
    const router = useRouter();
    const t = useTranslations('Actions');
    const showApiError = useApiErrorToast('Actions');
    const restoring = initialDraft !== undefined && initialDraftGeneration !== undefined;

    const [loaded, setLoaded] = useState(false);
    const [pipelines, setPipelines] = useState<Pipeline[]>([]);
    const [stagesByPipeline, setStagesByPipeline] = useState<Record<number, Stage[]>>({});
    const [selectedCompany, setSelectedCompany] = useState<Company | null>(null);
    const stagesByPipelineRef = useRef<Record<number, Stage[]>>({});
    useEffect(() => {
        stagesByPipelineRef.current = stagesByPipeline;
    }, [stagesByPipeline]);

    const [payload, setPayload] = useState<CreateDealPayload>(EMPTY_DRAFT);
    const restoredPayloadRef = useRef<CreateDealPayload | null>(null);
    const [creating, setCreating] = useState(false);
    const [succeeded, setSucceeded] = useState(false);
    const [restorationReady, setRestorationReady] = useState(!restoring);
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
            const nextPipelines = await getPipelines(requestInit).catch((error: unknown): Pipeline[] => {
                if (restoring) throw error;
                return [];
            });
            if (cancelled || requestInit?.signal?.aborted) return;
            setPipelines(nextPipelines);
            const entries = await Promise.all(
                nextPipelines.map(
                    async (p) => [
                        p.id,
                        await getStagesByPipelineId(p.id, requestInit).catch((error: unknown): Stage[] => {
                            if (restoring) throw error;
                            return [];
                        }),
                    ] as const,
                ),
            );
            if (cancelled || requestInit?.signal?.aborted) return;
            const nextStages = Object.fromEntries(entries);
            setStagesByPipeline(nextStages);
            if (restoring && initialDraft) {
                const pipelineExists = initialDraft.pipeline === null || nextPipelines.some(
                    (pipeline) => pipeline.id === initialDraft.pipeline,
                );
                const pipeline = pipelineExists ? initialDraft.pipeline : null;
                const stageExists = initialDraft.stage === null || (
                    pipeline !== null && (nextStages[pipeline]?.some((stage) => stage.id === initialDraft.stage) ?? false)
                );
                const stage = stageExists ? initialDraft.stage : null;
                const companies = initialDraft.company === null
                    ? []
                    : await getCompaniesByIds([initialDraft.company], requestInit);
                if (cancelled || requestInit?.signal?.aborted) return;
                const restoredCompany = companies.find((company) => company.id === initialDraft.company) ?? null;
                const companyExists = initialDraft.company === null || restoredCompany !== null;
                const company = restoredCompany?.id ?? null;
                const restoredPayload = toCreatePayload({ ...initialDraft, pipeline, stage, company });
                setSelectedCompany(restoredCompany);
                restoredPayloadRef.current = pipelineExists && stageExists && companyExists ? restoredPayload : null;
                setPayload(restoredPayload);
                if (!pipelineExists || !stageExists || !companyExists) {
                    toastWarn(t('feedback.restoredDealReferenceUnavailable'));
                }
                setLoaded(true);
                setRestorationReady(true);
                return;
            }
            setLoaded(true);
            const pipelineId = defaults?.pipelineId;
            if (pipelineId && nextStages[pipelineId]?.length) {
                setPayload((prev) =>
                    prev.pipeline === pipelineId && !prev.stage ? { ...prev, stage: nextStages[pipelineId][0].id } : prev,
                );
            }
        })().catch(() => {
            if (cancelled || requestInit?.signal?.aborted) return;
            toastError(t('feedback.linkedRecordLoadFailed'));
            onOpenChange(false);
        });
        return () => {
            cancelled = true;
        };
    }, [open, loaded, defaults?.pipelineId, initialDraft, onOpenChange, requestInit, restoring, t]);

    useEffect(() => {
        if (!open || restoring) return;
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
    }, [open, defaults?.companyId, defaults?.pipelineId, restoring]);

    const seedPipelineId = defaults?.pipelineId ?? 0;
    const seededBaseline: CreateDealPayload = {
        ...EMPTY_DRAFT,
        company: defaults?.companyId ?? null,
        pipeline: seedPipelineId,
        stage: (seedPipelineId ? stagesByPipeline[seedPipelineId] : undefined)?.[0]?.id ?? 0,
    };
    const isDirty = !creating && !succeeded && isDealPayloadDirty(payload, seededBaseline);
    const formReady = restorationReady;
    const persistDraft = draftPersistence ? draft.persist : undefined;
    const clearDraft = draftPersistence ? draft.clear : undefined;

    useEffect(() => {
        onDirtyChange?.(isDirty);
    }, [isDirty, onDirtyChange]);
    useEffect(() => () => onDirtyChange?.(false), [onDirtyChange]);

    useEffect(() => {
        if (!persistDraft || !clearDraft || !open || !formReady || creating || succeeded) return;
        if (
            isDirty &&
            restoredPayloadRef.current !== null &&
            !isDealPayloadDirty(payload, restoredPayloadRef.current)
        ) {
            return;
        }
        restoredPayloadRef.current = null;
        if (isDirty) persistDraft(toStoredDraft(payload));
        else clearDraft();
    }, [clearDraft, creating, formReady, isDirty, open, payload, persistDraft, succeeded]);

    const handleOpenChange = (next: boolean) => {
        if (!next && creating) return;
        if (!next) {
            clearPendingClose();
            if (isDirty) clearDraft?.();
        }
        onOpenChange(next);
    };

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
            clearDraft?.();
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

    if (!formReady) return null;

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
                selectedCompany={selectedCompany}
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
            selectedCompany={selectedCompany}
            createNewDeal={createNewDeal}
            requestInit={requestInit}
        />
    );
}
