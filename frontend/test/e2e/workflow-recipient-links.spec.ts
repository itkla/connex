import { randomUUID } from "node:crypto";

import { expect, test } from "@playwright/test";

import { csrfBootstrap, seeder } from "./support/api";
import { runFixture } from "./support/fixtures";
import { useLocale } from "./support/locale";
import { message } from "./support/messages";

function isRecord(value: unknown): value is Record<string, unknown> {
    return typeof value === "object" && value !== null && !Array.isArray(value);
}

for (const locale of ["en", "ja"] as const) {
    test(`${locale}: a real workflow trigger creates a same-day follow-up and a linked alert @mobile`, async ({ page }, testInfo) => {
        const fixture = runFixture(testInfo.project.name);
        await useLocale(page, locale);
        const csrf = await csrfBootstrap(page.request);
        const seed = seeder(page.request, fixture.workspaceId, csrf);
        const memberResponse = await page.request.get("/api/auth/me");
        expect(memberResponse.ok()).toBeTruthy();
        const member: unknown = await memberResponse.json();
        if (!isRecord(member) || typeof member.id !== "number") throw new Error("Expected current member");
        const suffix = randomUUID();
        const contactName = `Workflow source ${suffix}`;
        const taskTitle = `Follow up ${suffix}`;
        const notificationTitle = `Review contact ${suffix}`;
        const rule = await seed.post("/api/rules", {
            name: `Recipient check ${suffix}`, enabled: true, recordType: "person", executionMode: "user",
            trigger: { type: "entity_change", events: ["person.created"] },
            condition: { match: "all", conditions: [{ type: "field", field: "name", op: "contains", value: suffix }] },
            actions: [
                { type: "create_task", title: taskTitle, targetUserId: member.id, dueInDays: 0 },
                { type: "notify", title: notificationTitle, body: "Check the company", targetUserId: member.id },
            ],
        });
        if (typeof rule.id !== "number") throw new Error("Expected workflow rule id");
        try {
            const before = new Date().toISOString().slice(0, 10);
            const person = await seed.post("/api/persons", { name: contactName });
            if (typeof person.id !== "number") throw new Error("Expected contact id");
            await expect.poll(async () => {
                const response = await page.request.get(`/api/persons/${person.id}/tasks`, {
                    headers: { "X-Workspace-Id": String(fixture.workspaceId) },
                });
                expect(response.ok()).toBeTruthy();
                const tasks: unknown = await response.json();
                return Array.isArray(tasks) ? tasks.filter(isRecord).find((task) => task.description === taskTitle) : null;
            }).toEqual(expect.objectContaining({ assignedToId: member.id, dueDate: before }));
            await page.goto("/notifications");
            const alert = page.getByRole("button").filter({ has: page.getByRole("heading", { name: notificationTitle, exact: true }) });
            await expect(alert).toContainText(contactName);
            const notificationId = await alert.getAttribute("data-notification-row");
            if (!notificationId || !/^\d+$/.test(notificationId)) throw new Error("Expected notification id");
            await alert.click();
            await expect(page).toHaveURL(new RegExp(`/records/contacts/${person.id}$`));
            await expect(page.getByRole("heading", { name: contactName, exact: true }).first()).toBeVisible();
            await seed.post(`/api/notifications/${notificationId}/unread`, {});
            await page.goto("/dashboard");
            await page.getByRole("button", { name: new RegExp(message(locale, "notifications", "Notifications.bellLabel")) }).click();
            const bellAlert = page.getByRole("menuitem").filter({ hasText: notificationTitle });
            await expect(bellAlert).toContainText(contactName);
            await bellAlert.click();
            await expect(page).toHaveURL(new RegExp(`/records/contacts/${person.id}$`));
            await seed.post(`/api/notifications/${notificationId}/dismiss`, {});
        } finally {
            const response = await page.request.delete(`/api/rules/${rule.id}`, {
                headers: { "X-Workspace-Id": String(fixture.workspaceId), [csrf.headerName]: csrf.token },
            });
            expect(response.ok()).toBeTruthy();
        }
    });
}
