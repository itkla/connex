import { createTranslator } from 'next-intl';
import { describe, expect, it } from 'vitest';

import {
    defaultPersonNameOrder,
    IGNORE_COLUMN,
    importFieldOptions,
    parseCsv,
    prepareImport,
    suggestColumnTargets,
    suggestField,
    type ParsedCsv,
} from '@/app/lib/import';
import type { ImportColumnMapping, ImportEntity } from '@/app/lib/types';
import en from '@/messages/en/importExport.json';
import ja from '@/messages/ja/importExport.json';

describe('conservative entity-aware header matching', () => {
    it.each([
        ['Company Name', 'persons', 'company'],
        ['Company Name', 'companies', 'name'],
        ['Company Name', 'deals', 'company'],
        ['Company Domain Name', 'companies', 'website'],
        ['Account Name', 'persons', 'company'],
        ['Account Name', 'companies', 'name'],
        ['Contact Name', 'persons', 'name'],
        ['Deal Name', 'deals', 'name'],
        ['First Name', 'persons', 'givenName'],
        ['Last Name', 'persons', 'familyName'],
        ['Surname', 'persons', 'familyName'],
        ['氏名', 'persons', 'name'],
        ['名前', 'persons', 'name'],
        ['姓', 'persons', 'familyName'],
        ['名', 'persons', 'givenName'],
        ['メール', 'persons', 'email'],
        ['メールアドレス', 'persons', 'email'],
        ['電話番号', 'persons', 'phone'],
        ['役職', 'persons', 'title'],
        ['会社名', 'persons', 'company'],
        ['会社名', 'companies', 'name'],
        ['会社名', 'deals', 'company'],
        ['ウェブサイト', 'companies', 'website'],
        ['業種', 'companies', 'industry'],
        ['住所', 'companies', 'address'],
        ['案件名', 'deals', 'name'],
        ['金額', 'deals', 'value'],
        ['通貨', 'deals', 'currency'],
        ['パイプライン', 'deals', 'pipeline'],
        ['ステージ', 'deals', 'stage'],
        ['完了予定日', 'deals', 'expectedCloseDate'],
        ['決着日時', 'deals', 'closedAt'],
        ['作成日時', 'deals', 'createdAt'],
        ['連絡先', 'deals', 'people'],
        ['タグ', 'persons', 'tags'],
        ['Tags', 'companies', 'tags'],
        [' Ｆｉｒｓｔ＿Ｎａｍｅ ', 'persons', 'givenName'],
        [' E-mail Address ', 'persons', 'email'],
        ['会社　名', 'persons', 'company'],
    ] satisfies [string, ImportEntity, string][])('maps %s on %s to %s', (header, entity, field) => {
        expect(suggestField(header, entity)).toBe(field);
    });

    it.each([
        'Lifecycle Stage', 'lifecycleStage', 'disqualificationReason', 'disqualificationReasonLabel',
        'Source', 'Lifecycle Status', 'Stage label', 'Contact owner', 'Contact ID', 'Annual revenue',
        'A', 'tagline', 'My labels', 'Name suffix', 'Unknown', 'Na\uFFFDme', '氏名（フリガナ）',
        'セイ', 'メイ', '備考', 'ライフサイクルステージ',
    ])('leaves uncertain or unsupported %s unmapped for every entity', (header) => {
        for (const entity of ['persons', 'companies', 'deals'] as const) {
            expect(suggestField(header, entity)).toBeNull();
        }
    });

    it.each([
        ['Company Domain Name', 'persons'], ['Contact Name', 'companies'], ['Deal Name', 'persons'],
        ['First Name', 'companies'], ['Last Name', 'deals'], ['役職', 'companies'], ['住所', 'persons'],
    ] satisfies [string, ImportEntity][])('never borrows another entity field for %s on %s', (header, entity) => {
        expect(suggestField(header, entity)).toBeNull();
    });

    it('does not take Name away from a full-name column or send lifecycle data to Tags', () => {
        expect(suggestColumnTargets([
            'First Name', 'Last Name', 'Full Name', 'Company Name', 'Lifecycle Stage', 'Tags',
        ], 'persons')).toEqual({
            'First Name': IGNORE_COLUMN, 'Last Name': IGNORE_COLUMN, 'Full Name': 'name',
            'Company Name': 'company', 'Lifecycle Stage': IGNORE_COLUMN, Tags: 'tags',
        });
    });

    it('leaves competing aliases unmapped independently of column order', () => {
        for (const headers of [['Email', 'E-mail Address'], ['E-mail Address', 'Email']]) {
            expect(suggestColumnTargets(headers, 'persons')).toEqual({ Email: IGNORE_COLUMN, 'E-mail Address': IGNORE_COLUMN });
        }
        expect(suggestColumnTargets(['First Name', 'Given Name', 'Last Name'], 'persons')).toEqual({
            'First Name': IGNORE_COLUMN, 'Given Name': IGNORE_COLUMN, 'Last Name': 'familyName',
        });
        expect(suggestColumnTargets(['Tags', 'Labels'], 'persons')).toEqual({ Tags: 'tags', Labels: 'tags' });
    });
});

