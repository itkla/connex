import { readFileSync } from 'node:fs';
import path from 'node:path';
import { describe, expect, it } from 'vitest';

const API_SOURCE = readFileSync(path.resolve(process.cwd(), 'app/lib/api.ts'), 'utf8');

const MUTATING_HELPERS = ['postJson', 'putJson', 'patchJson', 'deleteJson', 'postFormData'] as const;

/**
 * How many read fetchers are currently routed through the error-swallowing `safeWithCookie`.
 * Pinned exactly rather than as a floor, so a call site the scan stops resolving fails loudly
 * instead of quietly shrinking the guard. Migrating a read off the wrapper should decrement it.
 */
const SAFE_WITH_COOKIE_READ_SITES = 39;

/** Drops comments so a doc-comment mention of the wrapper is not scanned as a call site. */
function withoutComments(source: string): string {
    return source.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/[^\n]*/g, '');
}

const API_CODE = withoutComments(API_SOURCE);

/**
 * Names every fetcher routed through the error-swallowing wrapper, by scanning for the arrow
 * that follows each reference to it, and counts the references it could not resolve.
 *
 * The marker omits the `<` so a call that lets the type argument be inferred is still seen, and
 * the lambda parameter is matched generically so renaming it does not hide a call. Both the
 * single-line and the wrapped call layouts in `api.ts` are matched.
 */
function fetchersRoutedThroughSafeWithCookie(): { names: string[]; unresolved: number } {
    const marker = 'safeWithCookie';
    const names: string[] = [];
    let unresolved = 0;
    let index = API_CODE.indexOf(marker);
    while (index !== -1) {
        const arrow = /\(\s*\w+\s*\)\s*=>\s*(\w+)\(/.exec(API_CODE.slice(index, index + 240));
        if (arrow) {
            names.push(arrow[1]);
        } else {
            unresolved += 1;
        }
        index = API_CODE.indexOf(marker, index + marker.length);
    }
    return { names, unresolved };
}

function bodyOf(fetcherName: string): string {
    const declaration = new RegExp(`function ${fetcherName}\\s*\\([^)]*\\)[^{]*\\{([\\s\\S]*?)\\n\\}`);
    const match = declaration.exec(API_CODE);
    if (!match) {
        throw new Error(`could not locate the definition of ${fetcherName} in app/lib/api.ts`);
    }
    return match[1];
}

describe('the error-swallowing cookie wrapper stays off write paths', () => {
    it('resolves every call site, so the guard below cannot silently shrink', () => {
        const { names, unresolved } = fetchersRoutedThroughSafeWithCookie();
        const references = API_CODE.split('safeWithCookie').length - 1;

        expect(unresolved, 'only the wrapper declaration should resolve to no fetcher').toBe(1);
        expect(names.length).toBe(references - 1);
        expect(names.length).toBe(SAFE_WITH_COOKIE_READ_SITES);
    });

    it('routes no mutation through safeWithCookie', () => {
        const { names } = fetchersRoutedThroughSafeWithCookie();

        for (const fetcher of names) {
            const used = MUTATING_HELPERS.filter((helper) => bodyOf(fetcher).includes(helper));
            expect(used, `${fetcher} is a write and must not swallow its failure`).toEqual([]);
        }
    });

    it('documents the wrapper as read-only so the next write does not land on it', () => {
        expect(API_SOURCE).toMatch(/\*\*Reads only\.\*\*/);
    });

    it('keeps no SSR write wrapper that cannot carry a CSRF header', () => {
        expect(API_CODE).not.toContain('addContactTagFromCookie');
        expect(API_CODE).not.toContain('removeContactTagFromCookie');
        expect(API_CODE).not.toContain('replaceContactTagsFromCookie');
    });
});
