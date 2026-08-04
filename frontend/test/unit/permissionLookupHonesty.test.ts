import { readFileSync } from 'node:fs';
import path from 'node:path';
import { Children, Fragment, isValidElement, type ReactNode } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import AccessDeniedPage from '@/app/components/AccessDeniedPage';
import PermissionsUnavailablePage from '@/app/components/PermissionsUnavailablePage';
import ReportDocumentBoard from '@/app/components/reports/ReportDocumentBoard';
import ReportSnapshotPage from '@/app/(app)/overview/reports/[id]/snapshots/[snapshotId]/page';

vi.mock('next/headers', () => ({
    headers: () => Promise.resolve(new Headers({ cookie: 'JSESSIONID=session; connex_workspace=1' })),
}));

vi.mock('next-intl/server', () => ({
    getTranslations: () => Promise.resolve((key: string) => key),
    getLocale: () => Promise.resolve('en'),
}));

const CONNECTIONS_PAGE = 'app/(app)/account/connections/page.tsx';
const CONNECTIONS_PANEL = 'app/components/account/ConnectionsPanel.tsx';
const SNAPSHOT_PAGE = 'app/(app)/overview/reports/[id]/snapshots/[snapshotId]/page.tsx';

function source(relativePath: string): string {
    return readFileSync(path.resolve(process.cwd(), relativePath), 'utf8');
}

type SnapshotWorld = {
    permissions: string[] | 'unreachable';
    attainment?: boolean;
    snapshot?: 'ready' | 'gone' | 'forbidden';
};

function hasChildren(value: unknown): value is { children?: ReactNode } {
    return typeof value === 'object' && value !== null && 'children' in value;
}

function json(body: unknown): Response {
    return new Response(JSON.stringify(body), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
    });
}

function stubOnlyThisRoutesReads(world: SnapshotWorld): void {
    const measure = world.attainment ? 'attainment' : 'deal_value';
    const snapshotState = world.snapshot ?? 'ready';
    vi.stubGlobal('fetch', (input: string) => {
        const url = String(input);
        if (url.endsWith('/api/auth/me')) {
            return Promise.resolve(json({ id: 7, timezone: 'Asia/Tokyo' }));
        }
        if (url.endsWith('/api/permissions/effective')) {
            return world.permissions === 'unreachable'
                ? Promise.resolve(new Response('', { status: 503 }))
                : Promise.resolve(json(world.permissions));
        }
        if (url.endsWith('/api/reports/1/snapshots')) {
            return Promise.resolve(json([]));
        }
        if (url.endsWith('/api/reports/1/snapshots/2')) {
            if (snapshotState === 'forbidden') {
                return Promise.resolve(new Response('', { status: 403 }));
            }
            if (snapshotState === 'gone') {
                return Promise.resolve(new Response('', { status: 404 }));
            }
            return Promise.resolve(json({
                id: 2,
                generatedAt: '2026-01-01T00:00:00Z',
                computedResult: { widgets: [{ measure }] },
            }));
        }
        if (url.endsWith('/api/reports/1')) {
            return Promise.resolve(json({ id: 1, name: 'Pipeline health', config: { widgets: [{ measure }] } }));
        }
        return Promise.resolve(new Response('', { status: 404 }));
    });
}

async function renderSnapshotPage(world: SnapshotWorld): Promise<unknown> {
    stubOnlyThisRoutesReads(world);
    const rendered = await ReportSnapshotPage({
        params: Promise.resolve({ id: '1', snapshotId: '2' }),
    });
    if (!isValidElement(rendered)) return null;
    if (rendered.type !== Fragment || !hasChildren(rendered.props)) return rendered.type;
    const content = Children.toArray(rendered.props.children).at(-1);
    return isValidElement(content) ? content.type : null;
}

afterEach(() => {
    vi.unstubAllGlobals();
});

