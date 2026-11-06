'use client';

import { useRef, useState } from 'react';
import { useTranslations } from 'next-intl';
import { Button } from '@/components/ui/button';
import NewPipelineDialog from '@/app/components/records/pipelines/NewPipelineDialog';
import { createPipeline, isFieldError, replacePipelineStages, updatePipeline } from '@/app/lib/api';
import { renderedWorkspaceId, workspacePinnedRequest } from '@/app/lib/renderedWorkspace';
import { useApiErrorToast } from '@/app/hooks/useApiErrorToast';
import { usePermission } from '@/app/hooks/usePermissions';
import type { CreatePipelinePayload, Pipeline, Stage } from '@/app/lib/types';

/** Sets up a usable pipeline without navigating away from the pending deal. */
export default function DealPipelineSetup({ needed, onCreated, requestInit }: {
    needed: boolean;
    onCreated: (pipeline: Pipeline, stages: Stage[]) => void;
    requestInit?: RequestInit;
}) {
    const t = useTranslations('DealsNewDialog');
    const canManage = usePermission('PIPELINE_MANAGE');
    const showApiError = useApiErrorToast('DealsNewDialog');
    const [open, setOpen] = useState(false);
    const [started, setStarted] = useState(false);
    const [creating, setCreating] = useState(false);
    const [payload, setPayload] = useState<CreatePipelinePayload>(() => ({
        name: t('starterPipeline'),
        stages: [
            { name: t('starterOpen'), success: false, failure: false },
            { name: t('starterWon'), success: true, failure: false },
            { name: t('starterLost'), success: false, failure: true },
        ],
    }));
    const createdPipeline = useRef<Pipeline | null>(null);
    const origin = useRef<RequestInit | null>(null);
    const submitting = useRef(false);
    const completed = useRef<{ pipeline: Pipeline; stages: Stage[] } | null>(null);
    const setupButton = useRef<HTMLButtonElement>(null);
    const [validation, setValidation] = useState('');

    const create = async () => {
        if (submitting.current || !origin.current) return;
        const stages = (payload.stages ?? []).map((stage) => ({
            name: stage.name.trim(), success: stage.success ?? false, failure: stage.failure ?? false,
        }));
        if (!stages.some((stage) => stage.name && !stage.success && !stage.failure)
            || stages.some((stage) => !stage.name)) {
            setValidation(t('pipelineNeedsOpenStage'));
            return;
        }
        if (stages.filter((stage) => stage.success).length > 1 || stages.filter((stage) => stage.failure).length > 1) {
            setValidation(t('pipelineTerminalLimit'));
            return;
        }
        submitting.current = true;
        setCreating(true);
        setValidation('');
        try {
            const name = payload.name?.trim() ?? '';
            let pipeline = createdPipeline.current;
            if (!pipeline) {
                pipeline = await createPipeline({ name }, origin.current);
                createdPipeline.current = pipeline;
            } else if (pipeline.name !== name) {
                pipeline = await updatePipeline(pipeline.id, { name }, origin.current);
                createdPipeline.current = pipeline;
            }
            const savedStages = await replacePipelineStages(pipeline.id, [], stages, origin.current);
            completed.current = { pipeline, stages: savedStages };
            setOpen(false);
        } catch (error) {
            if (isFieldError(error)) throw error;
            showApiError(error, 'pipelineSetupFailed', 'pipelineSetupRetry');
        } finally {
            submitting.current = false;
            setCreating(false);
        }
    };

    return (
        <>
            {needed && (
                <div className="grid gap-2 rounded-lg border border-border p-3">
                    <p className="text-sm text-muted-foreground">{canManage ? t('pipelineSetupHint') : t('pipelineSetupAskManager')}</p>
                    {canManage && (
                        <Button ref={setupButton} type="button" variant="outline" onClick={() => {
                            origin.current ??= workspacePinnedRequest(renderedWorkspaceId(), requestInit);
                            setStarted(true);
                            setOpen(true);
                        }}>{t('setupPipeline')}</Button>
                    )}
                </div>
            )}
            {started && (
                <NewPipelineDialog
                    open={open}
                    onOpenChange={setOpen}
                    payload={payload}
                    setPayload={setPayload}
                    isCreating={creating}
                    createNewPipeline={create}
                    stageError={validation}
                    onCloseComplete={() => {
                        const saved = completed.current;
                        if (saved) {
                            completed.current = null;
                            onCreated(saved.pipeline, saved.stages);
                            requestAnimationFrame(() => document.getElementById('deal-stage')?.focus());
                        } else {
                            setupButton.current?.focus();
                        }
                    }}
                />
            )}
        </>
    );
}
