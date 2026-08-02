/**
 * Setup project for the Wave 4 (#856) route/state matrix.
 *
 * Signs in each seeded role, persists its storage state, and writes the run provenance the report
 * cites. Two details matter for how the resulting evidence should be read.
 *
 * Roles are provisioned by the seeder rather than by invite redemption, so every membership is
 * already ACTIVE and pending-membership and invite-redemption states are explicitly out of scope.
 * Likewise the backend runs with `CONNEX_RECENT_AUTHENTICATION_WINDOW=0s` so password sessions can
 * drive flows that normally demand WebAuthn step-up — the step-up gate itself is NOT exercised here.
 *
 * Provenance is only recorded as fact when it is known. The default usernames encode seed 853; if a
 * run overrides them without declaring the seeder parameters that produced them, the dataset is
 * recorded as unknown rather than attributed to a seed it may not have come from.
 */

import { mkdirSync, rmSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { expect, request, test as setup } from '@playwright/test';

import {
    MATRIX_ARTIFACT_DIR,
    MATRIX_BASE_URL,
    MATRIX_FIXTURE_PATH,
    storageStateFor,
} from '../../../playwright.matrix.config';
import { writeRunInfo, type MatrixFixture } from './support/matrix';

const USER_OVERRIDE_KEYS = ['MATRIX_OWNER', 'MATRIX_ADMIN', 'MATRIX_MEMBER'] as const;
const SEED_OVERRIDE_KEYS = [
    'MATRIX_SEED_PROFILE',
    'MATRIX_SEED',
    'MATRIX_SEED_WORKSPACES',
    'MATRIX_SEED_ANCHOR_DATE',
] as const;

const SEEDED_USERS = {
    owner: process.env.MATRIX_OWNER ?? 'seed-60p03thwdmb4-w1-u1',
    admin: process.env.MATRIX_ADMIN ?? 'seed-60p03thwdmb4-w1-u2',
    member: process.env.MATRIX_MEMBER ?? 'seed-60p03thwdmb4-w1-u3',
} as const;

const SEEDED_PASSWORD = process.env.MATRIX_PASSWORD ?? 'seeder-password';

function isRecord(value: unknown): value is Record<string, unknown> {
    return typeof value === 'object' && value !== null;
}

function readCsrfToken(payload: unknown, role: string): string {
    if (!isRecord(payload) || typeof payload.token !== 'string' || payload.token.length === 0) {
        const shape = isRecord(payload) ? Object.keys(payload).join(', ') : typeof payload;
        throw new Error(
            `CSRF bootstrap for ${role} did not return a non-empty string token (payload shape: ${shape}). `
            + 'The auth contract changed, or the backend answered something other than the CSRF payload.',
        );
    }
    return payload.token;
}

function readWorkspaceId(payload: unknown): number {
    if (!isRecord(payload)) return 0;
    if (typeof payload.activeWorkspaceId === 'number') return payload.activeWorkspaceId;
    const workspaces: unknown = payload.workspaces;
    if (Array.isArray(workspaces) && workspaces.length > 0) {
        const first: unknown = workspaces[0];
        if (isRecord(first) && typeof first.id === 'number') return first.id;
    }
    return 0;
}

function seedProvenance(): Record<string, unknown> | null {
    const usersOverridden = USER_OVERRIDE_KEYS.some((key) => process.env[key] !== undefined);
    const seedDeclared = SEED_OVERRIDE_KEYS.some((key) => process.env[key] !== undefined);
    if (usersOverridden && !seedDeclared) return null;
    return {
        profile: process.env.MATRIX_SEED_PROFILE ?? 'small',
        seed: Number(process.env.MATRIX_SEED ?? 853),
        workspaces: Number(process.env.MATRIX_SEED_WORKSPACES ?? 2),
        anchorDate: process.env.MATRIX_SEED_ANCHOR_DATE ?? '2026-08-01',
    };
}

setup('sign in each seeded role', async () => {
    setup.setTimeout(120_000);
    if (process.env.MATRIX_RESET === '1') {
        rmSync(MATRIX_ARTIFACT_DIR, { recursive: true, force: true });
    }
    mkdirSync(MATRIX_ARTIFACT_DIR, { recursive: true });
    rmSync(path.join(MATRIX_ARTIFACT_DIR, 'manifest.jsonl'), { force: true });

    const probe = await request.newContext({ baseURL: MATRIX_BASE_URL });
    const reachable = await probe.get('/auth/login').catch(() => null);
    if (!reachable || !reachable.ok()) {
        throw new Error(
            `Frontend is not reachable at ${MATRIX_BASE_URL}. Start the matrix stack: MySQL on :3307, `
            + 'backend on :8081, fault proxy on :8190, and a production frontend build on :3000.',
        );
    }
    await probe.dispose();

    let workspaceId = 0;
    for (const [role, username] of Object.entries(SEEDED_USERS) as [keyof typeof SEEDED_USERS, string][]) {
        const api = await request.newContext({ baseURL: MATRIX_BASE_URL });
        const csrf = await api.get('/api/auth/csrf');
        expect(csrf.status(), `csrf bootstrap for ${role}`).toBe(200);
        const csrfPayload: unknown = await csrf.json();
        const token = readCsrfToken(csrfPayload, role);

        const login = await api.post('/api/auth/login', {
            headers: { 'X-CSRF-TOKEN': token, 'Content-Type': 'application/json' },
            data: { username, password: SEEDED_PASSWORD },
        });
        expect(login.status(), `login for ${role} (${username})`).toBe(200);

        const workspaces = await api.get('/api/workspaces');
        expect(workspaces.status(), `workspace list for ${role}`).toBe(200);
        const workspacePayload: unknown = await workspaces.json();
        workspaceId = readWorkspaceId(workspacePayload);
        expect(workspaceId, `active workspace for ${role}`).toBeGreaterThan(0);

        await api.storageState({ path: storageStateFor(role) });
        await api.dispose();
    }

    const fixture: MatrixFixture = { workspaceId, users: SEEDED_USERS };
    writeFileSync(MATRIX_FIXTURE_PATH, JSON.stringify(fixture, null, 2));

    const seed = seedProvenance();
    const scopeCaveats = [
        'CONNEX_RECENT_AUTHENTICATION_WINDOW=0s — the WebAuthn step-up gate is NOT exercised.',
        'All seeded memberships are ACTIVE — pending-membership and invite-redemption states are untested.',
        'Evidence is after-only; the Wave 4 grammar PRs (#943/#954) carry their own before/after.',
        'OCR sidecar is not running — business-card extraction is covered via the AI path, not OCR.',
    ];
    if (seed === null) {
        scopeCaveats.push(
            'Seeded usernames were overridden without declaring the seeder parameters — the dataset '
            + 'behind this run is unknown and is NOT attributable to seed 853.',
        );
    }
    if (process.env.MATRIX_EMPTY_WORKSPACE === undefined) {
        scopeCaveats.push(
            'MATRIX_EMPTY_WORKSPACE was not set — the first-run empty-workspace state is NOT covered; '
            + 'only the filtered no-results state is.',
        );
    }

    writeRunInfo({
        capturedAt: new Date().toISOString(),
        baseUrl: MATRIX_BASE_URL,
        commit: process.env.MATRIX_COMMIT ?? null,
        seed,
        users: SEEDED_USERS,
        scopeCaveats,
    });
});
