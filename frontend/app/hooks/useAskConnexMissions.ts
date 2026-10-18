'use client';

import { useCallback, useEffect, useRef, useState } from 'react';

import { ApiError, controlAiAgentRun, createAiAgentRun, getAiAgentOutput, getAiAgentRun, getAiAgentRuns } from '@/app/lib/api';
import { askConnexMissionProblem, newerAskConnexMission, type AskConnexMissionProblem } from '@/app/lib/askConnexMissions';
import type { AiAgentOutput, AiAgentRecordScope, AiAgentRun, AiAgentRunControl, AiAgentRunCreateRequest } from '@/app/lib/types';

const REFRESH_INTERVAL_MS = 5_000;
const MAX_REFRESH_INTERVAL_MS = 30_000;

type MissionSnapshot = {
    key: string;
    runs: AiAgentRun[];
    nextCursor: string | null;
    loading: boolean;
    problem: AskConnexMissionProblem | null;
    output: AiAgentOutput | null;
    pendingId: number | null;
};

function activeMissionRevision(runs: readonly AiAgentRun[]): string {
    return runs.filter((run) => !run.contentWithheld && (run.state === 'queued' || run.state === 'running'))
        .map((run) => `${run.id}:${run.state}:${run.stateVersion}`).join(',');
}

/**
 * Owns session mission discovery and controls under an abortable account/workspace identity.
 * Entry and realtime invalidations reload once; only active missions poll, with bounded backoff.
 */
