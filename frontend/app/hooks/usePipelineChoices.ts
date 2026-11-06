'use client';

import { useCallback, useState, type Dispatch, type SetStateAction } from 'react';
import { useWorkspace } from '@/app/hooks/useWorkspace';
import type { Pipeline, Stage } from '@/app/lib/types';

type Choices = { scope: object; workspaceId: number | null; pipelines: Pipeline[]; stages: Record<number, Stage[]> };
type PipelineChoices = {
    scope: object;
    activeWorkspaceId: number | null;
    pipelines: Pipeline[];
    stagesByPipeline: Record<number, Stage[]>;
    setPipelines: Dispatch<SetStateAction<Pipeline[]>>;
    setStagesByPipeline: Dispatch<SetStateAction<Record<number, Stage[]>>>;
};

/** Scopes pipeline choices and their asynchronous updates to one rendered workspace. */
export function usePipelineChoices(): PipelineChoices {
    const { activeWorkspaceId } = useWorkspace();
    const [choices, setChoices] = useState<Choices>(() => ({ scope: {}, workspaceId: activeWorkspaceId, pipelines: [], stages: {} }));
    if (choices.workspaceId !== activeWorkspaceId) {
        setChoices({ scope: {}, workspaceId: activeWorkspaceId, pipelines: [], stages: {} });
    }
    const setPipelines = useCallback((update: SetStateAction<Pipeline[]>) => {
        setChoices((current) => current.scope !== choices.scope || current.workspaceId !== activeWorkspaceId ? current : {
            ...current, pipelines: typeof update === 'function' ? update(current.pipelines) : update,
        });
    }, [activeWorkspaceId, choices.scope]);
    const setStagesByPipeline = useCallback((update: SetStateAction<Record<number, Stage[]>>) => {
        setChoices((current) => current.scope !== choices.scope || current.workspaceId !== activeWorkspaceId ? current : {
            ...current, stages: typeof update === 'function' ? update(current.stages) : update,
        });
    }, [activeWorkspaceId, choices.scope]);
    return {
        scope: choices.scope,
        activeWorkspaceId,
        pipelines: choices.workspaceId === activeWorkspaceId ? choices.pipelines : [],
        stagesByPipeline: choices.workspaceId === activeWorkspaceId ? choices.stages : {},
        setPipelines,
        setStagesByPipeline,
    };
}
