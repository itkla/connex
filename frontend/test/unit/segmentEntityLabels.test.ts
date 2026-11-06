import { createTranslator } from 'next-intl';
import { describe, expect, it } from 'vitest';
import { segmentConditionLabel, segmentDefinitionLabel } from '@/app/lib/segmentLabels';
import type { RuleBuilderOptions, SegmentDefinition } from '@/app/lib/types';
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

    const membershipOptions: RuleBuilderOptions = {
        companies: [{ id: 42, name: 'Sakura Foods' }, { id: 43, name: 'Lotus Labs' }],
        stages: [{ id: 42, name: 'Discovery', pipeline: 'Sales' }, { id: 43, name: 'Review', pipeline: 'Sales' }],
        owners: [{ id: 42, name: 'Mika' }, { id: 43, name: 'Aya' }],
    };

    it.each([false, true])('preserves pre-resolved scalar chips with negate=%s', (negate) => {
        const stage = { type: 'field' as const, field: 'stage', op: 'is', value: 'Discovery', negate };
        const owner = { type: 'field' as const, field: 'owner', op: 'is', value: '42', negate };
        const op = t(negate ? 'op.isNot' : 'op.is');
        expect(segmentConditionLabel(stage, t, String, undefined, 'deal')).toBe(
            t('chipField', { field: t('field.stage'), op, value: 'Discovery' }),
        );
        expect(segmentConditionLabel(owner, t, String, () => 'Mika', 'company')).toBe(
            t('chipField', { field: t('field.owner'), op, value: 'Mika' }),
        );
    });
    const membershipFields = [
        { field: 'company', recordType: 'person', names: ['Sakura Foods', 'Lotus Labs'] },
        { field: 'stage', recordType: 'deal', names: ['Discovery', 'Review'] },
        { field: 'owner', recordType: 'company', names: ['Mika', 'Aya'] },
    ];

    describe.each(membershipFields)('$field membership', ({ field, recordType, names }) => {
        it.each([false, true])('renders every resolved operand with negate=%s', (negate) => {
            const condition = { type: 'field' as const, field, op: 'in', values: ['42', '43'], negate };
            const definition: SegmentDefinition = { match: 'all', conditions: [condition] };
            const before = JSON.stringify(definition);
            const expected = t('chipField', {
                field: t(`field.${field}`), op: t(negate ? 'op.notIn' : 'op.in'),
                value: names.join(t('valueSeparator')),
            });
            expect(segmentDefinitionLabel(definition, recordType, t, null, membershipOptions)).toBe(expected);
            expect(segmentConditionLabel(condition, t, String, undefined, recordType, membershipOptions)).toBe(expected);
            expect(JSON.stringify(definition)).toBe(before);
        });

        it.each([false, true])('keeps unresolved operands visible without raw IDs with negate=%s', (negate) => {
            const condition = { type: 'field' as const, field, op: 'in', values: ['42', '99999'], negate };
            const definition: SegmentDefinition = { match: 'all', conditions: [condition] };
            const label = segmentDefinitionLabel(definition, recordType, t, null, membershipOptions);
            expect(label).toBe(t('chipField', {
                field: t(`field.${field}`), op: t(negate ? 'op.notIn' : 'op.in'),
                value: [names[0], t('unavailableValue')].join(t('valueSeparator')),
            }));
            expect(label).not.toContain('99999');
            const withoutOptions = segmentDefinitionLabel(definition, recordType, t, null);
            expect(withoutOptions).toBe(t('chipField', {
                field: t(`field.${field}`), op: t(negate ? 'op.notIn' : 'op.in'),
                value: [t('unavailableValue'), t('unavailableValue')].join(t('valueSeparator')),
            }));
        });
    });
});
