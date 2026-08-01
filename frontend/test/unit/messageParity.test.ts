import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

import { locales } from "@/i18n/config";

const MESSAGES_ROOT = join(process.cwd(), "messages");
const REFERENCE_LOCALE = "en";

type MessageValue = string | { [key: string]: MessageValue };

function isMessageTree(value: MessageValue): value is { [key: string]: MessageValue } {
    return typeof value === "object" && value !== null;
}

function flattenKeys(tree: { [key: string]: MessageValue }, prefix = ""): string[] {
    return Object.entries(tree).flatMap(([key, value]) =>
        isMessageTree(value) ? flattenKeys(value, `${prefix}${key}.`) : [`${prefix}${key}`],
    );
}

function readNamespaceFile(locale: string, file: string): { [key: string]: MessageValue } {
    return JSON.parse(readFileSync(join(MESSAGES_ROOT, locale, file), "utf8")) as {
        [key: string]: MessageValue;
    };
}

function namespaceFiles(locale: string): string[] {
    return readdirSync(join(MESSAGES_ROOT, locale))
        .filter((file) => file.endsWith(".json"))
        .sort();
}

const referenceFiles = namespaceFiles(REFERENCE_LOCALE);
const translatedLocales = locales.filter((locale) => locale !== REFERENCE_LOCALE);

/**
 * Finds keys declared more than once within the same JSON object. `JSON.parse` silently keeps the
 * last occurrence, so a duplicate is invisible to any parsed comparison and can only be caught by
 * scanning the raw text.
 */
function duplicateKeys(source: string): string[] {
    const seenPerDepth = new Map<number, Set<string>>();
    const duplicates: string[] = [];
    let depth = 0;
    let inString = false;
    let escaped = false;
    let current = "";
    let captured: string | null = null;

    for (let index = 0; index < source.length; index += 1) {
        const character = source[index];
        if (inString) {
            if (escaped) escaped = false;
            else if (character === "\\") escaped = true;
            else if (character === '"') {
                inString = false;
                captured = current;
            } else current += character;
            continue;
        }
        if (character === '"') {
            inString = true;
            current = "";
            captured = null;
            continue;
        }
        if (character === ":" && captured !== null) {
            const seen = seenPerDepth.get(depth) ?? new Set<string>();
            if (seen.has(captured)) duplicates.push(`depth ${depth}: ${captured}`);
            seen.add(captured);
            seenPerDepth.set(depth, seen);
            captured = null;
            continue;
        }
        if (character === "{" || character === "[") {
            depth += 1;
            seenPerDepth.set(depth, new Set<string>());
            captured = null;
            continue;
        }
        if (character === "}" || character === "]") {
            seenPerDepth.delete(depth);
            depth -= 1;
            captured = null;
        }
    }
    return duplicates;
}

const ICU_ARGUMENT = /\{\s*([A-Za-z0-9_]+)\s*(?=[,}])/g;
const ICU_SELECTORS = new Set(["plural", "select", "selectordinal", "zero", "one", "two", "few", "many", "other"]);

/**
 * Extracts the ICU argument names a message interpolates. A brace that opens a plural or select
 * sub-message is skipped, so literal sub-message text (e.g. the `Review` in `one {Review # item}`)
 * is never mistaken for an argument.
 */
function placeholders(value: string): string[] {
    const names: string[] = [];
    for (const match of value.matchAll(ICU_ARGUMENT)) {
        const preceding = value.slice(0, match.index).trimEnd();
        const previousToken = preceding.split(/[\s{,]+/).pop() ?? "";
        if (ICU_SELECTORS.has(previousToken) || /^=\d+$/.test(previousToken)) continue;
        names.push(match[1]);
    }
    return [...new Set(names)].sort();
}

function flattenEntries(
    tree: { [key: string]: MessageValue },
    prefix = "",
): [string, string][] {
    return Object.entries(tree).flatMap(([key, value]) => {
        if (isMessageTree(value)) return flattenEntries(value, `${prefix}${key}.`);
        return typeof value === "string" ? [[`${prefix}${key}`, value] as [string, string]] : [];
    });
}

describe("message catalogue parity", () => {
    it.each(translatedLocales)("%s ships the same namespace files as en", (locale) => {
        expect(namespaceFiles(locale)).toEqual(referenceFiles);
    });

    describe.each(translatedLocales)("%s", (locale) => {
        it.each(referenceFiles)("%s has exactly the keys en defines", (file) => {
            const referenceKeys = flattenKeys(readNamespaceFile(REFERENCE_LOCALE, file)).sort();
            const translatedKeys = flattenKeys(readNamespaceFile(locale, file)).sort();

            const missing = referenceKeys.filter((key) => !translatedKeys.includes(key));
            const orphaned = translatedKeys.filter((key) => !referenceKeys.includes(key));

            expect({ missing, orphaned }).toEqual({ missing: [], orphaned: [] });
        });

        it.each(referenceFiles)("%s carries the same ICU placeholders as en", (file) => {
            const reference = new Map(flattenEntries(readNamespaceFile(REFERENCE_LOCALE, file)));
            const translated = flattenEntries(readNamespaceFile(locale, file));

            const mismatched = translated
                .filter(([key]) => reference.has(key))
                .filter(([key, value]) => {
                    const expected = placeholders(reference.get(key) as string);
                    return placeholders(value).join(",") !== expected.join(",");
                })
                .map(([key]) => key);

            expect(mismatched).toEqual([]);
        });
    });

    describe.each(locales)("%s declares no duplicate JSON keys", (locale) => {
        it.each(namespaceFiles(locale))("%s", (file) => {
            const source = readFileSync(join(MESSAGES_ROOT, locale, file), "utf8");
            expect(duplicateKeys(source)).toEqual([]);
        });
    });
});