export function useAskConnexMissions({
    identity, sessionId, enabled, visible, refreshVersion, contentVersion = 0, onChanged, onContentInvalidated,
}: {
    identity: string;
    sessionId: number | null;
    enabled: boolean;
    visible: boolean;
    refreshVersion: number;
    /** Invalidates rendered content and all pending responses before canonical privacy reload. */
    contentVersion?: number;
    onChanged: (sessionId: number) => void;
    /** Withdraws other session projections when canonical mission state loses readable content. */
    onContentInvalidated?: (sessionId: number) => void;
}) {
    const key = `${identity}:${sessionId ?? 'new'}:${contentVersion}`;
    const [snapshot, setSnapshot] = useState<MissionSnapshot | null>(null);
    const [reload, setReload] = useState(0);
    const current = enabled && snapshot?.key === key ? snapshot : null;
    const controllerRef = useRef<AbortController | null>(null);
    const refreshRef = useRef(0);
    const operationRef = useRef(false);
    const loadedPagesRef = useRef(1);
    const creationRef = useRef<{ identity: string; sessionId: number; body: AiAgentRunCreateRequest } | null>(null);
    const changedRef = useRef(onChanged);
    const invalidatedRef = useRef(onContentInvalidated);
    const retainedRunsRef = useRef<readonly AiAgentRun[]>([]);
    const pollTimerRef = useRef<number | null>(null);
    const pollStateRef = useRef<{ key: string; revision: string; delay: number } | null>(null);
    useEffect(() => { changedRef.current = onChanged; }, [onChanged]);
    useEffect(() => { invalidatedRef.current = onContentInvalidated; }, [onContentInvalidated]);
    useEffect(() => { retainedRunsRef.current = current?.runs ?? []; }, [current?.runs]);

    useEffect(() => {
        if (!enabled) return;
        const controller = new AbortController();
        controllerRef.current = controller;
        operationRef.current = false;
        loadedPagesRef.current = 1;
        pollStateRef.current = null;
        setSnapshot({ key, runs: [], nextCursor: null, loading: sessionId !== null, problem: null, output: null, pendingId: null });
        return () => {
            controller.abort();
            if (controllerRef.current === controller) controllerRef.current = null;
        };
    }, [enabled, key, sessionId]);

    useEffect(() => {
        if (!visible) setSnapshot(null);
    }, [visible]);

    const refresh = useCallback(async () => {
        const signal = controllerRef.current?.signal;
        if (!enabled || sessionId === null || !signal || signal.aborted) return;
        if (pollTimerRef.current !== null) {
            window.clearTimeout(pollTimerRef.current);
            pollTimerRef.current = null;
        }
        const revision = ++refreshRef.current;
        try {
            let page = await getAiAgentRuns({ sessionId, limit: 20 }, { signal });
            const runs = [...page.items];
            for (let index = 1; index < loadedPagesRef.current && page.nextCursor !== null; index++) {
                if (signal.aborted || revision !== refreshRef.current) return;
                page = await getAiAgentRuns({ sessionId, limit: 20, cursor: page.nextCursor }, { signal });
                runs.push(...page.items.filter((item) => !runs.some((existing) => existing.id === item.id)));
            }
            if (signal.aborted || revision !== refreshRef.current) return;
            const lastId = runs.at(-1)?.id;
            const contentInvalidated = retainedRunsRef.current.some((previous) => {
                if (previous.contentWithheld) return false;
                const durable = runs.find((run) => run.id === previous.id);
                return durable ? durable.contentWithheld
                    : page.nextCursor === null || (lastId !== undefined && previous.id >= lastId);
            });
            const activeRevision = activeMissionRevision(runs);
            const previousPoll = pollStateRef.current;
            pollStateRef.current = { key, revision: activeRevision,
                delay: previousPoll?.key === key && previousPoll.revision === activeRevision
                    ? Math.min(previousPoll.delay * 2, MAX_REFRESH_INTERVAL_MS) : REFRESH_INTERVAL_MS };
            setSnapshot((previous) => ({
                key, runs, nextCursor: page.nextCursor, loading: false, problem: null,
                output: previous?.key === key && runs.some((run) => run.id === previous.output?.runId
                    && run.outputAvailable && !run.contentWithheld) ? previous.output : null,
                pendingId: previous?.key === key ? previous.pendingId : null,
            }));
            if (contentInvalidated) invalidatedRef.current?.(sessionId);
        } catch (error) {
            if (signal.aborted || revision !== refreshRef.current) return;
            setSnapshot({ key, runs: [], nextCursor: null, loading: false, output: null, pendingId: null,
                problem: error instanceof ApiError ? askConnexMissionProblem(error.status, error.code) : 'failed' });
        }
    }, [enabled, key, sessionId]);

    useEffect(() => {
        if (!enabled || !visible || sessionId === null) return;
        pollStateRef.current = null;
        void refresh();
    }, [enabled, refresh, refreshVersion, reload, sessionId, visible]);

    useEffect(() => {
        if (!enabled || !visible || !current?.runs.length) return;
        const revision = activeMissionRevision(current.runs);
        if (revision.length === 0) return;
        const pollState = pollStateRef.current;
        const delay = pollState?.key === key && pollState.revision === revision
            ? pollState.delay : REFRESH_INTERVAL_MS;
        const timer = window.setTimeout(() => {
            pollTimerRef.current = null;
            void refresh();
        }, delay);
        pollTimerRef.current = timer;
        return () => {
            window.clearTimeout(timer);
            if (pollTimerRef.current === timer) pollTimerRef.current = null;
        };
    }, [current?.runs, enabled, key, refresh, visible]);

    const upsert = useCallback((run: AiAgentRun) => {
        const retained = retainedRunsRef.current.find((item) => item.id === run.id);
        if (sessionId !== null && retained && !retained.contentWithheld
                && newerAskConnexMission(retained, run).contentWithheld) {
            invalidatedRef.current?.(sessionId);
        }
        setSnapshot((previous) => {
            if (previous?.key !== key) return previous;
            const existing = previous.runs.find((item) => item.id === run.id);
            const updated = existing ? newerAskConnexMission(existing, run) : run;
            return { ...previous, problem: null, loading: false,
                runs: [updated, ...previous.runs.filter((item) => item.id !== run.id)].sort((a, b) => b.id - a.id),
                output: updated.contentWithheld || !updated.outputAvailable ? null : previous.output };
        });
    }, [key, sessionId]);

    const create = useCallback(async (targetSessionId: number, goal: string, scope: AiAgentRecordScope, creationSignal?: AbortSignal): Promise<boolean> => {
        const signal = creationSignal ?? controllerRef.current?.signal;
        if (!enabled || operationRef.current || !signal || signal.aborted) return false;
        const previous = creationRef.current;
        const body: AiAgentRunCreateRequest = previous?.identity === identity
            && previous.sessionId === targetSessionId && previous.body.goal === goal
            && previous.body.scope.recordKind === scope.recordKind && previous.body.scope.recordId === scope.recordId
            ? previous.body : { clientRequestKey: crypto.randomUUID(), missionType: 'record_review_v1', goal, scope };
        creationRef.current = { identity, sessionId: targetSessionId, body };
        operationRef.current = true;
        setSnapshot((value) => value?.key === key ? { ...value, pendingId: 0, problem: null } : value);
        try {
            const run = await createAiAgentRun(targetSessionId, body, { signal });
            if (signal.aborted) return false;
            refreshRef.current++;
            creationRef.current = null;
            upsert(run);
            changedRef.current(targetSessionId);
            return true;
        } catch (error) {
            if (!signal.aborted) setSnapshot((value) => value?.key === key ? { ...value,
                problem: error instanceof ApiError ? askConnexMissionProblem(error.status, error.code) : 'failed' } : value);
            return false;
        } finally {
            if (!signal.aborted) {
                operationRef.current = false;
                setSnapshot((value) => value?.key === key ? { ...value, pendingId: null } : value);
            }
        }
    }, [enabled, identity, key, upsert]);

    const control = useCallback(async (run: AiAgentRun, action: AiAgentRunControl) => {
        const signal = controllerRef.current?.signal;
        if (!enabled || operationRef.current || !signal || signal.aborted || !run.availableActions.includes(action)) return;
        operationRef.current = true;
        setSnapshot((value) => value?.key === key ? { ...value, pendingId: run.id, problem: null, output: null } : value);
        try {
            const updated = await controlAiAgentRun(run.id, action, run.stateVersion, { signal });
            if (signal.aborted) return;
            refreshRef.current++;
            upsert(updated);
            if (sessionId !== null) changedRef.current(sessionId);
        } catch (error) {
            if (signal.aborted) return;
            const problem = error instanceof ApiError ? askConnexMissionProblem(error.status, error.code) : 'failed';
            if (error instanceof ApiError && error.status === 409) {
                try {
                    const updated = await getAiAgentRun(run.id, { signal });
                    if (!signal.aborted) upsert(updated);
                } catch { if (!signal.aborted) await refresh(); }
            } else await refresh();
            if (!signal.aborted) setSnapshot((value) => value?.key === key ? { ...value, problem } : value);
        } finally {
            if (!signal.aborted) {
                operationRef.current = false;
                setSnapshot((value) => value?.key === key ? { ...value, pendingId: null } : value);
            }
        }
    }, [enabled, key, refresh, sessionId, upsert]);

    const viewOutput = useCallback(async (run: AiAgentRun) => {
        const signal = controllerRef.current?.signal;
        if (!enabled || operationRef.current || !signal || signal.aborted || !run.outputAvailable || run.contentWithheld) return;
        operationRef.current = true;
        setSnapshot((value) => value?.key === key ? { ...value, pendingId: run.id, output: null, problem: null } : value);
        try {
            const output = await getAiAgentOutput(run.id, { signal });
            if (!signal.aborted) setSnapshot((value) => value?.key === key
                && value.runs.some((item) => item.id === run.id && item.outputAvailable && !item.contentWithheld)
                ? { ...value, output } : value);
        } catch (error) {
            if (signal.aborted) return;
            await refresh();
            if (!signal.aborted) setSnapshot((value) => value?.key === key ? { ...value, output: null,
                problem: error instanceof ApiError ? askConnexMissionProblem(error.status, error.code) : 'failed' } : value);
        } finally {
            if (!signal.aborted) {
                operationRef.current = false;
                setSnapshot((value) => value?.key === key ? { ...value, pendingId: null } : value);
            }
        }
    }, [enabled, key, refresh]);

    const loadMore = useCallback(async () => {
        const signal = controllerRef.current?.signal;
        if (!current?.nextCursor || sessionId === null || operationRef.current || !signal || signal.aborted) return;
        operationRef.current = true;
        const revision = ++refreshRef.current;
        setSnapshot((value) => value?.key === key ? { ...value, pendingId: -1 } : value);
        try {
            const page = await getAiAgentRuns({ sessionId, limit: 20, cursor: current.nextCursor }, { signal });
            if (signal.aborted || revision !== refreshRef.current) return;
            loadedPagesRef.current++;
            setSnapshot((value) => value?.key === key ? { ...value,
                runs: [...value.runs, ...page.items.filter((item) => !value.runs.some((existing) => existing.id === item.id))],
                nextCursor: page.nextCursor, problem: null } : value);
        } catch (error) {
            if (!signal.aborted) setSnapshot((value) => value?.key === key ? { ...value,
                problem: error instanceof ApiError ? askConnexMissionProblem(error.status, error.code) : 'failed' } : value);
        } finally {
            if (!signal.aborted) {
                operationRef.current = false;
                setSnapshot((value) => value?.key === key ? { ...value, pendingId: null } : value);
            }
        }
    }, [current?.nextCursor, key, sessionId]);

    return {
        runs: current?.runs ?? [], loading: current?.loading ?? false, problem: current?.problem ?? null,
        output: current?.output ?? null, pendingId: current?.pendingId ?? null, hasMore: current?.nextCursor != null,
        create, control, viewOutput, loadMore, retry: () => setReload((value) => value + 1),
        closeOutput: () => setSnapshot((value) => value?.key === key ? { ...value, output: null } : value),
    };
}
