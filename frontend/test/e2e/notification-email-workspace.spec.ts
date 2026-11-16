import { randomUUID } from "node:crypto";
import { expect, test } from "@playwright/test";

import { activeWorkspaceId, csrfBootstrap, registerUser, seeder } from "./support/api";
import { useLocale } from "./support/locale";

function isRecord(value: unknown): value is Record<string, unknown> {
    return typeof value === "object" && value !== null && !Array.isArray(value);
}

test.use({ storageState: { cookies: [], origins: [] } });

for (const locale of ["en", "ja"] as const) {
    test(`${locale}: an emailed notification selects workspace A while viewing B @mobile`, async ({ page }) => {
        await useLocale(page, locale);
        const suffix = randomUUID().replace(/-/g, "").slice(0, 12);
        await registerUser(page.request, {
            username: `wfmail_${suffix}`, password: `WorkflowMail!${suffix}A1`,
            email: `wfmail_${suffix}@example.invalid`, displayName: `Mail origin ${suffix}`,
        });
        const origin = await activeWorkspaceId(page.request);
        const csrf = await csrfBootstrap(page.request);
        const headers = { "X-Workspace-Id": String(origin), [csrf.headerName]: csrf.token };
        const seed = seeder(page.request, origin, csrf);
        const rule = await seed.post("/api/rules", {
            name: `Email destination ${suffix}`, enabled: true, recordType: "person", executionMode: "user",
            trigger: { type: "entity_change", events: ["person.created"] },
            condition: { match: "all", conditions: [{ type: "field", field: "name", op: "contains", value: suffix }] },
            actions: [{ type: "notify", title: `Review ${suffix}` }],
        });
        if (typeof rule.id !== "number") throw new Error("Expected workflow id");
        try {
            const sourceName = `Mail source ${suffix}`;
            const person = await seed.post("/api/persons", { name: sourceName });
            if (typeof person.id !== "number") throw new Error("Expected contact id");
            let notificationId = 0;
            await expect.poll(async () => {
                const response = await page.request.get("/api/notifications?status=all&types=rule&page=1&size=100");
                expect(response.ok()).toBeTruthy();
                const body: unknown = await response.json();
                if (!isRecord(body) || !Array.isArray(body.items)) throw new Error("Expected notification page");
                const notification = body.items.filter(isRecord).find((item) => item.sourceId === person.id);
                notificationId = typeof notification?.id === "number" ? notification.id : 0;
                return notificationId;
            }).toBeGreaterThan(0);
            const destinationName = `Mail other workspace ${suffix}`;
            const created = await page.request.post("/api/workspaces", { headers, data: { name: destinationName } });
            expect(created.ok(), await created.text()).toBeTruthy();
            const destination: unknown = await created.json();
            if (!isRecord(destination) || typeof destination.id !== "number") throw new Error("Expected workspace id");
            expect(await activeWorkspaceId(page.request)).toBe(destination.id);
            await page.goto("/dashboard");
            const selectedOrigin = page.waitForResponse((response) =>
                new URL(response.url()).pathname === `/api/workspaces/${origin}/switch`
                && response.request().method() === "POST");

            await page.goto(`/notifications?open=${notificationId}`);

            expect((await selectedOrigin).status()).toBe(200);
            await expect(page).toHaveURL(new RegExp(`/records/contacts/${person.id}$`));
            await expect(page.getByRole("heading", { name: sourceName, exact: true }).first()).toBeVisible();
            expect(await activeWorkspaceId(page.request)).toBe(origin);
        } finally {
            const removed = await page.request.delete(`/api/rules/${rule.id}`, { headers });
            expect(removed.ok()).toBeTruthy();
        }
    });
}
