import { expect, test, type Page } from "@playwright/test";
import { E2E_BASE_URL } from "../../playwright.config";
import { runFixture } from "./support/fixtures";

test.describe("record archive and restore", () => {
    test("contact archive is reversible and replaces delete", async ({ page }) => {
        const contact = runFixture().contacts.archive;
        await ensureActive(page, `/api/persons/${contact.id}/restore`);
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
            await ensureActive(page, `/api/persons/${contact.id}/restore`);
        }
    });

    test("company archive and restore work in Japanese at mobile width", async ({ page }) => {
        await page.setViewportSize({ width: 390, height: 844 });
        await page.context().addCookies([
            { name: "NEXT_LOCALE", value: "ja", url: E2E_BASE_URL },
        ]);
        const company = runFixture().companies.archive;
        await ensureActive(page, `/api/companies/${company.id}/restore`);
        try {
            const listUrl = `/records/companies?view=grid&q=${encodeURIComponent(company.name)}`;
            await page.goto(listUrl);
            await expect(page.locator("html")).toHaveAttribute("lang", "ja");

            const card = page.locator("div.group").filter({ hasText: company.name }).first();
            await expect(card).toBeVisible();
            await card.getByRole("button", { name: `${company.name}のアクション` }).click();
            await expect(page.getByRole("menuitem", { name: "削除", exact: true })).toHaveCount(0);
            await page.getByRole("menuitem", { name: "アーカイブ", exact: true }).click();

            const archiveDialog = page.getByRole("dialog");
            await expect(archiveDialog.getByRole("heading", { name: "企業 をアーカイブ" })).toBeVisible();
            await expect(archiveDialog).toContainText(company.name);
            await archiveDialog.getByRole("button", { name: "アーカイブ", exact: true }).click();

            await expect(page.getByText("企業をアーカイブしました")).toBeVisible();
            await expect(card).toBeHidden();

            const scope = page.getByRole("group", {
                name: "有効な企業とアーカイブ済みの企業を切り替える",
            });
            await scope.getByRole("button", { name: /^アーカイブ済み/ }).click();
            const archivedCard = page.locator("div.group").filter({ hasText: company.name }).first();
            await expect(archivedCard).toBeVisible();
            await archivedCard.getByRole("button", { name: `${company.name}のアクション` }).click();
            await expect(page.getByRole("menuitem", { name: "削除", exact: true })).toHaveCount(0);
            await expect(page.getByRole("menuitem", { name: "クイック編集", exact: true })).toHaveCount(0);
            await page.getByRole("menuitem", { name: "復元", exact: true }).click();

            const restoreDialog = page.getByRole("dialog");
            await expect(restoreDialog.getByRole("heading", { name: "企業 を復元" })).toBeVisible();
            await restoreDialog.getByRole("button", { name: "復元", exact: true }).click();

            await expect(page.getByText("企業を復元しました")).toBeVisible();
            await expect(archivedCard).toBeHidden();
            await scope.getByRole("button", { name: "有効", exact: true }).click();
            await expect(page.locator("div.group").filter({ hasText: company.name }).first()).toBeVisible();
        } finally {
            await ensureActive(page, `/api/companies/${company.id}/restore`);
        }
    });
});

async function ensureActive(page: Page, restorePath: string): Promise<void> {
    const fixture = runFixture();
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
            "X-Workspace-Id": String(fixture.workspaceId),
            [csrf.headerName]: csrf.token,
        },
        data: {},
    });
    expect([200, 404]).toContain(response.status());
}
