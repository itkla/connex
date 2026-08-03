import { expect, test, type Page } from "@playwright/test";
import { E2E_BASE_URL } from "../../playwright.config";
import { runFixture } from "./support/fixtures";

test.describe("record archive and restore", () => {
    test("contact archive is reversible and replaces delete", async ({ page }, testInfo) => {
        const fixture = runFixture(testInfo.project.name);
        const contact = fixture.contacts.archive;
        await ensureActive(page, `/api/persons/${contact.id}/restore`, fixture.workspaceId);
        try {
            const listUrl = `/records/contacts?view=table&q=${encodeURIComponent(contact.name)}`;
            await page.goto(listUrl);

            const row = page.getByRole("row").filter({ hasText: contact.name });
            await expect(row).toBeVisible();
            await row.hover();
            await row.getByRole("button", { name: `Actions for ${contact.name}` }).click();
            await expect(page.getByRole("menuitem", { name: "Delete", exact: true })).toHaveCount(0);
            await page.getByRole("menuitem", { name: "Archive", exact: true }).click();

            const archiveDialog = page.getByRole("dialog");
            await expect(archiveDialog.getByRole("heading", { name: "Archive contact" })).toBeVisible();
            await expect(archiveDialog).toContainText(contact.name);
            await archiveDialog.getByRole("button", { name: "Archive", exact: true }).click();

            await expect(page.getByText("Contact archived")).toBeVisible();
            await expect(row).toBeHidden();

            const scope = page.getByRole("group", { name: "Show active or archived contacts" });
            await scope.getByRole("button", { name: /^Archived \(/ }).click();
            const archivedRow = page.getByRole("row").filter({ hasText: contact.name });
            await expect(archivedRow).toBeVisible();
            await archivedRow.hover();
            await archivedRow.getByRole("button", { name: `Actions for ${contact.name}` }).click();
            await expect(page.getByRole("menuitem", { name: "Delete", exact: true })).toHaveCount(0);
            await expect(page.getByRole("menuitem", { name: "Quick edit", exact: true })).toHaveCount(0);
            await page.getByRole("menuitem", { name: "Restore", exact: true }).click();

            const restoreDialog = page.getByRole("dialog");
            await expect(restoreDialog.getByRole("heading", { name: "Restore contact" })).toBeVisible();
            await restoreDialog.getByRole("button", { name: "Restore", exact: true }).click();

            await expect(page.getByText("Contact restored")).toBeVisible();
            await expect(archivedRow).toBeHidden();
            await scope.getByRole("button", { name: "Active", exact: true }).click();
            await expect(page.getByRole("row").filter({ hasText: contact.name })).toBeVisible();
        } finally {
            await ensureActive(page, `/api/persons/${contact.id}/restore`, fixture.workspaceId);
        }
    });

    test("company archive and restore work in Japanese at mobile width @mobile-only", async ({ page }, testInfo) => {
        await page.context().addCookies([
            { name: "NEXT_LOCALE", value: "ja", url: E2E_BASE_URL },
        ]);
        const fixture = runFixture(testInfo.project.name);
        const company = fixture.companies.archive;
        await ensureActive(page, `/api/companies/${company.id}/restore`, fixture.workspaceId);
        try {
            const listUrl = `/records/companies?view=grid&q=${encodeURIComponent(company.name)}`;
            await page.goto(listUrl);
            await expect(page.locator("html")).toHaveAttribute("lang", "ja");

            const row = page.getByRole("listitem").filter({ hasText: company.name }).first();
            await expect(row).toBeVisible();
            await row.getByRole("button", { name: `${company.name}のアクション` }).click();
            await expect(page.getByRole("menuitem", { name: "削除", exact: true })).toHaveCount(0);
            await page.getByRole("menuitem", { name: "アーカイブ", exact: true }).click();

            const archiveDialog = page.getByRole("dialog");
            await expect(archiveDialog.getByRole("heading", { name: "会社 をアーカイブ" })).toBeVisible();
            await expect(archiveDialog).toContainText(company.name);
            await archiveDialog.getByRole("button", { name: "アーカイブ", exact: true }).click();

            await expect(page.getByText("会社をアーカイブしました")).toBeVisible();
            await expect(row).toBeHidden();

            await page.getByRole("button", { name: "レコードを絞り込んで並び替える" }).click();
            const filterSheet = page.getByRole("dialog", { name: "フィルターと並び替え" });
            const scope = filterSheet.getByRole("group", {
                name: "有効な会社とアーカイブ済みの会社を切り替える",
            });
            await scope.getByRole("button", { name: /^アーカイブ済み/ }).click();
            await filterSheet.getByRole("button", { name: "完了", exact: true }).click();
            const archivedRow = page.getByRole("listitem").filter({ hasText: company.name }).first();
            await expect(archivedRow).toBeVisible();
            await archivedRow.getByRole("button", { name: `${company.name}のアクション` }).click();
            await expect(page.getByRole("menuitem", { name: "削除", exact: true })).toHaveCount(0);
            await expect(page.getByRole("menuitem", { name: "クイック編集", exact: true })).toHaveCount(0);
            await page.getByRole("menuitem", { name: "復元", exact: true }).click();

            const restoreDialog = page.getByRole("dialog");
            await expect(restoreDialog.getByRole("heading", { name: "会社 を復元" })).toBeVisible();
            await restoreDialog.getByRole("button", { name: "復元", exact: true }).click();

            await expect(page.getByText("会社を復元しました")).toBeVisible();
            await expect(archivedRow).toBeHidden();
            await page.getByRole("button", { name: "レコードを絞り込んで並び替える" }).click();
            await scope.getByRole("button", { name: "有効", exact: true }).click();
            await filterSheet.getByRole("button", { name: "完了", exact: true }).click();
            await expect(page.getByRole("listitem").filter({ hasText: company.name }).first()).toBeVisible();
        } finally {
            await ensureActive(page, `/api/companies/${company.id}/restore`, fixture.workspaceId);
        }
    });
});

async function ensureActive(page: Page, restorePath: string, workspaceId: number): Promise<void> {
    const csrfResponse = await page.request.get("/api/auth/csrf");
    expect(csrfResponse.status()).toBe(200);
    const csrf: unknown = await csrfResponse.json();
    if (
        typeof csrf !== "object"
        || csrf === null
        || !("token" in csrf)
        || typeof csrf.token !== "string"
        || !("headerName" in csrf)
        || typeof csrf.headerName !== "string"
    ) {
        throw new Error("Invalid CSRF bootstrap response");
    }
    const response = await page.request.post(restorePath, {
        headers: {
            "X-Workspace-Id": String(workspaceId),
            [csrf.headerName]: csrf.token,
        },
        data: {},
    });
    expect([200, 404]).toContain(response.status());
}
