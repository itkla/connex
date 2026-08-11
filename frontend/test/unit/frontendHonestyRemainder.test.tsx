import { isValidElement } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import AnalyticsPage from '@/app/(app)/overview/analytics/page';

const { recentMovesUnavailable } = vi.hoisted(() => ({
    recentMovesUnavailable: { current: true },
}));

vi.mock('next/headers', () => ({
    headers: () => Promise.resolve(new Headers({
        cookie: 'JSESSIONID=session; connex_workspace=7',
    })),
}));

vi.mock('next/navigation', () => ({
    redirect: vi.fn(),
}));

vi.mock('next-intl/server', () => ({
    getTranslations: () => Promise.resolve((key: string) => key),
}));

vi.mock('@/app/components/overview/analytics/AnalyticsBoard', () => ({
    default: function AnalyticsBoardStub() {
        return null;
    },
}));

vi.mock('@/app/lib/api', () => ({
    getAllStagesFromCookie: () => Promise.resolve([]),
    getCurrentUserResultFromCookie: () => Promise.resolve({
        ok: true,
        data: { id: 9, timezone: 'UTC' },
    }),
    getDealMetricsFromCookie: () => Promise.resolve({ byCurrency: [], totalCount: 0 }),
    getDealRiskAnalyticsFromCookie: () => Promise.resolve({ currencies: [], truncated: false }),
    getIntroSuggestionsFromCookie: () => Promise.resolve([]),
    getIntroductions: () => Promise.resolve({ items: [] }),
    getMyWorkspacesFromCookie: () => Promise.resolve({
        workspaces: [],
        activeWorkspaceId: null,
    }),
    getPipelinesFromCookie: () => Promise.resolve([]),
    getRecentMovesResultFromCookie: () => Promise.resolve(
        recentMovesUnavailable.current
            ? { ok: false }
            : { ok: true, data: [] },
    ),
    getTaskSummaryFromCookie: () => Promise.resolve({
        todo: 0,
        inProgress: 0,
        done: 0,
        overdue: 0,
        dueSoon: 0,
    }),
    getUsers: () => Promise.resolve([]),
    getWarmthSummaryFromCookie: () => Promise.resolve({
        contacts: { hot: 0, warm: 0, cool: 0, cold: 0 },
        companies: { hot: 0, warm: 0, cool: 0, cold: 0 },
        contactTrends: { rising: 0, steady: 0, cooling: 0 },
        contactDecay: { soon: 0, mid: 0, later: 0 },
    }),
}));

function hasRecentMovesAvailability(value: unknown): value is {
    recentMoves: unknown[];
    recentMovesAvailable: boolean;
} {
    return typeof value === 'object'
        && value !== null
        && 'recentMoves' in value
        && Array.isArray(value.recentMoves)
        && 'recentMovesAvailable' in value
        && typeof value.recentMovesAvailable === 'boolean';
}

afterEach(() => {
    recentMovesUnavailable.current = true;
});

describe('analytics recent-move honesty', () => {
    it('marks a failed read unavailable instead of presenting an ordinary empty list', async () => {
        const rendered = await AnalyticsPage();

        expect(isValidElement(rendered)).toBe(true);
        if (!isValidElement(rendered) || !hasRecentMovesAvailability(rendered.props)) {
            throw new Error('Analytics page did not render the expected availability contract');
        }
        expect(rendered.props.recentMoves).toEqual([]);
        expect(rendered.props.recentMovesAvailable).toBe(false);
    });

    it('keeps a successful empty response distinct from failure', async () => {
        recentMovesUnavailable.current = false;

        const rendered = await AnalyticsPage();

        expect(isValidElement(rendered)).toBe(true);
        if (!isValidElement(rendered) || !hasRecentMovesAvailability(rendered.props)) {
            throw new Error('Analytics page did not render the expected availability contract');
        }
        expect(rendered.props.recentMoves).toEqual([]);
        expect(rendered.props.recentMovesAvailable).toBe(true);
    });
});