describe('an attainment snapshot separates a refusal from a failed check', () => {
    it('renders the board for a viewer who holds GOAL_READ', async () => {
        expect(await renderSnapshotPage({ permissions: ['GOAL_READ'], attainment: true }))
            .toBe(ReportDocumentBoard);
    });

    it('refuses a viewer who genuinely lacks GOAL_READ, in the page rather than by navigating away', async () => {
        expect(await renderSnapshotPage({ permissions: ['REPORT_READ'], attainment: true }))
            .toBe(AccessDeniedPage);
    });

    it('does not refuse an entitled reader when the permission lookup itself failed', async () => {
        expect(await renderSnapshotPage({ permissions: 'unreachable', attainment: true }))
            .toBe(PermissionsUnavailablePage);
    });

    it('says the check failed even when the report carries no attainment at all', async () => {
        expect(await renderSnapshotPage({ permissions: 'unreachable' }))
            .toBe(PermissionsUnavailablePage);
    });

    it('still renders a report without attainment to a viewer holding nothing', async () => {
        expect(await renderSnapshotPage({ permissions: [] })).toBe(ReportDocumentBoard);
    });

    it('keeps the pruned-snapshot recovery ahead of the permission answer, so an emailed link still recovers', async () => {
        const pruned = await renderSnapshotPage({ permissions: 'unreachable', snapshot: 'gone' });

        expect(pruned).not.toBe(PermissionsUnavailablePage);
        expect(pruned).toBe('div');
    });

    it('leaves a forbidden snapshot answered by the snapshot itself', async () => {
        expect(await renderSnapshotPage({ permissions: 'unreachable', snapshot: 'forbidden' }))
            .toBe('div');
    });

    it('reads the lookup outcome rather than a list that swallows failure', () => {
        const page = source(SNAPSHOT_PAGE);

        expect(page).toContain('getEffectivePermissionsResultFromCookie(cookie)');
        expect(page).not.toContain('getEffectivePermissionsFromCookie');
        expect(page).not.toMatch(/redirect\('\/overview\/reports'\)/);
    });
});

describe('a connected-capture deep link survives a failed permission lookup', () => {
    const page = source(CONNECTIONS_PAGE);
    const panel = source(CONNECTIONS_PANEL);

    it('strips the route state only when the check actually returned a refusal', () => {
        expect(page).toMatch(
            /const workspacePolicyForbidden = routeState\.panel === "workspace-policy"\s*&& checkPermission\([\s\S]*?\)\s*=== "denied"/,
        );
    });

    it('still resolves an unreadable lookup to no permissions, so nothing is widened', () => {
        expect(page).toContain('const effectivePermissions = permissionsResult.ok ? permissionsResult.data : [];');
        expect(page).toContain('permissionsResult.ok ? "resolved" : "unavailable"');
    });

    it('carries the outcome into the panel that has to explain it', () => {
        expect(page).toContain('permissionsStatus={permissionsStatus}');
        expect(panel).toContain('permissionsStatus: PermissionsStatus;');
    });

    it('keeps the policy affordance gated on a granted answer, never on a missing one', () => {
        expect(panel).toContain('const canManageWorkspacePolicy = workspacePolicyCheck === "granted";');
        expect(panel).not.toContain('effectivePermissions.includes("WORKSPACE_SETTINGS")');
    });

    it('reports the failed lookup where the deep link landed, with a way out', () => {
        expect(panel).toMatch(
            /routeState\.panel === "workspace-policy" && workspacePolicyCheck === "unavailable"/,
        );
        expect(panel).toContain('<WorkspacePolicyUnavailable />');
        expect(panel).toContain('startTransition(() => router.refresh())');
    });

    it('reuses the shared state rather than hand-rolling a card', () => {
        expect(panel).toContain('from "@/app/components/PermissionsUnavailable"');
        expect(panel).toContain('variant="inline"');
    });

    it('ships the copy it renders in both locales, actually translated', () => {
        for (const key of ['title', 'sectionBody', 'retry', 'retrying']) {
            const en = JSON.parse(source('messages/en/errors.json')).PermissionsUnavailable[key];
            const ja = JSON.parse(source('messages/ja/errors.json')).PermissionsUnavailable[key];

            expect(typeof en).toBe('string');
            expect(typeof ja).toBe('string');
            expect(ja).not.toBe(en);
        }
    });
});
