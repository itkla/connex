import { expect, test } from "@playwright/test";
import type { Page } from "@playwright/test";

import { runFixture } from "./support/fixtures";
import { useLocale } from "./support/locale";
import { message } from "./support/messages";

function contactEmail(name: string): string {
    return `${name.toLowerCase().replace(/[^a-z0-9]+/g, ".")}@acme-rocket.example.com`;
}

function activityCsv(email: string, sourceId: string, subject: string): Buffer {
    return Buffer.from([
        "Occurred At,Participant Email,Source ID,Subject,Type,Notes",
        `2026-01-12 09:30:00,${email},${sourceId},${subject},meeting,Historical context`,
        "",
    ].join("\n"), "utf8");
}

async function openHistoryImport(page: Page, locale: "en" | "ja") {
    await page.goto("/settings/data");
    await page.getByRole("button", {
        name: message(locale, "workspace", "WorkspaceData.historyAction"),
    }).click();
    await expect(page.getByRole("heading", {
        name: message(locale, "importExport", "importExport.history.title"),
    })).toBeVisible();
}

for (const locale of ["en", "ja"] as const) {
    test(`imports and replay-skips exact interaction history in ${locale} @mobile`, async ({ page }, testInfo) => {
        const fixture = runFixture(testInfo.project.name);
        const contact = fixture.contacts.activity;
        const attemptKey = `${locale}-${testInfo.project.name}-r${testInfo.retry}`;
        const sourceId = `e2e-history-${attemptKey}`;
        const subject = locale === "ja" ? `導入前の打ち合わせ ${attemptKey}` : `Pre-launch discovery ${attemptKey}`;
        const csv = activityCsv(contactEmail(contact.name), sourceId, subject);

        await useLocale(page, locale);
        await openHistoryImport(page, locale);
        await page.locator('input[type="file"]').setInputFiles({
            name: `history-${locale}.csv`,
            mimeType: "text/csv",
            buffer: csv,
        });
        await page.getByRole("button", {
            name: message(locale, "importExport", "importExport.history.review"),
        }).click();

        await expect(page.getByLabel(
            `${message(locale, "importExport", "importExport.history.counts.ready")}: 1`,
        )).toBeVisible();
        await page.getByRole("button", {
            name: message(locale, "importExport", "importExport.history.import"),
        }).click();
        await expect(page.getByText(
            message(locale, "importExport", "importExport.history.doneTitle"),
        )).toBeVisible();

        await page.goto(`/records/contacts/${contact.id}`);
        await expect(page.getByText(subject)).toBeVisible();

        await openHistoryImport(page, locale);
        await page.locator('input[type="file"]').setInputFiles({
            name: `history-${locale}.csv`,
            mimeType: "text/csv",
            buffer: csv,
        });
        await page.getByRole("button", {
            name: message(locale, "importExport", "importExport.history.review"),
        }).click();
        await expect(page.getByLabel(
            `${message(locale, "importExport", "importExport.history.counts.imported")}: 1`,
        )).toBeVisible();
        await expect(page.getByLabel(
            `${message(locale, "importExport", "importExport.history.counts.ready")}: 0`,
        )).toBeVisible();
        await expect(page.getByLabel(
            `${message(locale, "importExport", "importExport.history.counts.invalid")}: 0`,
        )).toBeVisible();
        await expect(page.getByRole("button", {
            name: message(locale, "importExport", "importExport.history.import"),
        })).toBeDisabled();
    });
}
