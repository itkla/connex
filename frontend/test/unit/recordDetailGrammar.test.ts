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

    it('deal detail keeps stakeholders and insights in the sticky left rail before files', () => {
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
        const stickyRail = source.indexOf('xl:sticky xl:top-16');
        const stickyOverflowInner = source.indexOf('xl:max-h-[calc(100dvh-5rem)] xl:overflow-y-auto');
        const stickyGrid = source.lastIndexOf('grid grid-cols-1 gap-8 xl:grid-cols-[minmax(16rem,20rem)_minmax(0,1fr)]', stickyRail);
        const riseAroundSticky = source.lastIndexOf('<Rise', stickyRail);
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
        expect(stickyRail).toBeGreaterThan(-1);
        expect(stickyRail).toBeLessThan(files);
        expect(stickyOverflowInner).toBeGreaterThan(stickyRail);
        expect(stickyGrid).toBeGreaterThan(-1);
        expect(riseAroundSticky).toBeLessThan(stickyGrid);
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
        expect(source).toContain('xl:sticky xl:top-16');
        const peopleSkeleton = source.indexOf('size-12 shrink-0 rounded-full');
        const filesSkeleton = source.indexOf('xl:grid-cols-2');
        expect(peopleSkeleton).toBeGreaterThan(-1);
        expect(filesSkeleton).toBeGreaterThan(peopleSkeleton);
    });
});
