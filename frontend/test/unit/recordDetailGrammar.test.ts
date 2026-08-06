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
        expect(RECORD_DETAIL_SECTION_ORDER.indexOf('activity')).toBeGreaterThan(
            RECORD_DETAIL_SECTION_ORDER.indexOf('relationship'),
        );
    });

    it('builds stable section ids for adapters', () => {
        expect(recordDetailSectionId('contact', 'relationship')).toBe('contact-detail-relationship');
        expect(recordDetailSectionId('company', 'profile')).toBe('company-detail-profile');
        expect(recordDetailSectionId('deal', 'relationship')).toBe('deal-detail-relationship');
    });

    it('contact detail uses the wide left-rail canvas with connections after pipeline work', () => {
        const source = readFileSync(
            path.resolve(process.cwd(), 'app/(app)/records/contacts/[id]/page.tsx'),
            'utf8',
        );

        const profile = source.indexOf('section="profile"');
        const metrics = source.indexOf('section="metrics"');
        const relationship = source.indexOf('section="relationship"');
        const activity = source.indexOf('section="activity"');
        const related = source.indexOf('section="related"');
        const files = source.indexOf('section="files"');
        const history = source.indexOf('section="history"');
        const evidence = source.indexOf('<RelationshipEvidencePanel');
        const evaluation = source.indexOf('<EngineEvaluationPanel');
        const identityClose = source.indexOf('</RecordDetailSection>', source.indexOf('section="identity"'));

        expect(profile).toBeGreaterThan(-1);
        expect(metrics).toBeGreaterThan(profile);
        expect(relationship).toBeGreaterThan(metrics);
        expect(activity).toBeGreaterThan(relationship);
        expect(related).toBeGreaterThan(activity);
        expect(files).toBeGreaterThan(related);
        expect(history).toBeGreaterThan(files);
        expect(evidence).toBeGreaterThan(metrics);
        expect(evidence).toBeGreaterThan(identityClose);
        expect(evidence).toBeLessThan(activity);
        expect(evaluation).toBeGreaterThan(profile);
        expect(evaluation).toBeLessThan(metrics);
        expect(source).toContain('tier="wide"');
        expect(source).not.toContain('xl:sticky');
        expect(source.indexOf('<RelationshipEvidencePanel', evidence + 1)).toBe(-1);
        expect(source.indexOf('<EngineEvaluationPanel', evaluation + 1)).toBe(-1);
    });

    it('company detail uses the wide left-rail canvas and keeps the people grid after pipeline work', () => {
        const source = readFileSync(
            path.resolve(process.cwd(), 'app/(app)/records/companies/[id]/page.tsx'),
            'utf8',
        );

        const profile = source.indexOf('section="profile"');
        const metrics = source.indexOf('section="metrics"');
        const relationship = source.indexOf('section="relationship"');
        const activity = source.indexOf('section="activity"');
        const related = source.indexOf('section="related"');
        const files = source.indexOf('section="files"');
        const history = source.indexOf('section="history"');
        const evidence = source.indexOf('<RelationshipEvidencePanel');
        const sparkline = source.indexOf('<EngagementSparkline');
        const identityClose = source.indexOf('</RecordDetailSection>', source.indexOf('section="identity"'));

        expect(profile).toBeGreaterThan(-1);
        expect(metrics).toBeGreaterThan(profile);
        expect(relationship).toBeGreaterThan(metrics);
        expect(activity).toBeGreaterThan(relationship);
        expect(related).toBeGreaterThan(activity);
        expect(files).toBeGreaterThan(related);
        expect(history).toBeGreaterThan(files);
        expect(evidence).toBeGreaterThan(metrics);
        expect(evidence).toBeGreaterThan(identityClose);
        expect(evidence).toBeLessThan(activity);
        expect(sparkline).toBeGreaterThan(files);
        expect(source).toContain('tier="wide"');
        expect(source).not.toContain('xl:sticky');
        expect(source.indexOf('<RelationshipEvidencePanel', evidence + 1)).toBe(-1);
        expect(source.indexOf('<EngagementSparkline', sparkline + 1)).toBe(-1);
    });

    it('deal detail keeps stakeholders and insights in the left rail before files', () => {
        const source = readFileSync(
            path.resolve(process.cwd(), 'app/(app)/records/deals/[id]/page.tsx'),
            'utf8',
        );

        const profile = source.indexOf('section="profile"');
        const metrics = source.indexOf('section="metrics"');
        const relationship = source.indexOf('section="relationship"');
        const activity = source.indexOf('section="activity"');
        const related = source.indexOf('section="related"');
        const files = source.indexOf('section="files"');
        const history = source.indexOf('section="history"');
        const risk = source.indexOf('<DealRiskPanel');
        const people = source.indexOf("t('peopleOnThisDeal')");
        const brief = source.indexOf('<DealBriefPanel');
        const evaluation = source.indexOf('<EngineEvaluationPanel');
        const identityClose = source.indexOf('</RecordDetailSection>', source.indexOf('section="identity"'));

        expect(profile).toBeGreaterThan(-1);
        expect(related).toBeGreaterThan(profile);
        expect(related).toBeLessThan(metrics);
        expect(metrics).toBeGreaterThan(profile);
        expect(relationship).toBeGreaterThan(metrics);
        expect(activity).toBeGreaterThan(relationship);
        expect(files).toBeGreaterThan(activity);
        expect(history).toBeGreaterThan(files);
        expect(risk).toBeGreaterThan(profile);
        expect(risk).toBeGreaterThan(identityClose);
        expect(risk).toBeLessThan(related);
        expect(people).toBeGreaterThan(risk);
        expect(people).toBeLessThan(evaluation);
        expect(evaluation).toBeGreaterThan(related);
        expect(evaluation).toBeLessThan(metrics);
        expect(brief).toBeGreaterThan(metrics);
        expect(brief).toBeLessThan(activity);
        expect(source).not.toContain('xl:sticky');
        expect(source.indexOf('<DealRiskPanel', risk + 1)).toBe(-1);
        expect(source.indexOf('<DealBriefPanel', brief + 1)).toBe(-1);
        expect(source.indexOf('<EngineEvaluationPanel', evaluation + 1)).toBe(-1);
        expect(source).toContain('tier="wide"');
    });

    it('deal detail loading skeleton matches the wide PageShell tier and left-rail order', () => {
        const source = readFileSync(
            path.resolve(process.cwd(), 'app/(app)/records/deals/[id]/loading.tsx'),
            'utf8',
        );
        expect(source).toContain('tier="wide"');
        expect(source).not.toContain('tier="reading"');
        expect(source).not.toContain('xl:sticky');
        const peopleSkeleton = source.indexOf('size-12 shrink-0 rounded-full');
        const filesSkeleton = source.indexOf('xl:grid-cols-2');
        expect(peopleSkeleton).toBeGreaterThan(-1);
        expect(filesSkeleton).toBeGreaterThan(peopleSkeleton);
    });

    it('contact and company loading skeletons match the wide left-rail canvas', () => {
        for (const relativePath of [
            'app/(app)/records/contacts/[id]/loading.tsx',
            'app/(app)/records/companies/[id]/loading.tsx',
        ]) {
            const source = readFileSync(path.resolve(process.cwd(), relativePath), 'utf8');
            expect(source).toContain('tier="wide"');
            expect(source).not.toContain('tier="reading"');
            expect(source).toContain('xl:grid-cols-[minmax(16rem,20rem)_minmax(0,1fr)]');
            expect(source).not.toContain('xl:sticky');
        }
    });
});