const splitMapping: ImportColumnMapping[] = [
    { column: 'First Name', field: 'givenName' },
    { column: 'Last Name', field: 'familyName' },
    { column: 'Email', field: 'email' },
];

function csv(rows: ParsedCsv['rows']): ParsedCsv {
    return { encoding: 'utf-8', headers: Object.keys(rows[0] ?? {}), rows };
}

describe('complete person names through the existing import payload', () => {
    const parsed = csv([
        { 'First Name': ' Aiko ', 'Last Name': ' Mori ', Email: 'aiko@example.invalid' },
        { 'First Name': '', 'Last Name': 'Ito', Email: 'ito@example.invalid' },
        { 'First Name': 'Anna', 'Last Name': '', Email: 'anna@example.invalid' },
        { 'First Name': ' ', 'Last Name': ' ', Email: 'empty@example.invalid' },
    ]);

    it('keeps both parts, handles missing parts and leaves empty names for server validation', () => {
        const prepared = prepareImport(parsed, 'persons', splitMapping, 'given_family');
        expect(prepared.nameConflict).toBe(false);
        expect(prepared.nameColumn).not.toBeNull();
        expect(prepared.rows.map((row) => row[prepared.nameColumn ?? ''])).toEqual(['Aiko Mori', 'Ito', 'Anna', '']);
        expect(prepared.mapping).toEqual([
            { column: 'Email', field: 'email' }, { column: prepared.nameColumn, field: 'name' },
        ]);
        expect(prepared.rows[0]?.['First Name']).toBe(' Aiko ');
        expect(parsed.rows[0]).not.toHaveProperty(prepared.nameColumn ?? '');
    });

    it('supports a family-name-only column instead of rejecting a row with no given name', () => {
        const prepared = prepareImport(csv([{ 姓: '山田' }]), 'persons', [{ column: '姓', field: 'familyName' }], 'family_given');
        expect(prepared.rows[0]?.[prepared.nameColumn ?? '']).toBe('山田');
    });

    it('preserves full names without guessing splits or changing their text', () => {
        const parsedFull = csv([{ Name: '  山田太郎  ' }, { Name: 'María del Carmen García López' }]);
        const mapping = [{ column: 'Name', field: 'name' }];
        const prepared = prepareImport(parsedFull, 'persons', mapping, 'family_given');
        expect(prepared.rows).toBe(parsedFull.rows);
        expect(prepared.mapping).toBe(mapping);
        expect(prepared.nameColumn).toBe('Name');
        expect(prepared.usesNameParts).toBe(false);
    });

    it('supports Japanese family-given order and exposes changed values to review binding', () => {
        const japanese = csv([{ 'First Name': '太郎', 'Last Name': '山田', Email: '' }]);
        const givenFirst = prepareImport(japanese, 'persons', splitMapping, 'given_family');
        const familyFirst = prepareImport(japanese, 'persons', splitMapping, 'family_given');
        expect(givenFirst.rows[0]?.[givenFirst.nameColumn ?? '']).toBe('太郎 山田');
        expect(familyFirst.rows[0]?.[familyFirst.nameColumn ?? '']).toBe('山田 太郎');
        expect(JSON.stringify(givenFirst.rows)).not.toBe(JSON.stringify(familyFirst.rows));
        expect(prepareImport(japanese, 'persons', splitMapping, 'family_given')).toEqual(familyFirst);
    });

    it.each([
        { mapping: [...splitMapping, { column: 'Name', field: 'name' }] },
        { mapping: [...splitMapping, { column: 'Other first name', field: 'givenName' }] },
        { mapping: [...splitMapping, { column: 'Other last name', field: 'familyName' }] },
    ])('refuses conflicting name columns without composing or overwriting any values', ({ mapping }) => {
        const prepared = prepareImport(parsed, 'persons', mapping, 'given_family');
        expect(prepared.nameConflict).toBe(true);
        expect(prepared.rows).toBe(parsed.rows);
    });

    it('never overwrites a source header or a row key when creating the derived name', () => {
        const collision = csv([{ 'First Name': 'Aiko', 'Last Name': 'Mori', __connex_import_name__: 'keep me' }]);
        collision.rows.push({ 'First Name': 'Anna', 'Last Name': 'Bell', __connex_import_name___: 'also keep me' });
        const prepared = prepareImport(collision, 'persons', splitMapping, 'given_family');
        expect(prepared.nameColumn).toBe('__connex_import_name____');
        expect(prepared.rows[0]?.__connex_import_name__).toBe('keep me');
        expect(prepared.rows[1]?.__connex_import_name___).toBe('also keep me');
        expect(prepared.rows[0]?.[prepared.nameColumn ?? '']).toBe('Aiko Mori');
    });

    it.each([
        [[], 'en', 'given_family'], [[], 'ja', 'family_given'],
        [['名', '姓'], 'en', 'family_given'], [['First Name', 'Last Name'], 'ja', 'family_given'],
    ] satisfies [string[], string, string][])('defaults %j in %s to %s', (headers, locale, expected) => {
        expect(defaultPersonNameOrder(headers, locale)).toBe(expected);
    });

    it.each(['utf-8', 'utf-8-bom', 'shift_jis'])('composes lossless Japanese names after %s decoding', async (encoding) => {
        const prefix = encoding === 'utf-8-bom' ? [0xef, 0xbb, 0xbf] : [];
        const data = encoding === 'shift_jis'
            ? [...new TextEncoder().encode('First Name,Last Name,Company Name\n'),
                0x91, 0xbe, 0x98, 0x59, 0x2c, 0x8e, 0x52, 0x93, 0x63, 0x2c, 0x87, 0x8a, 0x83, 0x65, 0x83, 0x58, 0x83, 0x67, 0x0a]
            : [...new TextEncoder().encode('First Name,Last Name,Company Name\n太郎,山田,㈱テスト\n')];
        const decoded = await parseCsv(new File([Uint8Array.from([...prefix, ...data])], 'names.csv'));
        const targets = suggestColumnTargets(decoded.headers, 'persons');
        const prepared = prepareImport(decoded, 'persons', decoded.headers.map((column) => ({ column, field: targets[column] })), 'family_given');
        expect(prepared.rows[0]?.[prepared.nameColumn ?? '']).toBe('山田 太郎');
        expect(prepared.rows[0]?.['Company Name']).toBe('㈱テスト');
        expect(prepared.mapping).toContainEqual({ column: 'Company Name', field: 'company' });
    });
});

describe('name mapping controls in both languages', () => {
    it.each(['en', 'ja'] as const)('labels every supported option and explains composition in %s', (locale) => {
        const t = createTranslator({ locale, messages: locale === 'en' ? en : ja, namespace: 'importExport' });
        const fields = new Map(Object.entries((locale === 'en' ? en : ja).importExport.fields));
        for (const entity of ['persons', 'companies', 'deals'] as const) {
            for (const field of importFieldOptions(entity)) expect(fields.get(field)).toBeTruthy();
        }
        expect(t('namePreview', { names: '山田 太郎' })).toContain('山田 太郎');
        expect(t('mapColumn', { column: '姓' })).toContain('姓');
        expect(t('nameMappingConflict')).toMatch(locale === 'en' ? /Ignore/ : /取り込まない/);
    });
});
