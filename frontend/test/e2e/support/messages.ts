import { readFileSync } from "node:fs";
import path from "node:path";
import type { Locale } from "@/i18n/config";

const MESSAGES_DIR = path.resolve(__dirname, "../../../messages");

function isRecord(value: unknown): value is Record<string, unknown> {
    return typeof value === "object" && value !== null;
}

/**
 * Reads a shipped translation so locale assertions stay tied to the catalogue rather than to a
 * copy of the string pasted into a spec, which would keep passing after the copy changed.
 *
 * @param locale catalogue to read, e.g. `"ja"`
 * @param namespace message file under `messages/<locale>`, e.g. `"auth"`
 * @param key dotted path within that file, e.g. `"AuthLogin.title"`
 * @returns the translated string
 * @throws when the namespace, the key, or a string value at that key is missing
 */
export function message(locale: Locale, namespace: string, key: string): string {
    const file = path.join(MESSAGES_DIR, locale, `${namespace}.json`);
    const catalogue: unknown = JSON.parse(readFileSync(file, "utf8"));
    const value = key
        .split(".")
        .reduce<unknown>((node, segment) => (isRecord(node) ? node[segment] : undefined), catalogue);
    if (typeof value !== "string") {
        throw new Error(`No string message at ${namespace}.${key} in ${file}`);
    }
    return value;
}
