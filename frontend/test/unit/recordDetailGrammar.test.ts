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
        expect(recordDetailSectionId('deal', 'relationship')).toBe('deal-detail-relationship');
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

    it('company detail renders evidence after metrics and before related contacts', () => {
        const source = readFileSync(
            path.resolve(process.cwd(), 'app/(app)/records/companies/[id]/page.tsx'),
            'utf8',
        );

        const metrics = source.indexOf('section="metrics"');
        const activity = source.indexOf('section="activity"');
        const relationship = source.indexOf('section="relationship"');
        const related = source.indexOf('section="related"');
        const files = source.indexOf('section="files"');
        const history = source.indexOf('section="history"');
        const evidence = source.indexOf('<RelationshipEvidencePanel');
        const identityClose = source.indexOf('</RecordDetailSection>', source.indexOf('section="identity"'));

        expect(metrics).toBeGreaterThan(-1);
        expect(activity).toBeGreaterThan(metrics);
        expect(relationship).toBeGreaterThan(activity);
        expect(related).toBeGreaterThan(relationship);
        expect(files).toBeGreaterThan(related);
        expect(history).toBeGreaterThan(files);
        expect(evidence).toBeGreaterThan(metrics);
        expect(evidence).toBeGreaterThan(identityClose);
        expect(source.indexOf('<RelationshipEvidencePanel', evidence + 1)).toBe(-1);
    });

    it('deal detail places commercial context before risk, brief, and history', () => {
        const source = readFileSync(
            path.resolve(process.cwd(), 'app/(app)/records/deals/[id]/page.tsx'),
            'utf8',
        );

        const profile = source.indexOf('section="profile"');
        const metrics = source.indexOf('section="metrics"');
        const activity = source.indexOf('section="activity"');
        const relationship = source.indexOf('section="relationship"');
        const related = source.indexOf('section="related"');
        const files = source.indexOf('section="files"');
        const history = source.indexOf('section="history"');
        const risk = source.indexOf('<DealRiskPanel');
        const brief = source.indexOf('<DealBriefPanel');
        const identityClose = source.indexOf('</RecordDetailSection>', source.indexOf('section="identity"'));

        expect(profile).toBeGreaterThan(-1);
        expect(metrics).toBeGreaterThan(profile);
        expect(activity).toBeGreaterThan(metrics);
        expect(relationship).toBeGreaterThan(activity);
        expect(related).toBeGreaterThan(relationship);
        expect(files).toBeGreaterThan(related);
        expect(history).toBeGreaterThan(files);
        expect(risk).toBeGreaterThan(metrics);
        expect(risk).toBeGreaterThan(identityClose);
        expect(brief).toBeGreaterThan(metrics);
        expect(brief).toBeGreaterThan(risk);
        expect(source.indexOf('<DealRiskPanel', risk + 1)).toBe(-1);
        expect(source.indexOf('<DealBriefPanel', brief + 1)).toBe(-1);
        expect(source).toContain('tier="wide"');
    });

    it('deal detail loading skeleton matches the wide PageShell tier', () => {
        const source = readFileSync(
            path.resolve(process.cwd(), 'app/(app)/records/deals/[id]/loading.tsx'),
            'utf8',
        );
        expect(source).toContain('tier="wide"');
        expect(source).not.toContain('tier="reading"');
    });
});
