import type { AiAgentRealtimeFrame, AiAgentRun, AiAgentRunState, AiChatMessage } from '@/app/lib/types';

/** Displayable reasons are selected locally; provider and server prose never become UI copy. */
export type AskConnexMissionProblem = 'busy' | 'conflict' | 'expired' | 'allowance' | 'withheld' | 'unavailable' | 'forbidden' | 'failed';

export function askConnexMissionProblem(status: number, code?: string): AskConnexMissionProblem {
    switch (code?.toLowerCase()) {
        case 'mission_session_busy': return 'busy';
        case 'mission_version_conflict':
        case 'mission_state_conflict': return 'conflict';
        case 'mission_expired': return 'expired';
        case 'mission_budget_exhausted':
        case 'mission_attempt_limit': return 'allowance';
        case 'mission_output_withheld':
        case 'mission_purged': return 'withheld';
        case 'mission_unavailable': return 'unavailable';
        default: return status === 403 ? 'forbidden' : status === 503 ? 'unavailable' : 'failed';
    }
}

function isRunState(value: unknown): value is AiAgentRunState {
    return value === 'queued' || value === 'running' || value === 'paused'
        || value === 'waiting_approval' || value === 'done' || value === 'failed' || value === 'cancelled';
}

/** Rejects malformed or content-bearing mission frames before they reach session state. */
export function parseAskConnexMissionFrame(value: unknown): AiAgentRealtimeFrame | null {
    if (typeof value !== 'object' || value === null) return null;
    const keys = ['workspaceId', 'sessionId', 'runId', 'stateVersion', 'kind', 'coarseState'];
    if (Object.keys(value).length !== keys.length || Object.keys(value).some((key) => !keys.includes(key))) return null;
    if (!('workspaceId' in value) || !('sessionId' in value) || !('runId' in value)
        || !('stateVersion' in value) || !('kind' in value) || !('coarseState' in value)) return null;
    const { workspaceId, sessionId, runId, stateVersion, kind, coarseState } = value;
    if (typeof workspaceId !== 'number' || !Number.isSafeInteger(workspaceId) || workspaceId <= 0
        || typeof sessionId !== 'number' || !Number.isSafeInteger(sessionId) || sessionId <= 0
        || typeof runId !== 'number' || !Number.isSafeInteger(runId) || runId <= 0
        || typeof stateVersion !== 'string' || !/^[1-9]\d*$/.test(stateVersion)
        || (kind !== 'mission_created' && kind !== 'mission_state' && kind !== 'mission_output')
        || !isRunState(coarseState)) return null;
    return { workspaceId, sessionId, runId, stateVersion, kind, coarseState };
}

/** Revisions are compared losslessly, including values above JavaScript's safe integer range. */
export function newerAskConnexMission(current: AiAgentRun, incoming: AiAgentRun): AiAgentRun {
    if (incoming.id !== current.id) return current;
    if (!/^[1-9]\d*$/.test(incoming.stateVersion)) return current;
    if (incoming.stateVersion.length < current.stateVersion.length) return current;
    if (incoming.stateVersion.length === current.stateVersion.length && incoming.stateVersion < current.stateVersion) return current;
    return incoming;
}

/** Durable reload replaces deletions and optimistic rows; gaps never imply missing local content. */
export function reconcileAskConnexDurableMessages(
    previous: readonly AiChatMessage[],
    durable: AiChatMessage[],
    animate: boolean,
): { messages: AiChatMessage[]; freshMessageIds: ReadonlySet<number> } {
    const known = new Set(previous.filter((message) => message.id > 0)
        .map((message) => `${message.seq}:${message.authorKind}:${message.content}`));
    return {
        messages: durable,
        freshMessageIds: new Set(animate ? durable.filter((message) =>
            !known.has(`${message.seq}:${message.authorKind}:${message.content}`)).map((message) => message.id) : []),
    };
}
