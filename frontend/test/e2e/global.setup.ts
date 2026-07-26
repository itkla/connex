import { mkdirSync, writeFileSync } from "node:fs";
import { expect, request, test as setup } from "@playwright/test";
import { E2E_ARTIFACT_DIR, E2E_BASE_URL, RUN_FIXTURE_PATH, STORAGE_STATE_PATH } from "../../playwright.config";
import { csrfBootstrap, defaultWorkspaceId, registerUser, seeder, type RunFixture } from "./support/api";

/**
 * Provisions an isolated tenant for this run: registers a fresh user (which, under the dev
 * profile, also logs in, creates a workspace, and sets the JSESSIONID + connex_workspace
 * cookies), persists the browser storage state, and seeds the records the flow specs assert
 * against. Every run gets its own workspace, so runs are isolated and rerunnable.
 */
setup("provision tenant and seed records", async () => {
    const api = await request.newContext({ baseURL: E2E_BASE_URL });

    const probe = await api.get("/auth/login").catch(() => null);
    if (!probe || !probe.ok()) {
        throw new Error(
            `Frontend is not reachable at ${E2E_BASE_URL} (and its /api proxy needs the backend on :8080). `
            + "Start the stack first: backend `bash gradlew bootRun` (dev profile) and frontend `pnpm dev`.",
        );
    }

    const runId = `${Date.now().toString(36)}${Math.floor(Math.random() * 1296).toString(36)}`;
    const contactNames = {
        peek: `Peek Target ${runId}`,
        edit: `Edit Target ${runId}`,
        activity: `Activity Target ${runId}`,
        search: `Searchable Sable ${runId}`,
    } as const;
    const fixture: RunFixture = {
        username: `e2e_${runId}`,
        password: `E2eHarness!${runId}A1`,
        email: `e2e_${runId}@example.com`,
        workspaceId: 0,
        contacts: {
            peek: { id: 0, name: contactNames.peek },
            edit: { id: 0, name: contactNames.edit },
            activity: { id: 0, name: contactNames.activity },
            search: { id: 0, name: contactNames.search },
        },
        companyName: `Acme Rocket Co ${runId}`,
    };

    await registerUser(api, fixture);
    fixture.workspaceId = await defaultWorkspaceId(api);

    const csrf = await csrfBootstrap(api);
    const seed = seeder(api, fixture.workspaceId, csrf);

    const company = await seed.post("/api/companies", {
        name: fixture.companyName,
        website: "https://acme-rocket.example.com",
        industry: "Aerospace",
    });

    for (const key of Object.keys(contactNames) as (keyof typeof contactNames)[]) {
        const name = contactNames[key];
        const person = await seed.post("/api/persons", {
            name,
            email: `${name.toLowerCase().replace(/[^a-z0-9]+/g, ".")}@acme-rocket.example.com`,
            title: "Engineer",
            companyId: company.id,
        });
        fixture.contacts[key] = { id: Number(person.id), name };
    }

    const pipeline = await seed.post("/api/pipelines", { name: "E2E Pipeline" });
    const stage = await seed.post(`/api/pipelines/${pipeline.id}/stages`, {
        name: "Qualifying",
        position: 0,
        success: false,
        failure: false,
    });
    for (const [index, value] of [120000, 45000, 8000].entries()) {
        await seed.post("/api/deals", {
            name: `E2E Deal ${index + 1} ${runId}`,
            value,
            actualValue: 0,
            currency: "USD",
            pipeline: pipeline.id,
            stage: stage.id,
            company: company.id,
        });
    }

    mkdirSync(E2E_ARTIFACT_DIR, { recursive: true });
    await api.storageState({ path: STORAGE_STATE_PATH });
    writeFileSync(RUN_FIXTURE_PATH, JSON.stringify(fixture, null, 2));

    const me = await api.get("/api/auth/me");
    expect(me.status()).toBe(200);
    await api.dispose();
});
