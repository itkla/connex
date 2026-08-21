import { readFileSync } from 'node:fs';
import path from 'node:path';
import ts from 'typescript';
import { describe, expect, it } from 'vitest';

import {
    RECORD_DETAIL_SECTION_ORDER,
    recordDetailSectionId,
} from '@/app/components/records/recordDetailGrammar';

function tagName(element: ts.JsxOpeningLikeElement): string | null {
    return ts.isIdentifier(element.tagName) ? element.tagName.text : null;
}

function stringAttribute(
    element: ts.JsxOpeningLikeElement,
    name: string,
): string | null {
    const attribute = element.attributes.properties.find(
        (property) => ts.isJsxAttribute(property) && property.name.getText() === name,
    );
    if (!attribute || !ts.isJsxAttribute(attribute) || !attribute.initializer) return null;
    return ts.isStringLiteral(attribute.initializer) ? attribute.initializer.text : null;
}

function hasBooleanAttribute(element: ts.JsxOpeningLikeElement, name: string): boolean {
    return element.attributes.properties.some(
        (property) => ts.isJsxAttribute(property)
            && property.name.getText() === name
            && property.initializer === undefined,
    );
}

function containsLocalizedNew(node: ts.Node): boolean {
    let found = false;
    const visit = (candidate: ts.Node) => {
        if (ts.isCallExpression(candidate)
                && ts.isIdentifier(candidate.expression)
                && candidate.expression.text === 't'
                && candidate.arguments.length === 1
                && ts.isStringLiteral(candidate.arguments[0])
                && candidate.arguments[0].text === 'new') {
            found = true;
            return;
        }
        candidate.forEachChild(visit);
    };
    node.forEachChild(visit);
    return found;
}

type PrimaryCreateMenu = {
    insideMenuTrigger: boolean;
    toolbar: boolean;
    menu: boolean;
    localizedNew: boolean;
};

