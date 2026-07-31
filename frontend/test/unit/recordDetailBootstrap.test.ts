import { readFileSync } from 'node:fs';
import path from 'node:path';
import { describe, expect, it } from 'vitest';

const DETAIL_PAGES = [
    'app/(app)/records/contacts/[id]/page.tsx',
    'app/(app)/records/companies/[id]/page.tsx',
    'app/(app)/records/deals/[id]/page.tsx',
];

describe('record detail bootstrap contracts', () => {
    it.each(DETAIL_PAGES)('%s avoids workspace-wide and per-user hydration', (relativePath) => {
        const source = readFileSync(path.resolve(process.cwd(), relativePath), 'utf8');

        expect(source).not.toMatch(/\bgetUsers\s*\(/);
        expect(source).not.toMatch(/\bgetUserById\s*\(/);
        expect(source).not.toMatch(/\bgetContacts\s*\(\s*\{\s*}\s*[,)]/);
        expect(source).not.toMatch(/\bgetDeals\s*\(\s*\)/);
        expect(source).not.toMatch(/\bgetPipelines\s*\(/);
    });

    it('deal detail performs only targeted pipeline and stakeholder hydration', () => {
        const source = readFileSync(
            path.resolve(process.cwd(), 'app/(app)/records/deals/[id]/page.tsx'),
            'utf8',
        );

        expect(source).toContain('getPipelineById(deal.pipeline');
        expect(source).toContain('getStagesByPipelineId(deal.pipeline');
        expect(source).toContain('getContacts({ dealId: id }');
        expect(source).toContain('getUserReferences(missingUserIds');
    });
});
