import { mkdirSync, rmSync } from 'node:fs';
import { expect, request, test as setup } from '@playwright/test';

import { MATRIX_ARTIFACT_DIR, MATRIX_BASE_URL, MATRIX_FIXTURE_PATH, storageStateFor } from '../../../playwright.matrix.config';
import { writeRunInfo, type MatrixFixture } from './support/matrix';
import { writeFileSync } from 'node:fs';

/**
 * Usernames the deterministic seeder produced for workspace 1 under
 * `-PseederProfile=small -PseederSeed=853 -PseederWorkspaces=2`. The seeder derives the prefix from
 * the seed, so these are stable for that invocation and are overridable for a differently-seeded run.
 */
const SEEDED_USERS = {
    owner: process.env.MATRIX_OWNER ?? 'seed-60p03thwdmb4-w1-u1',
    admin: process.env.MATRIX_ADMIN ?? 'seed-60p03thwdmb4-w1-u2',
    member: process.env.MATRIX_MEMBER ?? 'seed-60p03thwdmb4-w1-u3',
} as const;

/** The seeder persists a precomputed BCrypt hash of this constant for every seeded user. */
const SEEDED_PASSWORD = process.env.MATRIX_PASSWORD ?? 'seeder-password';

/**
 * Signs in each seeded role and persists its storage state.
 *
 * Roles are provisioned by the seeder rather than by invite redemption, which matters for how the
 * evidence should be read: every membership is already ACTIVE, so pending-membership and
 * invite-redemption states are explicitly out of this matrix's scope. Likewise the backend runs with
 * `CONNEX_RECENT_AUTHENTICATION_WINDOW=0s` so password sessions can drive flows that normally demand
 * WebAuthn step-up — meaning the step-up gate itself is NOT exercised here.
 */
setup('sign in each seeded role', async () => {
    setup.setTimeout(120_000);
    if (process.env.MATRIX_RESET === '1') {
        rmSync(MATRIX_ARTIFACT_DIR, { recursive: true, force: true });
    }
    mkdirSync(MATRIX_ARTIFACT_DIR, { recursive: true });

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
        const token = (await csrf.json()).token as string;

        const login = await api.post('/api/auth/login', {
            headers: { 'X-CSRF-TOKEN': token, 'Content-Type': 'application/json' },
            data: { username, password: SEEDED_PASSWORD },
        });
        expect(login.status(), `login for ${role} (${username})`).toBe(200);

        const workspaces = await api.get('/api/workspaces');
        expect(workspaces.status(), `workspace list for ${role}`).toBe(200);
        const body = await workspaces.json();
        workspaceId = Number(body.activeWorkspaceId ?? body.workspaces?.[0]?.id ?? 0);
        expect(workspaceId, `active workspace for ${role}`).toBeGreaterThan(0);

        await api.storageState({ path: storageStateFor(role) });
        await api.dispose();
    }

    const fixture: MatrixFixture = { workspaceId, users: SEEDED_USERS, password: SEEDED_PASSWORD };
    writeFileSync(MATRIX_FIXTURE_PATH, JSON.stringify(fixture, null, 2));

    writeRunInfo({
        capturedAt: new Date().toISOString(),
        baseUrl: MATRIX_BASE_URL,
        commit: process.env.MATRIX_COMMIT ?? null,
        seed: { profile: 'small', seed: 853, workspaces: 2, anchorDate: '2026-08-01' },
        scopeCaveats: [
            'CONNEX_RECENT_AUTHENTICATION_WINDOW=0s — the WebAuthn step-up gate is NOT exercised.',
            'All seeded memberships are ACTIVE — pending-membership and invite-redemption states are untested.',
            'Evidence is after-only; the Wave 4 grammar PRs (#943/#954) carry their own before/after.',
            'OCR sidecar is not running — business-card extraction is covered via the AI path, not OCR.',
        ],
    });
});
