import { createTranslator } from 'next-intl';
import { describe, expect, it } from 'vitest';
import { ApiError } from '@/app/lib/api';
import { companyChoiceRows, duplicateMappingColumns, existingCustomTarget, importFailureKey, importMappingErrorColumns, importPresentationProblems, importReviewRows, reviewPage, unavailableCustomColumns } from '@/app/lib/importReview';
import type { CustomFieldSchemaEntry, DuplicateCandidate, ImportRowAnalysis } from '@/app/lib/types';
import en from '@/messages/en/importExport.json';
import ja from '@/messages/ja/importExport.json';

const candidate: DuplicateCandidate = {
    recordId: 10, recordType: 'company', name: 'Example', ownedByActiveWorkspace: true,
    strength: 'WEAK', matches: [{ kind: 'NAME', normalizedValue: 'example', strength: 'WEAK' }],
};
function row(index: number): ImportRowAnalysis {
    return { rowIndex: index, status: index % 3 === 0 ? 'skip' : index % 3 === 1 ? 'invalid' : 'create' };
}

describe('complete import review', () => {
    it('keeps structured currency and amount problems authoritative over raw diagnostics', () => {
        const problems = [{ code: 'CURRENCY_INVALID' }, { code: 'VALUE_INVALID' }];
        expect(importPresentationProblems({ problems, errors: ['private diagnostic'] })).toBe(problems);
        expect(importPresentationProblems({})).toEqual([]);
    });

    it.each(['en', 'ja'] as const)('localizes combined legacy currency errors without exposing diagnostics in %s', (locale) => {
        const messages = locale === 'en' ? en : ja;
        const t = createTranslator({ locale, messages, namespace: 'importExport' });
        const errors = ['CURRENCY_INVALID', 'Company name variant requires an explicit company selection', 'Invalid deal value: private'];
        const expected = [{ code: 'CURRENCY_INVALID' }, { code: 'COMPANY_VARIANT_CHOICE_REQUIRED' }, { code: 'ROW_INVALID' }];
        expect(importPresentationProblems({ errors })).toEqual(expected);
        expect(importPresentationProblems({ reason: errors.join('; ') })).toEqual(expected);
        const text = importPresentationProblems({ errors }).map((problem) => {
            const code = Object.keys(messages.importExport.problems).find(
                (key): key is keyof typeof messages.importExport.problems => key === problem.code,
            );
            if (!code) throw new Error('Missing import problem translation');
            return t(`problems.${code}`);
        }).join('; ');
        expect(text).toContain(messages.importExport.problems.CURRENCY_INVALID);
        expect(text).toContain(messages.importExport.problems.COMPANY_VARIANT_CHOICE_REQUIRED);
        expect(text).not.toContain('private');
        expect(text).not.toContain('CURRENCY_INVALID');
    });

    it('shares one bounded dependency catalogue across all source rows', () => {
        const rows = importReviewRows({ total: 2, toCreate: 0, toUpdate: 0, toSkip: 0, invalid: 2,
            companyCandidates: { example: [candidate] },
            rows: [0, 1].map((rowIndex) => ({ rowIndex, status: 'invalid', companyDependency: {
                name: 'Example', key: 'example', truncated: false,
            } })),
        });
        expect(rows[0].companyDependency?.candidates).toEqual([candidate]);
        expect(rows[0].companyDependency?.candidates).toBe(rows[1].companyDependency?.candidates);
        expect(companyChoiceRows(rows, rows[0], 10)).toEqual([0, 1]);
    });

    it('makes every row reachable without allowing candidates to defeat the page bound', () => {
        const rows = Array.from({ length: 161 }, (_, index) => ({ ...row(index), candidates: [candidate] }));
        const visited = Array.from({ length: reviewPage(rows, 'all', 0).pages }, (_, page) => reviewPage(rows, 'all', page).rows).flat();
        expect(visited.map((item) => item.rowIndex)).toEqual(rows.map((item) => item.rowIndex));
        expect(reviewPage(rows, 'all', 0).rows).toHaveLength(20);
        expect(reviewPage(rows, 'all', 8).rows).toHaveLength(1);
        expect(reviewPage(rows, 'skip', 0).total).toBe(54);
        expect(reviewPage(rows, 'invalid', 0).total).toBe(54);
        expect(reviewPage(rows, 'match', 8)).toMatchObject({ current: 0, pages: 1, rows: [] });
    });

    it('keeps ambiguous company rows in the decision filter but suppresses resolved identity suggestions', () => {
        const rows: ImportRowAnalysis[] = [{ rowIndex: 0, status: 'match', candidates: [candidate] },
            { rowIndex: 1, status: 'invalid', companyDependency: { name: 'Example', key: 'example', candidates: [candidate], truncated: false } }];
        expect(reviewPage(rows, 'decision', 0).rows.map((item) => item.rowIndex)).toEqual([1]);
    });

    it('bulk selection includes only rows with the same dependency and a complete visible candidate set', () => {
        const dependency = { key: 'example', name: 'Example', candidates: [candidate], truncated: false };
        const source: ImportRowAnalysis = { rowIndex: 0, status: 'invalid', companyDependency: dependency };
        const rows = [source, { ...source, rowIndex: 1 },
            { ...source, rowIndex: 2, companyDependency: { ...dependency, truncated: true } },
            { ...source, rowIndex: 3, companyDependency: { ...dependency, candidates: [] } }];
        expect(companyChoiceRows(rows, source, 10)).toEqual([0, 1]);
        expect(companyChoiceRows(rows, source, 999)).toEqual([]);
    });

    it('rejects duplicate standard and existing custom targets while preserving multi-column tags', () => {
        expect([...duplicateMappingColumns([{ column: 'A', field: 'email' }, { column: 'B', field: 'email' },
            { column: 'C', field: 'custom:1' }, { column: 'D', field: 'custom:1' },
            { column: 'E', field: 'tags' }, { column: 'F', field: 'tags' }])]).toEqual(['A', 'B', 'C', 'D']);
    });

    it('selects an existing custom field only for a unique exact label', () => {
        const field: CustomFieldSchemaEntry = { definitionId: 1, label: '地域', fieldType: 'text', options: null, required: false };
        expect(existingCustomTarget(' 地域 ', [field])).toBe('custom:1');
        expect(existingCustomTarget('地域名', [field])).toBeNull();
        expect(existingCustomTarget('地域', [field, { ...field, definitionId: 2 }])).toBeNull();
    });

    it.each([['Region Code', 'Region-Code'], ['Region code', 'Region-code']])('marks both server-resolved aliases %s and %s inline', (first, second) => {
        const error = new ApiError('Diagnostic only', 400, 'IMPORT_MAPPING_DUPLICATE', {
            [first]: 'IMPORT_MAPPING_DUPLICATE', [second]: 'IMPORT_MAPPING_DUPLICATE', unrelated: 'IMPORT_MAPPING_DUPLICATE',
        });
        expect([...importMappingErrorColumns(error, [first, second])]).toEqual([first, second]);
        expect(importMappingErrorColumns(new ApiError('Other failure', 400, 'BAD_REQUEST', error.fieldErrors), [first, second]).size).toBe(0);
    });

    it('keeps an archived mapping explicit until the user selects its replacement', () => {
        const mapping = [{ column: 'Legacy', field: 'custom:1' }];
        const replacement: CustomFieldSchemaEntry = { definitionId: 2, label: 'Territory replacement', fieldType: 'text', options: null, required: false };
        expect([...unavailableCustomColumns(mapping, [replacement])]).toEqual(['Legacy']);
        expect(mapping).toEqual([{ column: 'Legacy', field: 'custom:1' }]);
        expect(unavailableCustomColumns([{ column: 'Legacy', field: 'custom:2' }], [replacement]).size).toBe(0);
    });

    it.each(['en', 'ja'] as const)('localizes every structured problem and correction in %s', (locale) => {
        const messages = locale === 'en' ? en : ja;
        const t = createTranslator({ locale, messages, namespace: 'importExport' });
        const codes = Object.keys(en.importExport.problems).filter(
            (code): code is keyof typeof en.importExport.problems => Object.hasOwn(en.importExport.problems, code),
        );
        for (const code of codes) {
            const text = t(`problems.${code}`, { field: '項目' });
            expect(text).not.toContain(`problems.${code}`);
            expect(text).toMatch(locale === 'ja' ? /スキップ|再確認/ : /skip|review/i);
        }
        expect(importFailureKey(new ApiError('raw technical message', 400, 'IMPORT_CUSTOM_COLLISION'))).toBe('failures.CUSTOM_COLLISION');
        expect(importFailureKey(new ApiError('raw technical message', 409))).toBe('failures.REVIEW_CHANGED');
        expect(importFailureKey(new Error('secret diagnostic'))).toBe('failures.UNAVAILABLE');
    });
});
