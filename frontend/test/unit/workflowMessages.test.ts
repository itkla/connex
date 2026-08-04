import { readdirSync, readFileSync } from "node:fs";
import { basename, join, relative, resolve } from "node:path";

import ts from "typescript";
import { describe, expect, it } from "vitest";

const WORKFLOW_COMPONENTS_ROOT = resolve("app/components/settings/workflows");
const LOCALES = ["en", "ja"] as const;
const NAMESPACE_FILES = {
    WorkflowOperations: "workflow-operations.json",
    WorkspaceRules: "workspace.json",
    WorkspaceWorkflows: "workspace.json",
} as const;

type MessageNamespace = keyof typeof NAMESPACE_FILES;
type MessageValue = string | { [key: string]: MessageValue };
type MessageUsage = {
    file: string;
    key: string;
    namespace: MessageNamespace;
};

function isMessageTree(value: unknown): value is { [key: string]: MessageValue } {
    return typeof value === "object" && value !== null && !Array.isArray(value);
}

function isMessageNamespace(value: string): value is MessageNamespace {
    return value in NAMESPACE_FILES;
}

function workflowSourceFiles(directory: string): string[] {
    return readdirSync(directory, { withFileTypes: true })
        .flatMap((entry) => {
            const path = join(directory, entry.name);
            if (entry.isDirectory()) return workflowSourceFiles(path);
            return entry.isFile() && /\.tsx?$/.test(entry.name) ? [path] : [];
        })
        .sort();
}

function translationBindings(sourceFile: ts.SourceFile): Map<string, MessageNamespace> {
    const bindings = new Map<string, MessageNamespace>();
    const visit = (node: ts.Node) => {
        if (
            ts.isVariableDeclaration(node)
            && ts.isIdentifier(node.name)
            && node.initializer
            && ts.isCallExpression(node.initializer)
            && ts.isIdentifier(node.initializer.expression)
            && node.initializer.expression.text === "useTranslations"
        ) {
            const namespace = node.initializer.arguments[0];
            if (namespace && ts.isStringLiteralLike(namespace) && isMessageNamespace(namespace.text)) {
                bindings.set(node.name.text, namespace.text);
            }
        }
        ts.forEachChild(node, visit);
    };
    visit(sourceFile);
    return bindings;
}

function literalMessageKeys(expression: ts.Expression): string[] {
    if (ts.isStringLiteralLike(expression)) return [expression.text];
    if (ts.isParenthesizedExpression(expression)) return literalMessageKeys(expression.expression);
    if (ts.isConditionalExpression(expression)) {
        return [
            ...literalMessageKeys(expression.whenTrue),
            ...literalMessageKeys(expression.whenFalse),
        ];
    }
    return [];
}

function messageUsages(file: string): MessageUsage[] {
    const source = readFileSync(file, "utf8");
    const sourceFile = ts.createSourceFile(file, source, ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
    const bindings = translationBindings(sourceFile);
    const usages: MessageUsage[] = [];
    const visit = (node: ts.Node) => {
        if (ts.isCallExpression(node) && ts.isIdentifier(node.expression)) {
            const namespace = bindings.get(node.expression.text);
            const argument = node.arguments[0];
            if (namespace && argument) {
                for (const key of literalMessageKeys(argument)) {
                    usages.push({
                        file: relative(WORKFLOW_COMPONENTS_ROOT, file),
                        key,
                        namespace,
                    });
                }
            }
        }
        ts.forEachChild(node, visit);
    };
    visit(sourceFile);
    return usages;
}

function uniqueMessageUsages(): MessageUsage[] {
    const usages = workflowSourceFiles(WORKFLOW_COMPONENTS_ROOT).flatMap(messageUsages);
    return [...new Map(usages.map((usage) => [`${usage.namespace}.${usage.key}`, usage])).values()]
        .sort((left, right) => `${left.namespace}.${left.key}`.localeCompare(`${right.namespace}.${right.key}`));
}

function readNamespace(locale: (typeof LOCALES)[number], namespace: MessageNamespace): { [key: string]: MessageValue } {
    const path = resolve(`messages/${locale}/${NAMESPACE_FILES[namespace]}`);
    const parsed: unknown = JSON.parse(readFileSync(path, "utf8"));
    if (!isMessageTree(parsed) || !(namespace in parsed) || !isMessageTree(parsed[namespace])) {
        throw new Error(`${namespace} is missing from messages/${locale}/${basename(path)}`);
    }
    return parsed[namespace];
}

function hasMessage(tree: { [key: string]: MessageValue }, key: string): boolean {
    let value: MessageValue = tree;
    for (const segment of key.split(".")) {
        if (!isMessageTree(value) || !(segment in value)) return false;
        value = value[segment];
    }
    return typeof value === "string";
}

describe("workflow component localization", () => {
    it.each(LOCALES)("resolves every literal translation key in %s", (locale) => {
        const usages = uniqueMessageUsages();
        const namespaces = new Map<MessageNamespace, { [key: string]: MessageValue }>();
        const missing = usages.flatMap((usage) => {
            const messages = namespaces.get(usage.namespace) ?? readNamespace(locale, usage.namespace);
            namespaces.set(usage.namespace, messages);
            return hasMessage(messages, usage.key)
                ? []
                : [`${usage.file}: ${usage.namespace}.${usage.key}`];
        });

        expect(usages.length).toBeGreaterThan(0);
        expect(missing).toEqual([]);
    });
});
