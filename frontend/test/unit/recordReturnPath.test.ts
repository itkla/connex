import { describe, expect, it } from 'vitest';

import {
    recordDetailPath,
    resolveRecordReturnPath,
} from '@/app/lib/recordReturnPath';

describe('record return paths', () => {
    it('builds a detail path with the exact encoded list state', () => {
        const returnTo = '/records/contacts?view=table&q=佐藤&peek=person%3A42';
        const detail = recordDetailPath('contacts', 42, returnTo);
        const url = new URL(detail, 'https://connex.invalid');

        expect(url.pathname).toBe('/records/contacts/42');
        expect(url.searchParams.get('returnTo')).toBe(returnTo);
    });

    it('preserves an allowlisted collection query', () => {
        expect(resolveRecordReturnPath(
            'deals',
            '/records/deals?view=kanban&sort=value&dir=desc&page=3&peek=deal%3A9',
        )).toBe('/records/deals?view=kanban&sort=value&dir=desc&page=3&peek=deal%3A9');
    });

    it.each([
        'https://evil.example/records/contacts',
        '//evil.example/records/contacts',
        '/\\evil.example/records/contacts',
        '/records/contacts/42?peek=person%3A42',
        '/records/companies?peek=company%3A42',
        '/records/contacts/../deals',
        '/records/contacts#peek',
        '/settings/data',
    ])('rejects hostile or cross-scope target %s', (target) => {
        expect(resolveRecordReturnPath('contacts', target)).toBe('/records/contacts');
    });

    it('rejects repeated and oversized return targets', () => {
        expect(resolveRecordReturnPath(
            'companies',
            ['/records/companies', '/records/companies?page=2'],
        )).toBe('/records/companies');
        expect(resolveRecordReturnPath(
            'companies',
            `/records/companies?q=${'x'.repeat(2048)}`,
        )).toBe('/records/companies');
    });

    it('rejects invalid record ids', () => {
        expect(() => recordDetailPath('contacts', 0)).toThrow(RangeError);
        expect(() => recordDetailPath('contacts', 1.5)).toThrow(RangeError);
    });
});
