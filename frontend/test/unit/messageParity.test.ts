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
    });
});
