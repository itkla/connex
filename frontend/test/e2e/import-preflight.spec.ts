import { expect, test } from "@playwright/test";

import { runFixture } from "./support/fixtures";
import { useLocale } from "./support/locale";
import { message } from "./support/messages";

test.describe("CSV duplicate review", () => {
    test("shows exact candidates and explicitly links an owned contact", async ({ page }, testInfo) => {
        const fixture = runFixture(testInfo.project.name);
        const contact = fixture.contacts.peek;
        const email = `${contact.name.toLowerCase().replace(/[^a-z0-9]+/g, ".")}@acme-rocket.example.com`;

        await page.goto("/records/contacts?view=table");
        await page.getByRole("button", { name: "More actions" }).click();
        await page.getByRole("menuitem", { name: "Import" }).click();

        await expect(page.getByRole("heading", { name: "Import contacts" })).toBeVisible();
        await page.locator('input[type="file"]').setInputFiles({
            name: "duplicate-contact.csv",
            mimeType: "text/csv",
            buffer: Buffer.from(`Name,Email\n${contact.name},${email}\n`, "utf8"),
        });
        await page.getByRole("button", { name: "Next" }).click();

        await expect(page.getByText(contact.name).last()).toBeVisible();
        await expect(page.getByText("Exact identity")).toBeVisible();
        await expect(page.getByText("Same email")).toBeVisible();
        await page.getByRole("button", { name: "Use record" }).click();

        await expect(page.getByRole("button", { name: "Selected" })).toBeDisabled();
        await expect(page.getByText(`Linked: ${contact.name}`)).toBeVisible();
        await expect(page.getByText(
            "Your match action or row links changed. Refresh the review before importing.",
        )).toBeVisible();
        await expect(page.getByRole("button", { name: "Import", exact: true })).toBeDisabled();
        await page.getByRole("button", { name: "Refresh review" }).click();
        await expect(page.getByText(
            "Your match action or row links changed. Refresh the review before importing.",
        )).toBeHidden();
        await expect(page.getByRole("button", { name: "Import", exact: true })).toBeEnabled();
        await expect(page.getByText("Match", { exact: true })).toBeVisible();
    });

    test("includes an exact company dependency in contact import review", async ({ page }, testInfo) => {
        const fixture = runFixture(testInfo.project.name);
        const contactName = `Company dependency ${Date.now()}`;

        await page.goto("/records/contacts?view=table");
        await page.getByRole("button", { name: "More actions" }).click();
        await page.getByRole("menuitem", { name: "Import" }).click();
        await page.locator('input[type="file"]').setInputFiles({
            name: "company-dependency.csv",
            mimeType: "text/csv",
            buffer: Buffer.from(
                `Name,Company\n${contactName},${fixture.companyName}\n`,
                "utf8",
            ),
        });
        await page.getByRole("button", { name: "Next" }).click();

        const candidates = page.getByLabel("Possible existing records");
        await expect(candidates.getByText(fixture.companyName)).toBeVisible();
        await expect(candidates.getByText("Company", { exact: true })).toBeVisible();
        await expect(candidates.getByText("Exact name")).toBeVisible();
        await expect(candidates.getByText("Same normalized name")).toBeVisible();
        await expect(page.getByRole("button", { name: "Use record" })).toHaveCount(0);
        await expect(page.getByRole("button", { name: "Import", exact: true })).toBeEnabled();
    });
});

