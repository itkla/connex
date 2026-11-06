import { readFileSync } from "node:fs";
import path from "node:path";
import ts from "typescript";
import { describe, expect, it } from "vitest";

import { locales } from "@/i18n/config";
import { message } from "../e2e/support/messages";

type MessageReference = { namespace: string; key: string };

function stringProperty(node: ts.ObjectLiteralExpression, name: string): string | undefined {
    for (const property of node.properties) {
        if (!ts.isPropertyAssignment(property)
            || !(ts.isIdentifier(property.name) || ts.isStringLiteral(property.name))
            || property.name.text !== name) continue;
        if (!ts.isStringLiteral(property.initializer)) throw new Error(`Expected a literal ${name} in the locale spec`);
        return property.initializer.text;
    }
    return undefined;
}

function specMessageReferences(): MessageReference[] {
    const file = path.resolve(process.cwd(), "test/e2e/public-entry-locale.spec.ts");
    const source = ts.createSourceFile(file, readFileSync(file, "utf8"), ts.ScriptTarget.Latest, true, ts.ScriptKind.TS);
    const references = new Map<string, MessageReference>();
    let lookupCount = 0;
    let tableLookupCount = 0;
    let tableRowCount = 0;
    const add = (namespace: string, key: string) => references.set(`${namespace}:${key}`, { namespace, key });

    const visit = (node: ts.Node) => {
        if (ts.isObjectLiteralExpression(node)) {
            const namespace = stringProperty(node, "namespace");
            const key = stringProperty(node, "heading");
            if (namespace !== undefined || key !== undefined) {
                if (namespace === undefined || key === undefined) throw new Error("Incomplete message reference in the locale spec");
                add(namespace, key);
                tableRowCount += 1;
            }
        }
        if (ts.isCallExpression(node) && ts.isIdentifier(node.expression) && node.expression.text === "message") {
            lookupCount += 1;
            const [, namespace, key] = node.arguments;
            if (namespace && key && ts.isStringLiteral(namespace) && ts.isStringLiteral(key)) {
                add(namespace.text, key.text);
            } else if (namespace && key && ts.isPropertyAccessExpression(namespace) && ts.isPropertyAccessExpression(key)
                && namespace.name.text === "namespace" && key.name.text === "heading"
                && namespace.expression.getText(source) === key.expression.getText(source)) {
                tableLookupCount += 1;
            } else {
                throw new Error("Unrecognized message lookup in the locale spec");
            }
        }
        ts.forEachChild(node, visit);
    };
    visit(source);
    if (lookupCount === 0 || (tableLookupCount > 0 && tableRowCount === 0)) {
        throw new Error("The locale spec's message references were not checked");
    }
    return [...references.values()];
}

const references = specMessageReferences();

it("finds the public entry spec's message references", () => {
    expect(references.length).toBeGreaterThan(0);
});

describe.each(locales)("public entry spec messages in %s", (locale) => {
    it.each(references)("$namespace / $key exists as a string", ({ namespace, key }) => {
        expect(message(locale, namespace, key)).not.toBe("");
    });
});
