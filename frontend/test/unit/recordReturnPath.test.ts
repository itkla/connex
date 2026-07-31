import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
    consumeRecordReturnSelection,
    recordDetailNavigationPath,
    recordDetailPath,
    resolveRecordReturnPath,
} from '@/app/lib/recordReturnPath';

describe('record return paths', () => {
    const values = new Map<string, string>();
    const location = {
        pathname: '/records/contacts',
        search: '?view=table&page=2&peek=person%3A42',
        origin: 'https://connex.test',
    };
    const history = {
        state: null as unknown,
        replaceState: (state: unknown) => {
            history.state = state;
        },
    };

    beforeEach(() => {
        values.clear();
        location.pathname = '/records/contacts';
        location.search = '?view=table&page=2&peek=person%3A42';
        history.state = null;
        vi.stubGlobal('document', {
            querySelector: () => ({ scrollTop: 417 }),
        });
        vi.stubGlobal('window', {
            crypto: {
                randomUUID: () => 'a4c0f631-e34a-4e6c-b6f8-14f133e3df49',
            },
            history,
            location,
            sessionStorage: {
                getItem: (key: string) => values.get(key) ?? null,
                setItem: (key: string, value: string) => values.set(key, value),
                removeItem: (key: string) => values.delete(key),
            },
        });
    });

    afterEach(() => {
        vi.restoreAllMocks();
        vi.unstubAllGlobals();
    });

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

    it('restores a scoped selection once for the exact list URL', () => {
        recordDetailNavigationPath('contacts', 42, {
            userId: 7,
            workspaceId: 11,
            ids: [42, 17, 42],
        });

        expect(consumeRecordReturnSelection('contacts', 7, 11)).toEqual({
            ids: [42, 17],
            scrollTop: 417,
        });
        expect(consumeRecordReturnSelection('contacts', 7, 11)).toBeNull();
    });

    it('rejects a selection when the restored URL differs', () => {
        recordDetailNavigationPath('contacts', 42, {
            userId: 7,
            workspaceId: 11,
            ids: [42],
        });
        location.search = '?view=table&page=3&peek=person%3A42';

        expect(consumeRecordReturnSelection('contacts', 7, 11)).toBeNull();
    });

    it('rejects the same URL reached through a different history entry', () => {
        recordDetailNavigationPath('contacts', 42, {
            userId: 7,
            workspaceId: 11,
            ids: [42],
        });
        history.state = null;

        expect(consumeRecordReturnSelection('contacts', 7, 11)).toBeNull();
    });

    it.each([
        ['collection', 'companies', 7, 11],
        ['user', 'contacts', 8, 11],
        ['workspace', 'contacts', 7, 12],
    ] as const)('rejects a selection with a mismatched %s scope', (_scope, collection, userId, workspaceId) => {
        recordDetailNavigationPath('contacts', 42, {
            userId: 7,
            workspaceId: 11,
            ids: [42],
        });

        expect(consumeRecordReturnSelection(collection, userId, workspaceId)).toBeNull();
    });

    it.each([
        ['expired', 31 * 60 * 1000],
        ['future', -1],
    ])('rejects a %s selection marker', (_state, elapsed) => {
        vi.spyOn(Date, 'now').mockReturnValue(10_000);
        recordDetailNavigationPath('contacts', 42, {
            userId: 7,
            workspaceId: 11,
            ids: [42],
        });
        vi.mocked(Date.now).mockReturnValue(10_000 + elapsed);

        expect(consumeRecordReturnSelection('contacts', 7, 11)).toBeNull();
    });

    it('rejects malformed selection storage', () => {
        values.set('connex:record-return-selection', '{"selectedIds":"42"}');

        expect(consumeRecordReturnSelection('contacts', 7, 11)).toBeNull();
        expect(values.has('connex:record-return-selection')).toBe(false);
    });

    it.each([
        [[]],
        [[0]],
        [[1.5]],
        [Array.from({ length: 1001 }, (_, index) => index + 1)],
    ])('does not persist an invalid selection snapshot', (ids) => {
        recordDetailNavigationPath('contacts', 42, {
            userId: 7,
            workspaceId: 11,
            ids,
        });

        expect(consumeRecordReturnSelection('contacts', 7, 11)).toBeNull();
    });
});