test.describe("manual duplicate review", () => {
    test("requires acknowledgement before creating a contact with an exact identity", async ({ page }, testInfo) => {
        const fixture = runFixture(testInfo.project.name);
        const contact = fixture.contacts.peek;
        const email = `${contact.name.toLowerCase().replace(/[^a-z0-9]+/g, ".")}@acme-rocket.example.com`;

        await page.goto("/dashboard");
        await page.getByRole("button", { name: "New", exact: true }).click();
        await page.getByRole("option", { name: "New contact" }).click();
        await page.getByLabel("Name", { exact: true }).fill(contact.name);
        await page.getByLabel("Email", { exact: true }).fill(email);

        await expect(page.getByRole("region", { name: "Possible duplicates" })).toBeVisible();
        await expect(page.getByText("Exact identity")).toBeVisible();
        await expect(page.getByText("Same email")).toBeVisible();
        await expect(page.getByRole("button", { name: "Create", exact: true })).toBeDisabled();

        await page.getByRole("checkbox", {
            name: "I reviewed these matches and still want to create a new record.",
        }).check();
        await expect(page.getByRole("button", { name: "Create", exact: true })).toBeEnabled();
    });

    test("runs the same exact-identity gate on OCR-populated contact fields", async ({ page }, testInfo) => {
        const fixture = runFixture(testInfo.project.name);
        const contact = fixture.contacts.peek;
        const email = `${contact.name.toLowerCase().replace(/[^a-z0-9]+/g, ".")}@acme-rocket.example.com`;

        await page.route("**/api/business-cards/availability", async (route) => {
            await route.fulfill({
                status: 200,
                contentType: "application/json",
                body: JSON.stringify({ scanning: true, importing: true }),
            });
        });
        await page.route("**/api/business-cards/scan", async (route) => {
            await route.fulfill({
                status: 200,
                contentType: "application/json",
                body: JSON.stringify({
                    fields: {
                        name: { value: contact.name, confidence: 0.99 },
                        email: { value: email, confidence: 0.99 },
                        phone: { value: null, confidence: null },
                        title: { value: null, confidence: null },
                    },
                    company: { value: null, confidence: null, matchedCompanyId: null },
                    warnings: [],
                }),
            });
        });

        await page.goto("/dashboard");
        await page.getByRole("button", { name: "New", exact: true }).click();
        await page.getByRole("option", { name: "New contact" }).click();
        const scan = page.getByRole("button", { name: "Scan business card" });
        await expect(scan).toBeVisible();
        await scan.locator("..").locator('input[type="file"]').last().setInputFiles({
            name: "contact-card.png",
            mimeType: "image/png",
            buffer: Buffer.from(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Y9ZKmcAAAAASUVORK5CYII=",
                "base64",
            ),
        });

        await expect(page.getByText("Details added. Review them before creating the contact.")).toBeVisible();
        await expect(page.getByRole("region", { name: "Possible duplicates" })).toBeVisible();
        await expect(page.getByText("Same email")).toBeVisible();
        await expect(page.getByRole("button", { name: "Create from card" })).toBeDisabled();
        await expect(page.getByRole("button", { name: /Use existing contact/ })).toBeVisible();
        await expect(page.getByRole("button", { name: /Create separate contact/ })).toBeVisible();
        await page.getByRole("button", { name: /Use existing contact/ }).click();
        await expect(page.getByRole("button", { name: "Attach card" })).toBeEnabled();

        await page.getByRole("button", { name: "Remove business card" }).click();
        await scan.locator("..").locator('input[type="file"]').last().setInputFiles({
            name: "replacement-card.png",
            mimeType: "image/png",
            buffer: Buffer.from(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Y9ZKmcAAAAASUVORK5CYII=",
                "base64",
            ),
        });

        await expect(page.getByRole("button", { name: "Create from card" })).toBeDisabled();
        await expect(page.getByRole("button", { name: /Use existing contact/ })).toBeVisible();
        await page.getByRole("button", { name: /Use existing contact/ }).click();
        await expect(page.getByRole("button", { name: "Attach card" })).toBeEnabled();
    });

    test("reviews an OCR-created company before business-card import", async ({ page }, testInfo) => {
        const fixture = runFixture(testInfo.project.name);

        await page.route("**/api/business-cards/availability", async (route) => {
            await route.fulfill({
                status: 200,
                contentType: "application/json",
                body: JSON.stringify({ scanning: true, importing: true }),
            });
        });
        await page.route("**/api/business-cards/scan", async (route) => {
            await route.fulfill({
                status: 200,
                contentType: "application/json",
                body: JSON.stringify({
                    fields: {
                        name: { value: "Fresh OCR Contact", confidence: 0.99 },
                        email: { value: null, confidence: null },
                        phone: { value: null, confidence: null },
                        title: { value: null, confidence: null },
                    },
                    company: {
                        value: fixture.companyName,
                        confidence: 0.99,
                        matchedCompanyId: null,
                    },
                    warnings: [],
                }),
            });
        });

        await page.goto("/dashboard");
        await page.getByRole("button", { name: "New", exact: true }).click();
        await page.getByRole("option", { name: "New contact" }).click();
        const scan = page.getByRole("button", { name: "Scan business card" });
        await expect(scan).toBeVisible();
        await scan.locator("..").locator('input[type="file"]').last().setInputFiles({
            name: "company-card.png",
            mimeType: "image/png",
            buffer: Buffer.from(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Y9ZKmcAAAAASUVORK5CYII=",
                "base64",
            ),
        });

        await page.getByRole("radio", { name: "Create a new company" }).check();
        await expect(page.getByRole("region", { name: "Possible duplicates" })).toBeVisible();
        await expect(page.getByText(fixture.companyName).last()).toBeVisible();
        await expect(page.getByRole("button", { name: "Create from card" })).toBeDisabled();
    });

});

