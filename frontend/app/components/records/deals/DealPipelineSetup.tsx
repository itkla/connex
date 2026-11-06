'use client';

import { useRef, useState } from 'react';
import { useTranslations } from 'next-intl';
import { Button } from '@/components/ui/button';
import NewPipelineDialog from '@/app/components/records/pipelines/NewPipelineDialog';
import { createPipeline, getStagesByPipelineId, isFieldError, replacePipelineStages, updatePipeline } from '@/app/lib/api';
import { renderedWorkspaceId, renderedWorkspaceNavigationVersion, workspacePinnedRequest } from '@/app/lib/renderedWorkspace';
import { newStageDraft } from '@/app/components/records/pipelines/stageDrafts';
import { useApiErrorToast } from '@/app/hooks/useApiErrorToast';
import { usePermission } from '@/app/hooks/usePermissions';
import type { CreatePipelinePayload, Pipeline, Stage } from '@/app/lib/types';

type SetupScope = { workspaceId: number | null; navigationVersion: number };

function isCurrentScope(scope: SetupScope): boolean {
    return renderedWorkspaceId() === scope.workspaceId && renderedWorkspaceNavigationVersion() === scope.navigationVersion;
}

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
            { ...newStageDraft(), name: t('starterOpen') },
            { ...newStageDraft(), name: t('starterWon'), success: true },
            { ...newStageDraft(), name: t('starterLost'), failure: true },
        ],
    }));
    const createdPipeline = useRef<Pipeline | null>(null);
    const scopeRef = useRef<SetupScope | null>(null);
    const submitted = useRef<{ key: string; name: string }[]>([]);
    const savedStageIds = useRef(new Map<string, number>());
    const submitting = useRef(false);
    const completed = useRef<{ scope: SetupScope; pipeline: Pipeline; stages: Stage[] } | null>(null);
    const setupButton = useRef<HTMLButtonElement>(null);
    const [validation, setValidation] = useState('');

    const create = async () => {
        const scope = scopeRef.current;
        if (submitting.current || !scope || !isCurrentScope(scope)) return;
        const origin = workspacePinnedRequest(scope.workspaceId, requestInit);
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
                pipeline = await createPipeline({ name }, origin);
                if (!isCurrentScope(scope)) {
                    await replacePipelineStages(pipeline.id, [], stages, origin);
                    return;
                }
                createdPipeline.current = pipeline;
            } else if (pipeline.name !== name) {
                pipeline = await updatePipeline(pipeline.id, { name }, origin);
                if (!isCurrentScope(scope)) return;
                createdPipeline.current = pipeline;
            }
            if (submitted.current.length > 0) {
                const persisted = await getStagesByPipelineId(pipeline.id, origin);
                if (!isCurrentScope(scope)) return;
                for (const previous of submitted.current) {
                    const saved = persisted.find((stage) => stage.name.trim().toLowerCase() === previous.name.toLowerCase());
                    if (saved) savedStageIds.current.set(previous.key, saved.id);
                }
            }
            const requested = stages.map((stage, index) => ({
                ...stage,
                id: savedStageIds.current.get(payload.stages?.[index]?.clientId ?? stage.name),
            }));
            submitted.current = stages.map((stage, index) => ({ key: payload.stages?.[index]?.clientId ?? stage.name, name: stage.name }));
            const savedStages = await replacePipelineStages(pipeline.id, [...savedStageIds.current.values()], requested, origin);
            if (!isCurrentScope(scope)) return;
            completed.current = { scope, pipeline, stages: savedStages };
            setOpen(false);
        } catch (error) {
            if (!isCurrentScope(scope)) return;
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
                            if (!scopeRef.current || !isCurrentScope(scopeRef.current)) {
                                scopeRef.current = { workspaceId: renderedWorkspaceId(), navigationVersion: renderedWorkspaceNavigationVersion() };
                                createdPipeline.current = null;
                                submitted.current = [];
                                savedStageIds.current.clear();
                                completed.current = null;
                            }
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
                        const scope = saved?.scope ?? scopeRef.current;
                        if (!scope || !isCurrentScope(scope)) {
                            completed.current = null;
                            return;
                        }
                        if (saved) {
                            completed.current = null;
                            onCreated(saved.pipeline, saved.stages);
                            requestAnimationFrame(() => {
                                if (isCurrentScope(scope)) document.getElementById('deal-stage')?.focus();
                            });
                        } else {
                            setupButton.current?.focus();
                        }
                    }}
                />
            )}
        </>
    );
}
