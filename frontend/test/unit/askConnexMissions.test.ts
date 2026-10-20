import { describe, expect, it } from 'vitest';

import { askConnexMissionProblem, newerAskConnexMission, parseAskConnexMissionFrame, reconcileAskConnexDurableMessages } from '@/app/lib/askConnexMissions';
import type { AiAgentRun, AiChatMessage } from '@/app/lib/types';

export function mission(overrides: Partial<AiAgentRun> = {}): AiAgentRun {
    return {
        schemaVersion: 1, id: 12, sessionId: 7, missionType: 'record_review_v1', definitionVersion: 1,
        state: 'queued', reasonCode: null, stateVersion: '1', goal: 'Review the record',
        scope: { kind: 'record', recordKind: 'person', recordId: 4 },
        createdAt: '2026-10-15T10:00:00Z', executionDeadlineAt: '2026-10-15T10:15:00Z',
        hardDeadlineAt: '2026-10-15T10:15:00Z', startedAt: null, finishedAt: null,
        outputAvailable: false, contentWithheld: false, availableActions: ['pause', 'cancel'], progress: null,
        ...overrides,
    };
}

function message(id: number, seq: number, authorKind: string, content: string): AiChatMessage {
    return { id, sessionId: 7, seq, authorKind, content, authorUserId: authorKind === 'user' ? 1 : null,
        authorDisplayName: null, createdAt: '2026-10-15T10:00:00Z' };
}

describe('Ask Connex missions', () => {
    it('accepts only the closed metadata frame and keeps large versions exact', () => {
        const frame = { workspaceId: 1, sessionId: 7, runId: 12, stateVersion: '18446744073709551615',
            kind: 'mission_state', coarseState: 'paused' };
        expect(parseAskConnexMissionFrame(frame)).toEqual(frame);
        expect(parseAskConnexMissionFrame({ ...frame, goal: 'private content' })).toBeNull();
        expect(parseAskConnexMissionFrame({ ...frame, stateVersion: 4 })).toBeNull();
        expect(parseAskConnexMissionFrame({ ...frame, stateVersion: '01' })).toBeNull();
        expect(parseAskConnexMissionFrame({ ...frame, workspaceId: -1 })).toBeNull();
        expect(parseAskConnexMissionFrame({ ...frame, coarseState: 'unknown' })).toBeNull();
    });

    it('does not regress a durable run across versions above the safe integer range', () => {
        const current = mission({ stateVersion: '9007199254740993', state: 'paused' });
        expect(newerAskConnexMission(current, mission({ stateVersion: '9007199254740992' }))).toBe(current);
        expect(newerAskConnexMission(current, mission({ stateVersion: '10000000000000000' })).stateVersion)
            .toBe('10000000000000000');
        expect(newerAskConnexMission(current, mission({ id: 99, stateVersion: '10000000000000000' }))).toBe(current);
    });

    it('reconnectAfterPurgeUsesDurableMessageSequences', () => {
        const preserved = message(1, 1, 'user', 'Review this');
        const before = [preserved, message(2, 2, 'assistant', 'Repeated answer'), message(3, 3, 'user', 'Review this')];
        const after = [preserved, message(4, 4, 'user', 'Review this'), message(5, 5, 'assistant', 'Repeated answer')];
        const optimistic = message(-1, 4, 'user', 'Review this');
        const result = reconcileAskConnexDurableMessages([...before, optimistic], after, true);
        expect(result.messages).toEqual(after);
        expect(result.messages.map((item) => item.seq)).toEqual([1, 4, 5]);
        expect(result.messages.every((item) => item.id > 0)).toBe(true);
        expect([...result.freshMessageIds]).toEqual([4, 5]);
        expect(reconcileAskConnexDurableMessages(after, after, true).freshMessageIds.size).toBe(0);
        expect(reconcileAskConnexDurableMessages(after, [preserved, message(9, 9, 'assistant', 'Repeated answer')], true)
            .messages.map((item) => item.seq)).toEqual([1, 9]);
    });

    it('gives distinct localized refusal keys without exposing server text', () => {
        expect(askConnexMissionProblem(400, 'mission_budget_infeasible')).toBe('budgetInfeasible');
        expect(askConnexMissionProblem(409, 'mission_session_busy')).toBe('busy');
        expect(askConnexMissionProblem(409, 'mission_version_conflict')).toBe('conflict');
        expect(askConnexMissionProblem(410, 'mission_output_withheld')).toBe('withheld');
        expect(askConnexMissionProblem(403)).toBe('forbidden');
        expect(askConnexMissionProblem(503)).toBe('unavailable');
        expect(askConnexMissionProblem(500, 'untrusted server message')).toBe('failed');
    });
});
