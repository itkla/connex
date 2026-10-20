'use client';

import { useCallback, useEffect, useRef, useState } from 'react';

import { ApiError, controlAiAgentRun, createAiAgentRun, decideAiAgentProposals, getAiAgentOutput, getAiAgentRun, getAiAgentRuns, isWorkspaceUnavailableError } from '@/app/lib/api';
import { askConnexMissionProblem, askConnexMissionProposalGroup, newerAskConnexMission, type AskConnexMissionProblem } from '@/app/lib/askConnexMissions';
import { askConnexToolCardAffordances } from '@/app/lib/askConnex';
import { useProposalReviewNow } from '@/app/hooks/useProposalReviewNow';
import { workspacePinnedRequest } from '@/app/lib/renderedWorkspace';
import { useApiErrorToast } from '@/app/hooks/useApiErrorToast';
import type { AiAgentMissionType, AiAgentOutput, AiAgentProposalDecisionResult, AiAgentRecordScope, AiAgentRun, AiAgentRunControl, AiAgentRunCreateRequest } from '@/app/lib/types';

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
    const revisions: string[] = [];
    for (const run of runs) {
        if (!run.contentWithheld && (run.state === 'queued' || run.state === 'running')) {
            revisions.push(`${run.id}:${run.state}:${run.stateVersion}`);
        }
    }
    return revisions.join(',');
}

function withholdMission(run: AiAgentRun): AiAgentRun {
    return { ...run, contentWithheld: true, outputAvailable: false, outputRevision: null, goal: null,
        scope: null, progress: null, proposalCounts: null, proposalMembershipRevision: null,
        proposalFrozenAt: null, proposalFirstPreparedAt: null, reviewExpiresAt: null,
        availableActions: run.availableActions.filter((action) => action === 'cancel') };
}

/**
 * Owns session mission discovery and controls under an abortable account/workspace identity.
 * Entry and realtime invalidations reload once; only active missions poll, with bounded backoff.
 */
