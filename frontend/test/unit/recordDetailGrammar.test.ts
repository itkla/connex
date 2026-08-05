import { readFileSync } from 'node:fs';
import path from 'node:path';
import { describe, expect, it } from 'vitest';

import {
    RECORD_DETAIL_SECTION_ORDER,
    recordDetailSectionId,
} from '@/app/components/records/recordDetailGrammar';

describe('record detail grammar', () => {
    it('keeps relationship evidence after profile and metrics', () => {
        expect(RECORD_DETAIL_SECTION_ORDER.indexOf('relationship')).toBeGreaterThan(
            RECORD_DETAIL_SECTION_ORDER.indexOf('profile'),
        );
        expect(RECORD_DETAIL_SECTION_ORDER.indexOf('relationship')).toBeGreaterThan(
            RECORD_DETAIL_SECTION_ORDER.indexOf('metrics'),
        );
        expect(RECORD_DETAIL_SECTION_ORDER.indexOf('relationship')).toBeGreaterThan(
            RECORD_DETAIL_SECTION_ORDER.indexOf('activity'),
        );
    });

    it('builds stable section ids for adapters', () => {
        expect(recordDetailSectionId('contact', 'relationship')).toBe('contact-detail-relationship');
        expect(recordDetailSectionId('company', 'profile')).toBe('company-detail-profile');
    });

    it('contact detail renders evidence after metrics and before related records', () => {
        const source = readFileSync(
            path.resolve(process.cwd(), 'app/(app)/records/contacts/[id]/page.tsx'),
            'utf8',
        );

        const metrics = source.indexOf('section="metrics"');
        const relationship = source.indexOf('section="relationship"');
        const related = source.indexOf('section="related"');
        const evidence = source.indexOf('<RelationshipEvidencePanel');
        const identityClose = source.indexOf('</RecordDetailSection>', source.indexOf('section="identity"'));

        expect(metrics).toBeGreaterThan(-1);
        expect(relationship).toBeGreaterThan(metrics);
        expect(related).toBeGreaterThan(relationship);
        expect(evidence).toBeGreaterThan(metrics);
        expect(evidence).toBeGreaterThan(identityClose);
        expect(source.indexOf('<RelationshipEvidencePanel', evidence + 1)).toBe(-1);
    });
});
