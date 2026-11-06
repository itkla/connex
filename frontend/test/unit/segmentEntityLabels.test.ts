import { createTranslator } from 'next-intl';
import { describe, expect, it } from 'vitest';
import { segmentConditionLabel, segmentDefinitionLabel } from '@/app/components/records/SegmentBuilder';
import type { SegmentDefinition } from '@/app/lib/types';
import en from '@/messages/en/records.json';
import ja from '@/messages/ja/records.json';

type Messages = { [key: string]: string | Messages };

describe.each([
    { locale: 'en', messages: en, contact: 'Contact name', deal: 'Deal name', company: 'Linked company' },
    { locale: 'ja', messages: ja, contact: '連絡先名', deal: '案件名', company: '関連会社' },
])('saved condition labels in $locale', ({ locale, messages, contact, deal, company }) => {
    const catalog: Messages = messages;
    const t = createTranslator({ locale, messages: catalog, namespace: 'SmartSegments' });

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
});
