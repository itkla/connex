import { readFileSync } from 'node:fs';
import path from 'node:path';
import { describe, expect, it } from 'vitest';

import { ApiError } from '@/app/lib/api';
import { loadCollection } from '@/app/lib/recordAccess';

const AUDIT_PAGE = 'app/(app)/admin/logs/page.tsx';
const AUDIT_BROWSER = 'app/components/admin/AuditLogBrowser.tsx';

function source(relativePath: string): string {
    return readFileSync(path.resolve(process.cwd(), relativePath), 'utf8');
}

function isMessageCatalog(value: unknown): value is Record<string, Record<string, string>> {
    return (
        typeof value === 'object' &&
        value !== null &&
        !Array.isArray(value) &&
        Object.values(value).every(
            (namespace) =>
                typeof namespace === 'object' &&
                namespace !== null &&
                !Array.isArray(namespace) &&
                Object.values(namespace).every((message) => typeof message === 'string'),
        )
    );
}

function messages(locale: 'en' | 'ja'): Record<string, Record<string, string>> {
    const parsed: unknown = JSON.parse(source(`messages/${locale}/admin.json`));
    if (!isMessageCatalog(parsed)) {
        throw new Error(`messages/${locale}/admin.json is not a message catalog`);
    }
    return parsed;
}

describe('loadCollection', () => {
    it('reports a refused collection as forbidden rather than as empty', async () => {
        const access = await loadCollection(async () => {
            throw new ApiError('Requires the AUDIT_READ permission in this workspace', 403);
        });

        expect(access).toEqual({ kind: 'forbidden' });
    });

    it('still reports a genuinely empty collection as loaded', async () => {
        await expect(loadCollection(async () => [])).resolves.toEqual({ kind: 'loaded', items: [] });
    });

    it('redirects an unauthenticated caller instead of denying them', async () => {
        await expect(
            loadCollection(async () => {
                throw new ApiError('', 403, undefined, undefined, undefined, true);
            }),
        ).rejects.toThrow(/NEXT_REDIRECT/);
    });

    it('rethrows a server fault instead of presenting it as an empty workspace', async () => {
        await expect(
            loadCollection(async () => {
                throw new ApiError('Request failed (500)', 500);
            }),
        ).rejects.toThrow('Request failed (500)');
    });
});

describe('/admin/logs never manufactures an empty audit log', () => {
    it('does not swallow a refused audit fetch into an empty list', () => {
        const page = source(AUDIT_PAGE);

        expect(page).not.toMatch(/\.catch\s*\(/);
        expect(page).toContain('loadCollection(');
    });

    it('returns the denial state for a member who lacks audit access', () => {
        const page = source(AUDIT_PAGE);

        expect(page).toMatch(/access\.kind === "forbidden"/);
        expect(page).toContain('<AccessDeniedPage');
        expect(page.indexOf('forbidden')).toBeLessThan(page.indexOf('<AuditLogBrowser'));
    });

    it('keeps the empty-log copy behind the browser that only a loaded fetch reaches', () => {
        expect(source(AUDIT_BROWSER)).toContain('emptyAllTitle');
        expect(source(AUDIT_PAGE)).not.toContain('emptyAll');
        expect(source(AUDIT_PAGE)).toContain('initialEntries={access.items}');
    });

    it('localizes the denial copy in both supported locales', () => {
        for (const locale of ['en', 'ja'] as const) {
            const namespace = messages(locale).AdminAuditLog;

            expect(namespace.deniedTitle).toBeTruthy();
            expect(namespace.deniedBody).toBeTruthy();
            expect(namespace.deniedBody).not.toBe(namespace.emptyAllBody);
        }
    });
});
