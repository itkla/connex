import { readFileSync } from 'node:fs';
import path from 'node:path';
import { describe, expect, it } from 'vitest';

const API_SOURCE = readFileSync(path.resolve(process.cwd(), 'app/lib/api.ts'), 'utf8');

const SWALLOWING_READ_WRAPPER = 'safeReadWithCookie';
const MUTATING_HELPERS = ['postJson', 'putJson', 'patchJson', 'deleteJson', 'postFormData'] as const;
const DELETED_SSR_WRITE_WRAPPERS = [
    'addContactTagFromCookie',
    'removeContactTagFromCookie',
    'replaceContactTagsFromCookie',
] as const;

const SWALLOWING_READ_CALL_SITES = 37;

const ANY_SINGLE_PARAM_ARROW_TO_FETCHER = /\(\s*\w+\s*\)\s*=>\s*(\w+)\(/;

function withoutComments(source: string): string {
    return source.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/[^\n]*/g, '');
}

const API_CODE = withoutComments(API_SOURCE);

function fetchersRoutedThrough(
    source: string,
    wrapper: string,
): { names: string[]; unresolved: number } {
    const names: string[] = [];
    let unresolved = 0;
    let index = source.indexOf(wrapper);
    while (index !== -1) {
        const arrow = ANY_SINGLE_PARAM_ARROW_TO_FETCHER.exec(source.slice(index, index + 240));
        if (arrow) {
            names.push(arrow[1]);
        } else {
            unresolved += 1;
        }
        index = source.indexOf(wrapper, index + wrapper.length);
    }
    return { names, unresolved };
}

function bodyOf(source: string, fetcherName: string): string {
    const declaration = new RegExp(`function ${fetcherName}\\s*\\([^)]*\\)[^{]*\\{([\\s\\S]*?)\\n\\}`);
    const match = declaration.exec(source);
    if (!match) {
        throw new Error(`could not locate the definition of ${fetcherName} in app/lib/api.ts`);
    }
    return match[1];
}

function mutatingHelpersUsedBy(source: string, fetcherName: string): string[] {
    return MUTATING_HELPERS.filter((helper) => bodyOf(source, fetcherName).includes(helper));
}

function withSmuggledCallSite(call: string): string {
    return API_CODE.replace(
        'export function getContactDeals(',
        `export function smuggledWrite(id: number, cookie: string | null) {\n    return ${call};\n}\nexport function getContactDeals(`,
    );
}

describe('the error-swallowing cookie wrapper stays off write paths', () => {
    it('carries the read-only constraint in its name, at every call site', () => {
        expect(API_CODE).toContain(`async function ${SWALLOWING_READ_WRAPPER}<T>(`);
        expect(API_CODE).not.toMatch(/function safeWithCookie\b/);
    });

    it('resolves every call site, so the guard below cannot silently shrink', () => {
        const { names, unresolved } = fetchersRoutedThrough(API_CODE, SWALLOWING_READ_WRAPPER);
        const references = API_CODE.split(SWALLOWING_READ_WRAPPER).length - 1;

        expect(unresolved, 'only the wrapper declaration should resolve to no fetcher').toBe(1);
        expect(names.length).toBe(references - 1);
        expect(names.length, 'migrating a read off the wrapper must decrement this').toBe(
            SWALLOWING_READ_CALL_SITES,
        );
    });

    it('routes no mutation through the swallowing read wrapper', () => {
        const { names } = fetchersRoutedThrough(API_CODE, SWALLOWING_READ_WRAPPER);

        for (const fetcher of names) {
            expect(
                mutatingHelpersUsedBy(API_CODE, fetcher),
                `${fetcher} is a write and must not swallow its failure`,
            ).toEqual([]);
        }
    });

    it('sees a write smuggled in without a type argument', () => {
        const injected = withSmuggledCallSite(
            `${SWALLOWING_READ_WRAPPER}((init) => replaceContactTags(id, [1], init), cookie)`,
        );
        const { names } = fetchersRoutedThrough(injected, SWALLOWING_READ_WRAPPER);

        expect(names).toContain('replaceContactTags');
        expect(mutatingHelpersUsedBy(injected, 'replaceContactTags')).not.toEqual([]);
    });

    it('sees a write smuggled in behind a renamed lambda parameter', () => {
        const injected = withSmuggledCallSite(
            `${SWALLOWING_READ_WRAPPER}<Types.Tag>((options) => replaceContactTags(id, [1], options), cookie)`,
        );
        const { names } = fetchersRoutedThrough(injected, SWALLOWING_READ_WRAPPER);

        expect(names).toContain('replaceContactTags');
        expect(mutatingHelpersUsedBy(injected, 'replaceContactTags')).not.toEqual([]);
    });

    it('keeps no SSR write wrapper that cannot carry a CSRF header', () => {
        for (const wrapper of DELETED_SSR_WRITE_WRAPPERS) {
            expect(API_CODE).not.toContain(wrapper);
        }
    });
});
