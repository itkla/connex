import { expect, test } from "@playwright/test";
import type { Page } from "@playwright/test";

import { runFixture } from "./support/fixtures";
import { useLocale } from "./support/locale";
import { message } from "./support/messages";

function activityCsv(email: string, sourceId: string, subject: string): Buffer {
    return Buffer.from([
        "Occurred At,Participant Email,Source ID,Subject,Type,Notes",
        `2026-01-12 09:30:00,${email},${sourceId},${subject},meeting,Evaluator context`,
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

async function uploadForReview(
    page: Page,
    locale: "en" | "ja",
    csv: Buffer,
    fileName: string,
) {
    await openHistoryImport(page, locale);
    await page.locator('input[type="file"]').setInputFiles({
        name: fileName,
        mimeType: "text/csv",
        buffer: csv,
    });
    await page.getByRole("button", {
        name: message(locale, "importExport", "importExport.history.review"),
    }).click();
}

for (const locale of ["en", "ja"] as const) {
    test(`completes the Wave 2 evaluator journey in ${locale} @mobile`, async ({ page }, testInfo) => {
        const fixture = runFixture(testInfo.project.name);
        const target = locale === "en"
            ? fixture.contacts.ambiguityPrimary
            : fixture.contacts.ambiguityPrimaryJa;
        const alternate = locale === "en"
            ? fixture.contacts.ambiguitySecondary
            : fixture.contacts.ambiguitySecondaryJa;
        const ambiguityEmail = locale === "en"
            ? fixture.ambiguityEmail
            : fixture.ambiguityEmailJa;
        const sourceId =
            `wave2-evaluator-${locale}-${testInfo.project.name}-${testInfo.retry}`;
        const importSubject = locale === "ja" ? "評価用の履歴ミーティング" : "Evaluator history meeting";
        const actionSubject = locale === "ja" ? "評価後のフォローアップ" : "Post-insight follow-up";
        const fileName = `wave2-evaluator-${locale}.csv`;
        const csv = activityCsv(ambiguityEmail, sourceId, importSubject);

        await useLocale(page, locale);
        await uploadForReview(page, locale, csv, fileName);

        await expect(page.getByLabel(
            `${message(locale, "importExport", "importExport.history.counts.review")}: 1`,
        )).toBeVisible();
        await expect(page.getByLabel(
            `${message(locale, "importExport", "importExport.history.counts.ready")}: 0`,
        )).toBeVisible();
        const candidateList = page.getByRole("list", {
            name: message(locale, "importExport", "importExport.history.candidateLabel"),
        });
        await expect(candidateList.getByText(
            target.name,
            { exact: true },
        )).toBeVisible();
        await expect(candidateList.getByText(
            alternate.name,
            { exact: true },
        )).toBeVisible();
        const importButton = page.getByRole("button", {
            name: message(locale, "importExport", "importExport.history.import"),
        });
        await expect(importButton).toBeDisabled();

        const targetCandidate = candidateList.getByRole("listitem")
            .filter({ hasText: target.name });
        await targetCandidate.getByRole("button", {
            name: message(locale, "importExport", "importExport.history.useContact"),
        }).click();
        await expect(page.getByText(
            message(locale, "importExport", "importExport.history.stale"),
        )).toBeVisible();
        await page.getByRole("button", {
            name: message(locale, "importExport", "importExport.history.refresh"),
        }).click();

        await expect(page.getByLabel(
            `${message(locale, "importExport", "importExport.history.counts.ready")}: 1`,
        )).toBeVisible();
        await expect(page.getByText(
            message(locale, "importExport", "importExport.history.matchedContact")
                .replace("{name}", target.name),
        )).toBeVisible();
        await importButton.click();
        await expect(page.getByText(
            message(locale, "importExport", "importExport.history.doneTitle"),
        )).toBeVisible();

        await page.goto(`/records/contacts/${target.id}`);
        const evidence = page.getByRole("region", {
            name: message(locale, "records", "RelationshipEvidence.title"),
        });
        await expect(evidence).toBeVisible();
        await expect(evidence).not.toContainText(
            message(locale, "records", "RelationshipEvidence.unavailable"),
        );
        await expect(evidence).not.toContainText(
            message(locale, "records", "RelationshipEvidence.noHistory"),
        );
        await expect(page.getByText(importSubject).first()).toBeVisible();

        await page.getByRole("button", {
            name: message(locale, "contacts", "ContactsNewActivityDialog.triggerSr"),
        }).first().click();
        await page.getByLabel(
            message(locale, "contacts", "ContactsNewActivityDialog.subject"),
        ).fill(actionSubject);
        await page.getByRole("button", {
            name: message(locale, "contacts", "ContactsNewActivityDialog.log"),
            exact: true,
        }).click();
        await expect(page.getByText(
            message(locale, "contacts", "ContactsNewActivityDialog.toastActivityLogged"),
        ).first()).toBeVisible();
        await expect(page.getByText(actionSubject).first()).toBeVisible({ timeout: 15_000 });

        for (let replay = 1; replay <= 2; replay += 1) {
            await uploadForReview(page, locale, csv, fileName);
            await expect(page.getByLabel(
                `${message(locale, "importExport", "importExport.history.counts.review")}: 1`,
            )).toBeVisible();
            await targetCandidate.getByRole("button", {
                name: message(locale, "importExport", "importExport.history.useContact"),
            }).click();
            await page.getByRole("button", {
                name: message(locale, "importExport", "importExport.history.refresh"),
            }).click();
            await expect(page.getByLabel(
                `${message(locale, "importExport", "importExport.history.counts.imported")}: 1`,
            )).toBeVisible();
            await expect(page.getByLabel(
                `${message(locale, "importExport", "importExport.history.counts.ready")}: 0`,
            )).toBeVisible();
            await expect(page.getByLabel(
                `${message(locale, "importExport", "importExport.history.counts.review")}: 0`,
            )).toBeVisible();
            await expect(page.getByRole("button", {
                name: message(locale, "importExport", "importExport.history.import"),
            })).toBeDisabled();
        }
    });
}
