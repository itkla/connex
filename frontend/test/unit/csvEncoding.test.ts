import { describe, expect, it } from 'vitest';

import { CsvEncodingError, parseCsv, suggestField } from '@/app/lib/import';
import { buildHistoryImportMapping, suggestHistoryImportField } from '@/app/lib/interaction-history-import';

const japaneseName = [0x8e, 0x52, 0x93, 0x63, 0x91, 0xbe, 0x98, 0x59];
const windowsCompany = [0x87, 0x8a, 0x83, 0x65, 0x83, 0x58, 0x83, 0x67];

function csvFile(bytes: number[]): File {
    return new File([Uint8Array.from(bytes)], 'contacts.csv', { type: 'text/csv' });
}

function utf8(text: string): number[] {
    return [...new TextEncoder().encode(text)];
}

describe('CSV encoding detection', () => {
    it.each([
        { label: 'UTF-8', prefix: [] },
        { label: 'UTF-8 with BOM', prefix: [0xef, 0xbb, 0xbf] },
    ])('preserves Japanese names and Windows characters in $label', async ({ prefix }) => {
        const result = await parseCsv(csvFile([
            ...prefix,
            ...utf8(' Name ,Company\r\n山田太郎,㈱テスト\r\n,\r\n'),
        ]));

        expect(result).toEqual({
            encoding: 'utf-8',
            headers: ['Name', 'Company'],
            rows: [{ Name: '山田太郎', Company: '㈱テスト' }],
        });
    });

    it('decodes Japanese Excel Windows-932 bytes including ㈱ before auto-mapping', async () => {
        const result = await parseCsv(csvFile([
            ...utf8('Name,Company\r\n'),
            ...japaneseName, 0x2c, ...windowsCompany, 0x0d, 0x0a,
        ]));

        expect(result).toEqual({
            encoding: 'shift_jis',
            headers: ['Name', 'Company'],
            rows: [{ Name: '山田太郎', Company: '㈱テスト' }],
        });
        expect(result.headers.map((header) => suggestField(header, 'persons'))).toEqual(['name', 'company']);
    });

    it('preserves Japanese headers without auto-mapping stray bytes from mojibake', async () => {
        const result = await parseCsv(csvFile([
            142, 129, 150, 188, 44, 137, 239, 142, 208, 150, 188, 44,
            150, 240, 144, 69, 44, 143, 90, 143, 138, 10,
            ...japaneseName, 44, ...windowsCompany, 44, 149, 148, 146, 183, 44,
            147, 140, 139, 158, 147, 115, 10,
        ]));

        expect(result.headers).toEqual(['氏名', '会社名', '役職', '住所']);
        expect(result.rows).toEqual([{
            氏名: '山田太郎', 会社名: '㈱テスト', 役職: '部長', 住所: '東京都',
        }]);
        expect(suggestField(result.headers[2], 'persons')).not.toBe('name');
        expect(suggestField(result.headers[3], 'persons')).not.toBe('company');
    });

    it.each([
        { label: 'truncated Shift-JIS', bytes: [...utf8('Name\n'), 0x81] },
        { label: 'illegal bytes', bytes: [...utf8('Name\n'), 0xff, 0xff] },
        { label: 'invalid Shift-JIS pair', bytes: [...utf8('Name\n'), 0x81, 0x30] },
        { label: 'replacement in a header', bytes: utf8('Na\uFFFDme\n山田太郎\n') },
        { label: 'replacement in a value', bytes: utf8('Name\n山田\uFFFD郎\n') },
        { label: 'replacement in an unmapped column', bytes: utf8('Name,Unused\n山田太郎,\uFFFD\n') },
    ])('refuses $label before returning importable rows', async ({ bytes }) => {
        await expect(parseCsv(csvFile(bytes))).rejects.toBeInstanceOf(CsvEncodingError);
    });

    it('prefers UTF-8 for ASCII and preserves quoted CSV fields', async () => {
        const result = await parseCsv(csvFile(utf8('Name,Company\n"Family, Given","Line one\nLine two"\n')));
        expect(result.encoding).toBe('utf-8');
        expect(result.rows).toEqual([{ Name: 'Family, Given', Company: 'Line one\nLine two' }]);
    });

    it.each(['utf-8', 'utf-8-bom', 'shift_jis'])('keeps history mapping working through the shared parser for %s', async (encoding) => {
        const header = utf8('Occurred At,Participant Email,Subject\n2026-01-12 09:30:00,participant@example.invalid,');
        const subject = encoding === 'shift_jis' ? windowsCompany : utf8('㈱テスト');
        const prefix = encoding === 'utf-8-bom' ? [0xef, 0xbb, 0xbf] : [];
        const result = await parseCsv(csvFile([...prefix, ...header, ...subject, 10]));
        const targets = Object.fromEntries(result.headers.map((column) => [
            column, suggestHistoryImportField(column, 'activities') ?? 'ignore',
        ]));

        expect(result.encoding).toBe(encoding === 'shift_jis' ? 'shift_jis' : 'utf-8');
        expect(result.rows[0]?.Subject).toBe('㈱テスト');
        expect(buildHistoryImportMapping(result.headers, targets)).toEqual([
            { column: 'Occurred At', field: 'occurredAt' },
            { column: 'Participant Email', field: 'participantEmail' },
            { column: 'Subject', field: 'subject' },
        ]);
    });
});
