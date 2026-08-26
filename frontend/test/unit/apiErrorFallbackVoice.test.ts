import { readFileSync } from "node:fs";
import { join } from "node:path";

import ts from "typescript";
import { beforeAll, describe, expect, it } from "vitest";

const scopedComponents = [
    "app/components/records/companies/CompaniesBrowser.tsx",
    "app/components/records/companies/EditCompanySheet.tsx",
    "app/components/records/contacts/ChangeCompanyDialog.tsx",
    "app/components/records/contacts/ContactCard.tsx",
    "app/components/records/deals/DealsBrowser.tsx",
    "app/components/records/deals/EditDealSheet.tsx",
    "app/components/records/pipelines/EditPipelineSheet.tsx",
] as const;

const fallbackTitles = [
    {
        catalog: "companies",
        key: "CompaniesBrowser.toastSelectAllFailed",
        en: "Couldn't select all matching companies",
        ja: "一致するすべての会社を選択できませんでした",
    },
    {
        catalog: "companies",
        key: "CompaniesBrowser.toastSaveFailed",
        en: "Couldn't save the company changes",
        ja: "会社の変更を保存できませんでした",
    },
    {
        catalog: "companies",
        key: "CompaniesBrowser.toastArchiveFailed",
        en: "Couldn't archive the selected companies",
        ja: "選択した会社をアーカイブできませんでした",
    },
    {
        catalog: "companies",
        key: "CompaniesBrowser.toastRestoreFailed",
        en: "Couldn't restore the selected companies",
        ja: "選択した会社を復元できませんでした",
    },
    {
        catalog: "companies",
        key: "CompaniesEditSheet.toastSaveFailed",
        en: "Couldn't save the company",
        ja: "会社を保存できませんでした",
    },
    {
        catalog: "contacts",
        key: "ContactsChangeCompanyDialog.toastFailedUpdate",
        en: "Couldn't change the company",
        ja: "会社を変更できませんでした",
    },
    {
        catalog: "contacts",
        key: "ContactsCard.toastFailedSave",
        en: "Couldn't save the contact",
        ja: "連絡先を保存できませんでした",
    },
    {
        catalog: "deals",
        key: "DealsBrowser.toastSelectAllFailed",
        en: "Couldn't select all matching deals",
        ja: "一致するすべての案件を選択できませんでした",
    },
    {
        catalog: "deals",
        key: "DealsBrowser.failedToCreateDeal",
        en: "Couldn't create the deal",
        ja: "案件を作成できませんでした",
    },
    {
        catalog: "deals",
        key: "DealsBrowser.failedToSave",
        en: "Couldn't save the deal changes",
        ja: "案件の変更を保存できませんでした",
    },
    {
        catalog: "deals",
        key: "DealsBrowser.failedToDelete",
        en: "Couldn't delete the selected deals",
        ja: "選択した案件を削除できませんでした",
    },
    {
        catalog: "deals",
        key: "DealsBrowser.failedToUpdateStatus",
        en: "Couldn't update the deal status",
        ja: "案件のステータスを更新できませんでした",
    },
    {
        catalog: "deals",
        key: "DealsEditSheet.failedToSave",
        en: "Couldn't save the deal",
        ja: "案件を保存できませんでした",
    },
    {
        catalog: "pipelines",
        key: "PipelinesEditSheet.failedToSave",
        en: "Couldn't save the pipeline",
        ja: "パイプラインを保存できませんでした",
    },
] as const;

function isRecord(value: unknown): value is Record<string, unknown> {
    return typeof value === "object" && value !== null && !Array.isArray(value);
}

function literalFallbackKeys(expression: ts.Expression, file: string): string[] {
    if (ts.isStringLiteralLike(expression)) return [expression.text];
    if (ts.isParenthesizedExpression(expression)) return literalFallbackKeys(expression.expression, file);
    if (ts.isConditionalExpression(expression)) {
        return [
            ...literalFallbackKeys(expression.whenTrue, file),
            ...literalFallbackKeys(expression.whenFalse, file),
        ];
    }
    throw new Error(`${file} passes a non-literal fallback to useApiErrorToast`);
}

function fallbackKeysFor(file: string): string[] {
    const source = readFileSync(join(process.cwd(), file), "utf8");
    const sourceFile = ts.createSourceFile(file, source, ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
    const reporters = new Map<string, string>();
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
            if (!namespace || !ts.isStringLiteralLike(namespace)) {
                throw new Error(`${file} passes a non-literal namespace to useApiErrorToast`);
            }
            reporters.set(node.name.text, namespace.text);
        }
        ts.forEachChild(node, collectReporters);
    };
    collectReporters(sourceFile);
    if (reporters.size !== 1) throw new Error(`${file} must bind useApiErrorToast exactly once`);

    const fallbacks: string[] = [];
    const collectFallbacks = (node: ts.Node) => {
        if (ts.isCallExpression(node) && ts.isIdentifier(node.expression)) {
            const namespace = reporters.get(node.expression.text);
            if (namespace !== undefined) {
                const fallback = node.arguments[1];
                if (!fallback) throw new Error(`${file} calls ${node.expression.text} without a fallback`);
                for (const key of literalFallbackKeys(fallback, file)) {
                    fallbacks.push(`${namespace}.${key}`);
                }
            }
        }
        ts.forEachChild(node, collectFallbacks);
    };
    collectFallbacks(sourceFile);
    return fallbacks;
}

function messageFor(locale: "en" | "ja", catalog: string, key: string): string {
    const parsed: unknown = JSON.parse(
        readFileSync(join(process.cwd(), "messages", locale, `${catalog}.json`), "utf8"),
    );
    let current = parsed;
    for (const segment of key.split(".")) {
        if (!isRecord(current) || !(segment in current)) {
            throw new Error(`Missing ${locale} message ${catalog}.${key}`);
        }
        current = current[segment];
    }
    if (typeof current !== "string") throw new Error(`${catalog}.${key} is not a string`);
    return current;
}

describe("API error fallback titles", () => {
    beforeAll(() => {
        const approved = fallbackTitles.map(({ key }) => key).sort();
        const derived = scopedComponents.flatMap(fallbackKeysFor).sort();

        expect(fallbackTitles).toHaveLength(14);
        expect(approved).toEqual([...new Set(approved)]);
        expect(derived).toEqual(approved);
    });

    it.each(fallbackTitles)("keeps $key in the approved EN and JA voice", ({ catalog, key, en, ja }) => {
        const english = messageFor("en", catalog, key);

        expect(english).toBe(en);
        expect(english).not.toMatch(/^(?:Failed to|Could not)\b/);
        expect(messageFor("ja", catalog, key)).toBe(ja);
    });
});