for (const locale of ["en", "ja"] as const) {
    const language = locale === "en" ? "English" : "Japanese";
    test(`attaches a mobile camera scan in ${language} to the exact existing contact @mobile-only`, async ({ page }, testInfo) => {
        const fixture = runFixture(testInfo.project.name);
        const contact = fixture.contacts.peek;
        const email = `${contact.name.toLowerCase().replace(/[^a-z0-9]+/g, ".")}@acme-rocket.example.com`;
        const importBodies: string[] = [];

        await useLocale(page, locale);
        await page.route("**/api/business-cards/availability", async (route) => {
            await route.fulfill({
                status: 200,
                contentType: "application/json",
                body: JSON.stringify({ scanning: true, importing: true }),
            });
        });
        await page.route("**/api/business-cards/scan", async (route) => {
            await route.fulfill({
                status: 200,
                contentType: "application/json",
                body: JSON.stringify({
                    fields: {
                        name: { value: contact.name, confidence: 0.99 },
                        email: { value: email, confidence: 0.99 },
                        phone: { value: null, confidence: null },
                        title: { value: null, confidence: null },
                    },
                    company: { value: null, confidence: null, matchedCompanyId: null },
                    warnings: [],
                }),
            });
        });
        await page.route("**/api/business-cards/import/reservation", async (route) => {
            await route.fulfill({
                status: 200,
                contentType: "application/json",
                body: JSON.stringify({ expiresAt: "2026-12-31T00:00:00Z" }),
            });
        });
        await page.route("**/api/business-cards/import", async (route) => {
            importBodies.push(route.request().postDataBuffer()?.toString("utf8") ?? "");
            await route.fulfill({
                status: 200,
                contentType: "application/json",
                body: JSON.stringify({
                    contact: { id: contact.id, name: contact.name, email },
                    attachment: {
                        id: 991,
                        fileName: "contact-card.png",
                        url: `/api/attachments/991`,
                        size: 68,
                        contentType: "image/png",
                    },
                    company: null,
                    disposition: "reused",
                }),
            });
        });

        await page.goto("/dashboard");
        const mobileNavigation = page.getByRole("navigation", {
            name: message(locale, "common", "MobileNav.barLabel"),
            exact: true,
        });
        await mobileNavigation.getByRole("button", {
            name: message(locale, "actions", "Actions.quickCreate.trigger"),
            exact: true,
        }).click();
        await page.getByRole("option", {
            name: message(locale, "actions", "Actions.create.person"),
            exact: true,
        }).click();

        const cameraInput = page.locator('input[type="file"][capture="environment"]');
        await expect(cameraInput).toHaveAttribute("accept", /image/);
        await cameraInput.setInputFiles({
            name: "contact-card.png",
            mimeType: "image/png",
            buffer: Buffer.from(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Y9ZKmcAAAAASUVORK5CYII=",
                "base64",
            ),
        });

        await expect(page.getByText(message(
            locale,
            "contacts",
            "ContactsNewContactDialog.cardScanReady",
        ))).toBeVisible();
        await expect(page.getByRole("region", {
            name: message(locale, "common", "DuplicateWarning.heading"),
        })).toBeVisible();
        await page.getByRole("button", {
            name: new RegExp(message(locale, "common", "DuplicateWarning.useExisting")),
        }).click();
        await page.getByRole("button", {
            name: message(locale, "contacts", "ContactsNewContactDialog.attachCard"),
            exact: true,
        }).click();

        await expect(page.getByText(message(
            locale,
            "actions",
            "Actions.feedback.businessCardAttached",
        ))).toBeVisible();
        expect(importBodies).toHaveLength(1);
        expect(importBodies[0]).toContain('"type":"existing"');
        expect(importBodies[0]).toContain(`"personId":${contact.id}`);
        expect(importBodies[0]).toMatch(/"duplicateReviewToken":"[0-9a-f]{64}"/);
        expect(importBodies[0].match(/duplicateReviewToken/g)).toHaveLength(1);
        expect(importBodies[0]).toContain('"type":"none"');
    });
}

test.describe("manual business-card fallback", () => {
    test("keeps mobile card import usable when automatic reading is unavailable @mobile-only", async ({ page }) => {
        await useLocale(page, "ja");
        await page.route("**/api/business-cards/availability", async (route) => {
            await route.fulfill({
                status: 200,
                contentType: "application/json",
                body: JSON.stringify({ scanning: false, importing: true }),
            });
        });

        await page.goto("/dashboard");
        const mobileNavigation = page.getByRole("navigation", {
            name: message("ja", "common", "MobileNav.barLabel"),
            exact: true,
        });
        await mobileNavigation.getByRole("button", {
            name: message("ja", "actions", "Actions.quickCreate.trigger"),
            exact: true,
        }).click();
        await page.getByRole("option", {
            name: message("ja", "actions", "Actions.create.person"),
            exact: true,
        }).click();
        await page.locator('input[type="file"][capture="environment"]').setInputFiles({
            name: "manual-card.png",
            mimeType: "image/png",
            buffer: Buffer.from(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Y9ZKmcAAAAASUVORK5CYII=",
                "base64",
            ),
        });

        await expect(page.getByText(message(
            "ja",
            "contacts",
            "ContactsNewContactDialog.cardManualOnly",
        ))).toBeVisible();
        await page.getByLabel(message(
            "ja",
            "contacts",
            "ContactsNewContactDialog.name",
        ), { exact: true }).fill("手動入力 太郎");
        await expect(page.getByRole("button", {
            name: message("ja", "contacts", "ContactsNewContactDialog.createFromCard"),
            exact: true,
        })).toBeEnabled();
    });
});
