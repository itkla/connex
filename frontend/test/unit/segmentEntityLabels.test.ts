import { createTranslator } from 'next-intl';
import { describe, expect, it } from 'vitest';
import { segmentConditionLabel, segmentDefinitionLabel } from '@/app/lib/segmentLabels';
import type { SegmentDefinition } from '@/app/lib/types';
import en from '@/messages/en/records.json';
import ja from '@/messages/ja/records.json';

type Messages = { [key: string]: string | Messages };

describe.each([
    { locale: 'en', messages: en, contact: 'Contact name', deal: 'Deal name', company: 'Linked company' },
    { locale: 'ja', messages: ja, contact: '連絡先名', deal: '案件名', company: '関連会社' },
])('saved condition labels in $locale', ({ locale, messages, contact, deal, company }) => {
    const catalog: Messages = messages;
    const translator = createTranslator({ locale, messages: catalog, namespace: 'SmartSegments' });
    const t = (key: string, values?: Record<string, string | number>): string => {
        const result: unknown = Reflect.apply(translator, undefined, [key, values]);
        if (typeof result !== 'string') throw new Error(`Expected a translated label for ${key}`);
        return result;
    };

    it('uses the chosen record type in names without changing saved matching fields', () => {
        const definition: SegmentDefinition = { match: 'all', conditions: [{ type: 'field', field: 'name', op: 'contains', value: 'Sakura' }] };
        const before = JSON.stringify(definition);
        expect(segmentDefinitionLabel(definition, 'person', t, null)).toContain(contact);
        expect(segmentDefinitionLabel(definition, 'deal', t, null)).toContain(deal);
        expect(JSON.stringify(definition)).toBe(before);
        expect(segmentConditionLabel(definition.conditions[0], t, String, undefined, 'person')).toContain(contact);
    });

    it('distinguishes the linked company, resolves references and preserves all/any groups', () => {
        const definition: SegmentDefinition = {
            match: 'all', conditions: [{ type: 'field', field: 'company', op: 'is', value: '42' }],
            groups: [{ match: 'any', conditions: [
                { type: 'field', field: 'name', op: 'is_set', negate: true },
                { type: 'field', field: 'tag', op: 'has', value: '5' },
            ] }],
        };
        const before = JSON.stringify(definition);
        const label = segmentDefinitionLabel(definition, 'person', t, { tags: [{ id: 5, name: 'Priority' }], industries: [] }, { companies: [{ id: 42, name: 'Sakura Foods' }], stages: [], owners: [] });
        expect(label).toContain(company);
        expect(label).toContain('Sakura Foods');
        expect(label).toContain('Priority');
        expect(label).toContain(t('summaryAnd'));
        expect(label).toContain(t('summaryOr'));
        expect(JSON.stringify(definition)).toBe(before);
        const unavailable = segmentDefinitionLabel(definition, 'person', t, null);
        expect(unavailable).toContain(t('unavailableValue'));
        expect(unavailable).not.toContain('42');
    });

    it('includes date durations and translated deal status in readable workflow summaries', () => {
        const definition: SegmentDefinition = { match: 'all', conditions: [
            { type: 'field', field: 'created', op: 'within_days', days: 30 },
            { type: 'field', field: 'status', op: 'is', value: 'won' },
        ] };
        const label = segmentDefinitionLabel(definition, 'deal', t, null);
        expect(label).toContain(t('durationDays', { days: 30 }));
        expect(label).toContain(t('status.won'));
    });

    it('keeps negated equality distinct from positive equality', () => {
        const condition = { type: 'field' as const, field: 'name', op: 'equals', value: 'Sakura', negate: true };
        expect(segmentConditionLabel(condition, t, String, undefined, 'person')).toBe(
            t('chipField', { field: contact, op: t('op.isNot'), value: 'Sakura' }),
        );
        expect(segmentDefinitionLabel({ match: 'all', conditions: [condition] }, 'person', t, null)).toBe(
            t('chipField', { field: contact, op: t('op.isNot'), value: 'Sakura' }),
        );
        expect(segmentConditionLabel({ ...condition, negate: false }, t, String, undefined, 'person')).toBe(
            t('chipField', { field: contact, op: t('op.is'), value: 'Sakura' }),
        );
    });

    it('groups translated negation around the entire root or nested condition group', () => {
        const negated: SegmentDefinition = { match: 'any', negate: true, conditions: [
            { type: 'field', field: 'name', op: 'equals', value: 'Sakura' },
            { type: 'field', field: 'name', op: 'equals', value: 'Lotus', negate: true },
        ] };
        const first = t('chipField', { field: contact, op: t('op.is'), value: 'Sakura' });
        const second = t('chipField', { field: contact, op: t('op.isNot'), value: 'Lotus' });
        const expected = t('summaryNot', { conditions: first + t('summaryOr') + second });
        const root: SegmentDefinition = { match: 'all', conditions: [
            { type: 'field', field: 'title', op: 'equals', value: 'Buyer' },
        ], groups: [negated] };
        const before = JSON.stringify(root);
        expect(segmentDefinitionLabel(negated, 'person', t, null)).toBe(expected);
        expect(segmentDefinitionLabel(root, 'person', t, null)).toBe(
            t('chipField', { field: t('field.title'), op: t('op.is'), value: 'Buyer' }) + t('summaryAnd') + `(${expected})`,
        );
        expect(JSON.stringify(root)).toBe(before);
    });

    it('makes negated comparisons explicit when the operator has no inverse label', () => {
        const label = segmentDefinitionLabel({ match: 'all', conditions: [
            { type: 'field', field: 'name', op: 'starts_with', value: 'Sakura', negate: true },
        ] }, 'person', t, null);
        expect(label).toBe(t('summaryNot', { conditions: t('chipField', { field: contact, op: t('op.startsWith'), value: 'Sakura' }) }));
    });
});
