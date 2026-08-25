import { readFileSync, readdirSync } from "node:fs";
import { join } from "node:path";

import ts from "typescript";
import { describe, expect, it } from "vitest";

const APP_ROOT = join(process.cwd(), "app");
const MESSAGES_ROOT = join(process.cwd(), "messages");
const LOCALES = ["en", "ja"] as const;

type Fallback = { file: string; namespace: string | null; key: string };

function sourceFiles(dir: string): string[] {
    const found: string[] = [];
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
        const path = join(dir, entry.name);
        if (entry.isDirectory()) found.push(...sourceFiles(path));
        else if (entry.name.endsWith(".tsx") || entry.name.endsWith(".ts")) found.push(path);
    }
    return found;
}

function literalKeys(expression: ts.Expression): string[] {
    if (ts.isStringLiteralLike(expression)) return [expression.text];
    if (ts.isParenthesizedExpression(expression)) return literalKeys(expression.expression);
    if (ts.isConditionalExpression(expression)) {
        return [...literalKeys(expression.whenTrue), ...literalKeys(expression.whenFalse)];
    }
    return [];
}

/**
 * Every literal fallback title handed to a `useApiErrorToast` reporter, with the namespace its
 * binding qualifies it against. Derived from source so a new call site joins the gate by existing,
 * rather than by being remembered.
 */
function declaredFallbacks(): Fallback[] {
    const found: Fallback[] = [];
    for (const path of sourceFiles(APP_ROOT)) {
        const source = readFileSync(path, "utf8");
        if (!source.includes("useApiErrorToast")) continue;

        const file = path.slice(process.cwd().length + 1);
        const sourceFile = ts.createSourceFile(file, source, ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
        const reporters = new Map<string, string | null>();
        const collectReporters = (node: ts.Node) => {
            if (
                ts.isVariableDeclaration(node)
                && ts.isIdentifier(node.name)
                && node.initializer
                && ts.isCallExpression(node.initializer)
                && ts.isIdentifier(node.initializer.expression)
                && node.initializer.expression.text === "useApiErrorToast"
            ) {
                const namespace = node.initializer.arguments[0];
                if (namespace && !ts.isStringLiteralLike(namespace)) {
                    throw new Error(`${file} passes a non-literal namespace to useApiErrorToast`);
                }
                reporters.set(node.name.text, namespace ? namespace.text : null);
            }
            ts.forEachChild(node, collectReporters);
        };
        collectReporters(sourceFile);

        const collectCalls = (node: ts.Node) => {
            if (ts.isCallExpression(node) && ts.isIdentifier(node.expression) && reporters.has(node.expression.text)) {
                const fallback = node.arguments[1];
                if (fallback) {
                    for (const key of literalKeys(fallback)) {
                        found.push({ file, namespace: reporters.get(node.expression.text) ?? null, key });
                    }
                }
            }
            ts.forEachChild(node, collectCalls);
        };
        collectCalls(sourceFile);
    }
    return found;
}

/** Which catalog file holds each top-level message namespace. Namespaces are unique across files. */
function namespaceIndex(): Map<string, string> {
    const index = new Map<string, string>();
    for (const name of readdirSync(join(MESSAGES_ROOT, "en"))) {
        const parsed: unknown = JSON.parse(readFileSync(join(MESSAGES_ROOT, "en", name), "utf8"));
        if (typeof parsed !== "object" || parsed === null) continue;
        for (const namespace of Object.keys(parsed)) index.set(namespace, name);
    }
    return index;
}

function resolve(locale: string, catalog: string, path: string): string | null {
    const parsed: unknown = JSON.parse(readFileSync(join(MESSAGES_ROOT, locale, catalog), "utf8"));
    let current: unknown = parsed;
    for (const segment of path.split(".")) {
        if (typeof current !== "object" || current === null || Array.isArray(current)) return null;
        if (!(segment in current)) return null;
        current = (current as Record<string, unknown>)[segment];
    }
    return typeof current === "string" ? current : null;
}

const fallbacks = declaredFallbacks();
const catalogs = namespaceIndex();

describe("API error fallback coverage", () => {
    it("derives a fallback title from every migrated surface", () => {
        expect(fallbacks.length).toBeGreaterThan(100);
        expect(new Set(fallbacks.map((fallback) => fallback.file)).size).toBeGreaterThan(60);
    });

    it("resolves every declared fallback title in both locales", () => {
        const unresolved: string[] = [];
        for (const fallback of fallbacks) {
            const qualified = fallback.namespace === null ? fallback.key : `${fallback.namespace}.${fallback.key}`;
            const catalog = catalogs.get(qualified.split(".")[0]);
            if (!catalog) {
                unresolved.push(`${fallback.file}: no catalog holds ${qualified}`);
                continue;
            }
            for (const locale of LOCALES) {
                if (resolve(locale, catalog, qualified) === null) {
                    unresolved.push(`${fallback.file}: ${locale}/${catalog} has no ${qualified}`);
                }
            }
        }

        expect(unresolved, "a fallback that does not resolve silently degrades to the generic title").toEqual([]);
    });

    it("keeps every fallback title in the one error dialect", () => {
        const offenders: string[] = [];
        for (const fallback of fallbacks) {
            const qualified = fallback.namespace === null ? fallback.key : `${fallback.namespace}.${fallback.key}`;
            const catalog = catalogs.get(qualified.split(".")[0]);
            if (!catalog) continue;

            const english = resolve("en", catalog, qualified);
            const japanese = resolve("ja", catalog, qualified);
            if (english === null || japanese === null) continue;

            if (/^(?:Failed to|Could not)\b/.test(english)) offenders.push(`${qualified}: "${english}" is not the dialect`);
            if (english.trimEnd().endsWith(".")) offenders.push(`${qualified}: a title carries no trailing period`);
            if (english.includes("{") || japanese.includes("{")) {
                offenders.push(`${qualified}: a title with a placeholder is discarded by userMessageFor`);
            }
            if (english.trim().length === 0 || japanese.trim().length === 0) offenders.push(`${qualified}: empty title`);
        }

        expect(offenders).toEqual([]);
    });
});
