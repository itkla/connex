import { describe, expect, it } from 'vitest';
import { createTranslator } from 'next-intl';

import en from '@/messages/en/importExport.json';
import ja from '@/messages/ja/importExport.json';

import { STANDARD_FIELDS, suggestField } from '@/app/lib/import';

describe('deal import history mapping', () => {
    it('offers historical dates separately from the expected close date', () => {
        expect(STANDARD_FIELDS.deals).toEqual(expect.arrayContaining(['closedAt', 'createdAt', 'expectedCloseDate']));
        expect(STANDARD_FIELDS.persons).not.toContain('closedAt');
        expect(STANDARD_FIELDS.companies).not.toContain('createdAt');
    });

    it.each([
        ['Closed at', 'closedAt'],
        ['Actual close date', 'closedAt'],
        ['Closed date', 'closedAt'],
        ['Created at', 'createdAt'],
        ['Creation date', 'createdAt'],
        ['Expected close date', 'expectedCloseDate'],
    ])('maps %s to %s', (header, field) => {
        expect(suggestField(header, 'deals')).toBe(field);
    });
});

describe('import date warning copy', () => {
    it.each(['en', 'ja'] as const)('names the count and fallback in %s before commit', (locale) => {
        const t = createTranslator({ locale, messages: locale === 'en' ? en : ja, namespace: 'importExport' });
        expect(t('missingCloseDates', { count: 2 })).toContain('2');
        expect(t('missingCloseDates', { count: 1 })).toContain('1');
        expect(t('rowUsesImportTime')).toMatch(locale === 'en' ? /import time/ : /インポート時/);
        expect(t('futureCloseDate')).toMatch(locale === 'en' ? /future/ : /未来/);
    });
});
