import { expect, test } from "@playwright/test";

import { runFixture } from "./support/fixtures";

test.describe("CSV duplicate review", () => {
    test("shows exact candidates and explicitly links an owned contact", async ({ page }) => {
        const fixture = runFixture();
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

    test("includes an exact company dependency in contact import review", async ({ page }) => {
        const fixture = runFixture();
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
    test("requires acknowledgement before creating a contact with an exact identity", async ({ page }) => {
        const fixture = runFixture();
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

    test("runs the same exact-identity gate on OCR-populated contact fields", async ({ page }) => {
        const fixture = runFixture();
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
    });

    test("reviews an OCR-created company before business-card import", async ({ page }) => {
        const fixture = runFixture();

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