export function useAskConnexMissions({
    identity, workspaceId, sessionId, enabled, visible, refreshVersion, contentVersion = 0, onChanged, onContentInvalidated,
}: {
    identity: string;
    workspaceId: number | null;
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
    const showApiError = useApiErrorToast('AskConnex');
    const key = `${identity}:${sessionId ?? 'new'}:${contentVersion}`;
    const [snapshot, setSnapshot] = useState<MissionSnapshot | null>(null);
    const [reload, setReload] = useState(0);
    const current = enabled && snapshot?.key === key ? snapshot : null;
    const [reviewState, setReviewState] = useState<{
        key: string;
        excluded: ReadonlySet<number>;
        pending: ReadonlyMap<number, 'approve' | 'reject'>;
        result: AiAgentProposalDecisionResult | null;
    } | null>(null);
    const reviewKey = current?.output ? `${key}:${current.output.runId}:${current.output.proposalSet.revision}` : null;
    const review = reviewState?.key === reviewKey ? reviewState : null;
    const now = useProposalReviewNow(current?.output ? [current.output.proposalSet.reviewExpiresAt] : []);
    const proposalGroup = current?.output ? askConnexMissionProposalGroup(current.output,
        review?.excluded ?? new Set(), review?.pending ?? new Map(), review?.result ?? null, now) : null;
    const retainedOutputRef = useRef<AiAgentOutput | null>(null);
    const outputViewRef = useRef(0);
    useEffect(() => { retainedOutputRef.current = current?.output ?? null; }, [current?.output]);
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
        setReviewState(null);
        loadedPagesRef.current = 1;
        pollStateRef.current = null;
        setSnapshot({ key, runs: [], nextCursor: null, loading: sessionId !== null, problem: null, output: null, pendingId: null });
        return () => {
            controller.abort();
            if (controllerRef.current === controller) controllerRef.current = null;
        };
    }, [enabled, key, sessionId]);

    useEffect(() => {
        if (!visible) {
            outputViewRef.current++;
            setSnapshot(null);
        }
    }, [visible]);

    const refresh = useCallback(async (reloadOutput = false) => {
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
            const openOutput = retainedOutputRef.current;
            const outputRun = runs.find((run) => run.id === openOutput?.runId && run.outputAvailable && !run.contentWithheld);
            if (openOutput !== null && outputRun === undefined) {
                outputViewRef.current++;
                setReviewState(null);
            }
            const outputChanged = openOutput !== null && outputRun !== undefined
                && (reloadOutput || outputRun.outputRevision !== openOutput.outputRevision);
            setSnapshot((previous) => ({
                key, runs, nextCursor: page.nextCursor, loading: false, problem: null,
                output: previous?.key === key && runs.some((run) => run.id === previous.output?.runId
                    && run.outputAvailable && !run.contentWithheld && run.outputRevision === previous.output.outputRevision) ? previous.output : null,
                pendingId: previous?.key === key ? previous.pendingId : null,
            }));
            if (contentInvalidated) {
                outputViewRef.current++;
                setReviewState(null);
                invalidatedRef.current?.(sessionId);
            } else if (outputChanged && outputRun) {
                const output = await getAiAgentOutput(outputRun.id, { signal });
                if (!signal.aborted && revision === refreshRef.current) {
                    setSnapshot((previous) => previous?.key === key && previous.runs.some((run) =>
                        run.id === output.runId && run.outputRevision === output.outputRevision && !run.contentWithheld)
                        ? { ...previous, output } : previous);
                }
            }
        } catch (error) {
            if (signal.aborted || revision !== refreshRef.current) return;
            if (error instanceof ApiError && [401, 403, 404, 410].includes(error.status)) {
                outputViewRef.current++;
                setReviewState(null);
                invalidatedRef.current?.(sessionId);
            }
            setSnapshot({ key, runs: [], nextCursor: null, loading: false, output: null, pendingId: null,
                problem: error instanceof ApiError ? askConnexMissionProblem(error.status, error.code) : 'failed' });
        }
    }, [enabled, key, sessionId]);

    useEffect(() => {
        if (!enabled || !visible || sessionId === null) return;
        pollStateRef.current = null;
        void refresh(true);
    }, [enabled, refresh, refreshVersion, reload, sessionId, visible]);

    useEffect(() => {
        if (!enabled || !visible) return;
        const reloadCanonical = () => { void refresh(true); };
        window.addEventListener('focus', reloadCanonical);
        window.addEventListener('online', reloadCanonical);
        return () => {
            window.removeEventListener('focus', reloadCanonical);
            window.removeEventListener('online', reloadCanonical);
        };
    }, [enabled, refresh, visible]);

    const deadlineRefreshRef = useRef<string | null>(null);
    const reviewDeadline = current?.output?.proposalSet.reviewExpiresAt;
    const expiredReview = Boolean(reviewDeadline && now >= Date.parse(reviewDeadline));
    useEffect(() => {
        const deadlineKey = `${key}:${current?.output?.runId}:${reviewDeadline}`;
        if (expiredReview && deadlineRefreshRef.current !== deadlineKey) {
            deadlineRefreshRef.current = deadlineKey;
            void refresh(true);
        }
    }, [current?.output?.runId, expiredReview, key, refresh, reviewDeadline]);

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
                output: updated.contentWithheld || !updated.outputAvailable
                    || (previous.output?.runId === updated.id && previous.output.outputRevision !== updated.outputRevision)
                    ? null : previous.output };
        });
    }, [key, sessionId]);

    const create = useCallback(async (targetSessionId: number, goal: string, scope: AiAgentRecordScope, creationSignal?: AbortSignal, missionType: AiAgentMissionType = 'record_review_v1'): Promise<boolean> => {
        const signal = creationSignal ?? controllerRef.current?.signal;
        if (!enabled || operationRef.current || !signal || signal.aborted) return false;
        const previous = creationRef.current;
        const body: AiAgentRunCreateRequest = previous?.identity === identity
            && previous.sessionId === targetSessionId && previous.body.goal === goal && previous.body.missionType === missionType
            && previous.body.scope.recordKind === scope.recordKind && previous.body.scope.recordId === scope.recordId
            ? previous.body : { clientRequestKey: crypto.randomUUID(), missionType, goal, scope };
        creationRef.current = { identity, sessionId: targetSessionId, body };
        operationRef.current = true;
        setSnapshot((value) => value?.key === key ? { ...value, pendingId: 0, problem: null } : value);
        try {
            const run = await createAiAgentRun(targetSessionId, body, workspacePinnedRequest(workspaceId, { signal }));
            if (signal.aborted) return false;
            refreshRef.current++;
            creationRef.current = null;
            upsert(run);
            changedRef.current(targetSessionId);
            return true;
        } catch (error) {
            if (!signal.aborted && isWorkspaceUnavailableError(error)) showApiError(error);
            if (!signal.aborted) setSnapshot((value) => value?.key === key ? { ...value,
                problem: error instanceof ApiError ? askConnexMissionProblem(error.status, error.code) : 'failed' } : value);
            return false;
        } finally {
            if (!signal.aborted) {
                operationRef.current = false;
                setSnapshot((value) => value?.key === key ? { ...value, pendingId: null } : value);
            }
        }
    }, [enabled, identity, key, showApiError, upsert, workspaceId]);

    const control = useCallback(async (run: AiAgentRun, action: AiAgentRunControl) => {
        const signal = controllerRef.current?.signal;
        if (!enabled || operationRef.current || !signal || signal.aborted || !run.availableActions.includes(action)) return;
        operationRef.current = true;
        outputViewRef.current++;
        setSnapshot((value) => value?.key === key ? { ...value, pendingId: run.id, problem: null, output: null } : value);
        try {
            const updated = await controlAiAgentRun(run.id, action, run.stateVersion, workspacePinnedRequest(workspaceId, { signal }));
            if (signal.aborted) return;
            refreshRef.current++;
            upsert(updated);
            if (sessionId !== null) changedRef.current(sessionId);
        } catch (error) {
            if (signal.aborted) return;
            if (isWorkspaceUnavailableError(error)) showApiError(error);
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
    }, [enabled, key, refresh, sessionId, showApiError, upsert, workspaceId]);

    const viewOutput = useCallback(async (run: AiAgentRun) => {
        const signal = controllerRef.current?.signal;
        if (!enabled || operationRef.current || !signal || signal.aborted || !run.outputAvailable || run.contentWithheld) return;
        operationRef.current = true;
        outputViewRef.current++;
        setReviewState(null);
        setSnapshot((value) => value?.key === key ? { ...value, pendingId: run.id, output: null, problem: null } : value);
        try {
            const output = await getAiAgentOutput(run.id, { signal });
            if (!signal.aborted) setSnapshot((value) => value?.key === key
                && value.runs.some((item) => item.id === run.id && item.outputAvailable && !item.contentWithheld
                    && item.outputRevision === output.outputRevision)
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

    const decide = useCallback(async (toolCallIds: number[], decision: 'approve' | 'discard') => {
        const signal = controllerRef.current?.signal;
        const output = current?.output;
        if (!enabled || !output || !reviewKey || !proposalGroup || operationRef.current || !signal || signal.aborted) return;
        const action = decision === 'approve' ? 'approve' : 'reject';
        const allowed = new Set(proposalGroup.cards.filter((card) =>
            toolCallIds.includes(card.id) && askConnexToolCardAffordances(card, now).includes(action)).map((card) => card.id));
        const items = output.proposalSet.items.filter((item) => allowed.has(item.card.id)).map((item) => ({
            actionSlot: item.actionSlot, expectedPreparationId: item.preparationId, decision,
        }));
        if (items.length === 0) return;
        const pinned = workspacePinnedRequest(workspaceId, { signal });
        let settled: AiAgentProposalDecisionResult | null = null;
        const outputView = outputViewRef.current;
        operationRef.current = true;
        setSnapshot((previous) => previous?.key === key ? { ...previous, pendingId: output.runId } : previous);
        setReviewState({ key: reviewKey, excluded: review?.excluded ?? new Set(), result: review?.result ?? null,
            pending: new Map([...allowed].map((id) => [id, action])) });
        try {
            const result = await decideAiAgentProposals(output.runId, {
                expectedProposalRevision: output.proposalSet.revision, items,
            }, pinned);
            settled = result;
            const retained = retainedOutputRef.current;
            if (signal.aborted || outputViewRef.current !== outputView || (retained !== null && (retained.runId !== output.runId
                || retained.proposalSet.revision !== output.proposalSet.revision
                || items.some((selected) => !retained.proposalSet.items.some((member) =>
                    member.actionSlot === selected.actionSlot && member.preparationId === selected.expectedPreparationId))))) return;
            setReviewState((previous) => previous?.key === reviewKey ? { ...previous, pending: new Map(), result } : previous);
            if (result.contentWithheld) {
                setSnapshot((previous) => previous?.key === key ? { ...previous, output: null,
                    runs: previous.runs.map((run) => run.id === output.runId ? withholdMission(run) : run) } : previous);
                setReviewState(null);
                if (sessionId !== null) invalidatedRef.current?.(sessionId);
                await refresh();
                return;
            }
            const [run, canonical] = await Promise.all([
                getAiAgentRun(output.runId, { signal }), getAiAgentOutput(output.runId, { signal }),
            ]);
            if (signal.aborted || outputViewRef.current !== outputView) return;
            upsert(run);
            setSnapshot((previous) => previous?.key === key && !run.contentWithheld
                && previous.runs.some((retained) => retained.id === canonical.runId && !retained.contentWithheld
                    && retained.outputAvailable && retained.outputRevision === canonical.outputRevision)
                ? { ...previous, output: canonical } : previous);
            if (sessionId !== null) changedRef.current(sessionId);
        } catch (error) {
            if (signal.aborted || outputViewRef.current !== outputView) return;
            if (isWorkspaceUnavailableError(error)) showApiError(error);
            const inaccessible = error instanceof ApiError && [401, 403, 404, 410].includes(error.status);
            const refusal = settled === null && error instanceof ApiError && error.status === 409;
            if (inaccessible) {
                setReviewState(null);
                setSnapshot((previous) => previous?.key === key ? { ...previous, output: null,
                    runs: previous.runs.map((run) => run.id === output.runId ? withholdMission(run) : run) } : previous);
                if (sessionId !== null) invalidatedRef.current?.(sessionId);
            } else {
                setReviewState((previous) => previous?.key === reviewKey ? { ...previous, pending: new Map(),
                    result: settled ?? { complete: false, stopReason: refusal ? 'proposal_conflict' : 'reload_required',
                        stateVersion: null, outputRevision: output.outputRevision, contentWithheld: false,
                        items: items.map((item) => ({ actionSlot: item.actionSlot,
                            outcome: refusal ? 'refused' : 'indeterminate', code: error instanceof ApiError ? error.code ?? null : null })) } } : previous);
            }
            setSnapshot((previous) => previous?.key === key ? { ...previous,
                problem: error instanceof ApiError ? askConnexMissionProblem(error.status, error.code) : 'failed' } : previous);
            if (settled === null) await refresh();
        } finally {
            if (!signal.aborted) {
                operationRef.current = false;
                setReviewState((previous) => previous?.key === reviewKey ? { ...previous, pending: new Map() } : previous);
                setSnapshot((previous) => previous?.key === key ? { ...previous, pendingId: null } : previous);
            }
        }
    }, [current?.output, enabled, key, now, proposalGroup, refresh, review, reviewKey, sessionId, showApiError, upsert, workspaceId]);

    const toggleInclusion = (id: number) => {
        if (!reviewKey || operationRef.current) return;
        setReviewState((previous) => {
            const excluded = new Set(previous?.key === reviewKey ? previous.excluded : []);
            if (excluded.has(id)) excluded.delete(id); else excluded.add(id);
            return { key: reviewKey, excluded, pending: previous?.key === reviewKey ? previous.pending : new Map(),
                result: previous?.key === reviewKey ? previous.result : null };
        });
    };

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
        proposalGroup, decisionResult: review?.result ?? null, toggleInclusion, decide,
        create, control, viewOutput, loadMore, retry: () => setReload((value) => value + 1),
        closeOutput: () => { outputViewRef.current++; setReviewState(null); setSnapshot((value) => value?.key === key ? { ...value, output: null } : value); },
    };
}
