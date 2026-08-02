import { existsSync } from 'node:fs';
import path from 'node:path';
import { describe, expect, it } from 'vitest';

import ReportSnapshotsPage from '@/app/(app)/overview/reports/[id]/snapshots/page';

const SEGMENT = 'app/(app)/overview/reports/[id]/snapshots';

function digestOf(error: unknown): string {
    if (typeof error === 'object' && error !== null && 'digest' in error) {
        const { digest } = error;
        if (typeof digest === 'string') return digest;
    }
    throw new Error('expected a Next.js navigation error carrying a digest');
}

async function outcomeOf(id: string): Promise<string> {
    try {
        await ReportSnapshotsPage({ params: Promise.resolve({ id }) });
    } catch (error) {
        return digestOf(error);
    }
    throw new Error('expected the page to navigate rather than render');
}

/**
 * The scheduled-delivery email deep-links `/overview/reports/{id}/snapshots/{snapshotId}`. Before
 * this route existed, truncating that link to its parent hit a hard 404 rather than the report.
 */
describe('the bare report snapshots path resolves instead of 404ing', () => {
    it('is a real route segment, not a directory holding only its dynamic child', () => {
        expect(existsSync(path.resolve(process.cwd(), SEGMENT, 'page.tsx'))).toBe(true);
        expect(existsSync(path.resolve(process.cwd(), SEGMENT, '[snapshotId]', 'page.tsx'))).toBe(
            true,
        );
    });

    it('sends a truncated snapshot link to the report that owns the snapshots', async () => {
        const digest = await outcomeOf('42');

        expect(digest).toContain('NEXT_REDIRECT');
        expect(digest).toContain('/overview/reports/42');
        expect(digest).not.toContain('/snapshots');
    });

    it('still refuses an id that is not a report', async () => {
        for (const id of ['not-a-number', '0', '-3']) {
            expect(await outcomeOf(id)).not.toContain('NEXT_REDIRECT');
        }
    });
});