function primaryCreateMenus(relativePath: string): PrimaryCreateMenu[] {
    const source = readFileSync(path.resolve(process.cwd(), relativePath), 'utf8');
    const sourceFile = ts.createSourceFile(
        relativePath,
        source,
        ts.ScriptTarget.Latest,
        true,
        ts.ScriptKind.TSX,
    );
    const menus: PrimaryCreateMenu[] = [];

    const visit = (node: ts.Node, insideMenuTrigger: boolean) => {
        if (ts.isJsxElement(node)) {
            const element = node.openingElement;
            const inside = insideMenuTrigger || tagName(element) === 'DropdownMenuTrigger';
            if (tagName(element) === 'Button' && stringAttribute(element, 'variant') === 'brand') {
                menus.push({
                    insideMenuTrigger: inside,
                    toolbar: stringAttribute(element, 'size') === 'toolbar',
                    menu: hasBooleanAttribute(element, 'menu'),
                    localizedNew: containsLocalizedNew(node),
                });
            }
            node.forEachChild((child) => visit(child, inside));
            return;
        }
        if (ts.isJsxSelfClosingElement(node)
                && tagName(node) === 'Button'
                && stringAttribute(node, 'variant') === 'brand') {
            menus.push({
                insideMenuTrigger,
                toolbar: stringAttribute(node, 'size') === 'toolbar',
                menu: hasBooleanAttribute(node, 'menu'),
                localizedNew: false,
            });
            return;
        }
        node.forEachChild((child) => visit(child, insideMenuTrigger));
    };
    visit(sourceFile, false);
    return menus;
}

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

    it('gives each record detail action region one primary create menu', () => {
        for (const relativePath of [
            'app/components/records/contacts/ContactActionsMenu.tsx',
            'app/components/records/companies/CompanyActionsMenu.tsx',
            'app/components/records/deals/DealActionsMenu.tsx',
        ]) {
            expect(primaryCreateMenus(relativePath), relativePath).toEqual([{
                insideMenuTrigger: true,
                toolbar: true,
                menu: true,
                localizedNew: true,
            }]);
        }
    });

    it('contact detail uses the wide left-rail canvas with evidence in the identity chip dialog', () => {
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
        const chip = source.indexOf('<WarmthEvidenceChip');
        const signals = source.indexOf('<RecordSignalsPanel');
        const evaluation = source.indexOf('<EngineEvaluationPanel');
        const identityClose = source.indexOf('</RecordDetailSection>', source.indexOf('section="identity"'));

        expect(profile).toBeGreaterThan(-1);
        expect(metrics).toBeGreaterThan(profile);
        expect(relationship).toBeGreaterThan(metrics);
        expect(activity).toBeGreaterThan(relationship);
        expect(related).toBeGreaterThan(activity);
        expect(files).toBeGreaterThan(related);
        expect(history).toBeGreaterThan(files);
        expect(chip).toBeGreaterThan(-1);
        expect(chip).toBeLessThan(identityClose);
        expect(source).not.toContain('<RelationshipEvidencePanel');
        expect(signals).toBeGreaterThan(relationship);
        expect(signals).toBeLessThan(activity);
        expect(evaluation).toBeGreaterThan(signals);
        expect(evaluation).toBeLessThan(activity);
        expect(source).toContain('<PageShell>');
        expect(source).not.toContain('xl:sticky');
        expect(source.indexOf('<EngineEvaluationPanel', evaluation + 1)).toBe(-1);
    });

    it('company detail uses the wide left-rail canvas with evidence in the identity chip dialog', () => {
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
        const chip = source.indexOf('<WarmthEvidenceChip');
        const sparkline = source.indexOf('<EngagementSparkline');
        const identityClose = source.indexOf('</RecordDetailSection>', source.indexOf('section="identity"'));

        expect(profile).toBeGreaterThan(-1);
        expect(metrics).toBeGreaterThan(profile);
        expect(relationship).toBe(-1);
        expect(activity).toBeGreaterThan(metrics);
        expect(related).toBeGreaterThan(activity);
        expect(files).toBeGreaterThan(related);
        expect(history).toBeGreaterThan(files);
        expect(chip).toBeGreaterThan(-1);
        expect(chip).toBeLessThan(identityClose);
        expect(source).not.toContain('<RelationshipEvidencePanel');
        expect(source).not.toContain('RecordStickyContext');
        expect(sparkline).toBeGreaterThan(files);
        expect(source).toContain('<PageShell>');
        expect(source).not.toContain('xl:sticky');
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
        const sparkline = source.indexOf('<EngagementSparkline');
        const timeline = source.indexOf('<Timeline');
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
        expect(sparkline).toBeGreaterThan(files);
        expect(sparkline).toBeLessThan(timeline);
        expect(source).not.toContain('xl:sticky');
        expect(source.indexOf('<DealRiskPanel', risk + 1)).toBe(-1);
        expect(source.indexOf('<DealBriefPanel', brief + 1)).toBe(-1);
        expect(source.indexOf('<EngineEvaluationPanel', evaluation + 1)).toBe(-1);
        expect(source.indexOf('<EngagementSparkline', sparkline + 1)).toBe(-1);
        expect(source).toContain('<PageShell>');
    });

    it('deal detail loading skeleton matches the uncapped PageShell and left-rail order', () => {
        const source = readFileSync(
            path.resolve(process.cwd(), 'app/(app)/records/deals/[id]/loading.tsx'),
            'utf8',
        );
        expect(source).toContain('<PageShell>');
        expect(source).not.toMatch(/<PageShell[^>]*max-w-/);
        expect(source).not.toContain('xl:sticky');
        const peopleSkeleton = source.indexOf('size-12 shrink-0 rounded-full');
        const filesSkeleton = source.indexOf('xl:grid-cols-2');
        const engagementSkeleton = source.indexOf('md:grid-cols-3');
        const timelineSkeleton = source.lastIndexOf('size-8 shrink-0 rounded-full');
        expect(peopleSkeleton).toBeGreaterThan(-1);
        expect(filesSkeleton).toBeGreaterThan(peopleSkeleton);
        expect(engagementSkeleton).toBeGreaterThan(filesSkeleton);
        expect(timelineSkeleton).toBeGreaterThan(engagementSkeleton);
    });

    it('contact and company loading skeletons match the uncapped left-rail canvas', () => {
        for (const relativePath of [
            'app/(app)/records/contacts/[id]/loading.tsx',
            'app/(app)/records/companies/[id]/loading.tsx',
        ]) {
            const source = readFileSync(path.resolve(process.cwd(), relativePath), 'utf8');
            expect(source).toContain('<PageShell>');
            expect(source).not.toMatch(/<PageShell[^>]*max-w-/);
            expect(source).toContain('xl:grid-cols-[minmax(16rem,20rem)_minmax(0,1fr)]');
            expect(source).not.toContain('xl:sticky');
        }
    });
});
