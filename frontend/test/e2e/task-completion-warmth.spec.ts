import { expect, test, type APIRequestContext } from "@playwright/test";
import { csrfBootstrap, seeder } from "./support/api";
import { runFixture } from "./support/fixtures";
import { message } from "./support/messages";

function isRecord(value: unknown): value is Record<string, unknown> {
    return typeof value === "object" && value !== null;
}

async function evidence(api: APIRequestContext, personId: number): Promise<Record<string, unknown>> {
    const response = await api.get(`/api/scoring/contacts/${personId}/evidence`);
    expect(response.ok()).toBeTruthy();
    const body: unknown = await response.json();
    if (!isRecord(body) || !isRecord(body.temperature)) {
        throw new Error("Expected relationship evidence with a warmth reading");
    }
    return body.temperature;
}

test("planned follow-ups leave warmth unchanged until completion and reopen removes their evidence", async ({ page }, testInfo) => {
    const fixture = runFixture(testInfo.project.name);
    const csrf = await csrfBootstrap(page.request);
    const seed = seeder(page.request, fixture.workspaceId, csrf);
    const name = `Task warmth ${Date.now().toString(36)}`;
    const person = await seed.post("/api/persons", { name });
    if (typeof person.id !== "number") throw new Error("Expected contact id");
    const currentUserResponse = await page.request.get("/api/auth/me");
    expect(currentUserResponse.ok()).toBeTruthy();
    const user: unknown = await currentUserResponse.json();
    if (typeof user !== "object" || user === null || !("id" in user) || typeof user.id !== "number") {
        throw new Error("Expected current member");
    }
    const before = await evidence(page.request, person.id);
    const task = await seed.post("/api/tasks", {
        description: "Follow up after the introduction",
        assignedToId: user.id,
        personId: person.id,
        dueDate: "2030-12-31",
    });
    if (typeof task.id !== "number") throw new Error("Expected task id");
    const planned = await evidence(page.request, person.id);
    for (const key of ["score", "lastTouchAt", "goesColdAt", "touchCount"]) {
        expect(planned[key]).toEqual(before[key]);
    }
    expect(planned.score).toBe(0);
    await seed.post(`/api/tasks/${task.id}/complete`, {});
    const completed = await evidence(page.request, person.id);
    expect(completed.score).toBeGreaterThan(0);
    expect(completed.touchCount).toBe(1);
    expect(completed.lastTouchAt).toBeTruthy();
    await page.goto(`/records/contacts/${person.id}`);
    await page.getByRole("button", {
        name: new RegExp(message("en", "records", "RelationshipEvidence.openEvidenceNamed")
            .replace(/[.*+?^${}()|[\]\\]/g, "\\$&").replace("\\{band\\}", ".+")),
    }).click();
    const panel = page.getByRole("region", { name: message("en", "records", "RelationshipEvidence.title") });
    await expect(panel.locator(`a[href="/activity/tasks/${task.id}"]`)).toBeVisible();
    await expect(panel).not.toContainText(/warmth-v\d|with model/);
    await seed.post(`/api/tasks/${task.id}/move`, { status: "todo", position: 0 });
    const reopened = await evidence(page.request, person.id);
    expect(reopened.score).toBe(0);
    expect(reopened).not.toHaveProperty("lastTouchAt");
    expect(reopened.touchCount).toBe(0);
});
