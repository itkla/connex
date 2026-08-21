import { readFileSync } from "node:fs";
import { join } from "node:path";

import { describe, expect, it } from "vitest";

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
    it.each(fallbackTitles)("keeps $key in the approved EN and JA voice", ({ catalog, key, en, ja }) => {
        const english = messageFor("en", catalog, key);

        expect(english).toBe(en);
        expect(english).not.toMatch(/^(?:Failed to|Could not)\b/);
        expect(messageFor("ja", catalog, key)).toBe(ja);
    });
});
