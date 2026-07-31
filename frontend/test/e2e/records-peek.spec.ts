import { expect, test } from "@playwright/test";
import { runFixture } from "./support/fixtures";
import { useLocale } from "./support/locale";
import { message } from "./support/messages";

test.describe("records browse and peek", () => {
    test("row opens a peek, peek opens the detail, and back restores the list context", async ({ page }, testInfo) => {
        const contact = runFixture(testInfo.project.name).contacts.peek;
        const listUrl = "/records/contacts?view=table&sort=name&dir=asc&page=1&size=10";
        await page.setViewportSize({ width: 1024, height: 420 });
        await page.goto(listUrl);

        const row = page.getByRole("row").filter({ hasText: contact.name });
        await expect(row).toBeVisible();
        await row.getByRole("checkbox").check();
        await row.scrollIntoViewIfNeeded();
        const listScrollTop = await page.locator("[data-app-main]").evaluate((main) => main.scrollTop);
        await row.getByRole("cell", { name: contact.name, exact: true }).click();

        const peek = page.locator("[data-record-peek]");
        await expect(peek).toBeVisible();
        await expect(peek.getByText(contact.name).first()).toBeVisible();
        await expect(page).toHaveURL(new RegExp(`peek=person%3A${contact.id}`));
        const peekUrl = page.url();

        await peek.getByRole("button", { name: "Open record" }).click();
        await expect(page).toHaveURL(new RegExp(`/records/contacts/${contact.id}`));
        await expect(page.getByRole("heading", { name: contact.name }).first()).toBeVisible();
        expect(new URL(page.url()).searchParams.get("returnTo")).toContain(`peek=person%3A${contact.id}`);

        await page.getByRole("link", { name: message("en", "contacts", "ContactsPage.allContacts") }).first().click();
        await expect(page).toHaveURL(peekUrl);
        await expect(peek).toBeVisible();
        await expect(row.getByRole("checkbox")).toBeChecked();
        await expect.poll(
            () => page.locator("[data-app-main]").evaluate((main) => main.scrollTop),
        ).toBe(listScrollTop);

        await peek.getByRole("button", { name: "Close" }).click();
        await expect(peek).toBeHidden();
        await expect(row).toBeFocused();
    });

    test("contact, company, and deal details expose evidence freshness", async ({ page }, testInfo) => {
        const fixture = runFixture(testInfo.project.name);

        await page.goto(`/records/contacts/${fixture.contacts.peek.id}`);
        const contactEvidence = page.getByRole("region", {
            name: message("en", "records", "RelationshipEvidence.title"),
        });
        await expect(contactEvidence).toBeVisible();
        await expect(contactEvidence).not.toContainText(
            message("en", "records", "RelationshipEvidence.unavailable"),
        );
        await expect(contactEvidence.getByRole("link")).toHaveAttribute(
            "href",
            `/activity/activities/${fixture.activities.evidence.id}`,
        );
        await expect(contactEvidence.getByText(/^Calculated /)).toBeVisible();

        await page.goto(`/records/companies/${fixture.companies.primary.id}`);
        const companyEvidence = page.getByRole("region", {
            name: message("en", "records", "RelationshipEvidence.title"),
        });
        await expect(companyEvidence).toBeVisible();
        await expect(companyEvidence).not.toContainText(
            message("en", "records", "RelationshipEvidence.unavailable"),
        );
        await expect(companyEvidence.getByText(/^Calculated /)).toBeVisible();

        await page.goto(`/records/deals/${fixture.deals.primary.id}`);
        const dealRisk = page.getByRole("region", {
            name: message("en", "deals", "DealRisk.panelTitle"),
        });
        await expect(dealRisk).toBeVisible();
        await expect(dealRisk).not.toContainText(
            message("en", "deals", "DealRisk.unavailable"),
        );
        await expect(dealRisk.getByText(/^Assessed /)).toBeVisible();
    });

    test("the localized detail return control preserves list state on mobile @mobile-only", async ({ page }, testInfo) => {
        const contact = runFixture(testInfo.project.name).contacts.peek;
        const listUrl = `/records/contacts?view=table&q=${encodeURIComponent(contact.name)}`;
        await useLocale(page, "ja");
        await page.setViewportSize({ width: 412, height: 915 });
        await page.goto(`/records/contacts/${contact.id}?${new URLSearchParams({ returnTo: listUrl })}`);

        await expect(page.getByRole("heading", { name: contact.name }).first()).toBeVisible();
        await page.locator("[data-app-main]").evaluate((main) => main.scrollTo({ top: 900 }));
        const sticky = page.locator("[data-record-sticky-context]");
        await expect(sticky).toHaveAttribute("data-visible", "true");
        await expect(sticky.getByRole("link", {
            name: message("ja", "contacts", "ContactsPage.allContacts"),
        })).toBeVisible();

        await sticky.getByRole("link", {
            name: message("ja", "contacts", "ContactsPage.allContacts"),
        }).click();
        await expect(page).toHaveURL(new RegExp(`q=${encodeURIComponent(contact.name)}`));
    });
});
