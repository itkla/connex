import { mkdirSync, writeFileSync } from "node:fs";
import path from "node:path";
import { expect, request, test as setup } from "@playwright/test";
import {
    E2E_ARTIFACT_DIR,
    E2E_BASE_URL,
    runFixturePath,
    storageStatePath,
    tenantScopeForProject,
} from "../../playwright.config";
import {
    csrfBootstrap,
    activeWorkspaceId,
    registerUser,
    seeder,
    type RunFixture,
    type SeededRecord,
    type Seeder,
} from "./support/api";

async function createAmbiguityPair(
    seed: Seeder,
    companyId: unknown,
    email: string,
    primary: SeededRecord,
    secondary: SeededRecord,
): Promise<{ primary: SeededRecord; secondary: SeededRecord }> {
    const createdPrimary = await seed.post("/api/persons", {
        name: primary.name,
        email,
        title: "Evaluator",
        companyId,
    });
    const review = await seed.post("/api/duplicate-preflight/persons", {
        name: secondary.name,
        emails: [email],
        phones: [],
    });
    if (typeof review.reviewToken !== "string") {
        throw new Error("Duplicate preflight did not return an evaluator review token");
    }
    const createdSecondary = await seed.post("/api/persons", {
        name: secondary.name,
        email,
        title: "Evaluator",
        companyId,
        duplicateReviewToken: review.reviewToken,
    });
    return {
        primary: { id: Number(createdPrimary.id), name: primary.name },
        secondary: { id: Number(createdSecondary.id), name: secondary.name },
    };
}

/**
 * Provisions an isolated tenant for this run: registers a fresh user (which, under the dev
 * profile, also logs in, creates a workspace, and sets the JSESSIONID + connex_workspace
 * cookies), persists the browser storage state, and seeds the records the flow specs assert
 * against. Every run gets its own workspace, so runs are isolated and rerunnable.
 */
setup("provision tenant and seed records", async ({}, testInfo) => {
    setup.setTimeout(240_000);
    const tenantScope = tenantScopeForProject(testInfo.project.name);
    const api = await request.newContext({ baseURL: E2E_BASE_URL });

    const probe = await api.get("/auth/login").catch(() => null);
    if (!probe || !probe.ok()) {
        throw new Error(
            `Frontend is not reachable at ${E2E_BASE_URL} (and its /api proxy needs the backend on :8080). `
            + "Start the stack first: backend `bash gradlew bootRun` (dev profile) and frontend `pnpm dev`.",
        );
    }

    const runId = `${tenantScope[0]}${Date.now().toString(36)}${Math.floor(Math.random() * 1296).toString(36)}`;
    const contactNames = {
        peek: `Peek Target ${runId}`,
        edit: `Edit Target ${runId}`,
        activity: `Activity Target ${runId}`,
        search: `Searchable Sable ${runId}`,
        archive: `Archive Target ${runId}`,
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
            archive: { id: 0, name: contactNames.archive },
            ambiguityPrimary: { id: 0, name: `Evaluator Primary ${runId}` },
            ambiguitySecondary: { id: 0, name: `Evaluator Secondary ${runId}` },
            ambiguityPrimaryJa: { id: 0, name: `Evaluator Primary JA ${runId}` },
            ambiguitySecondaryJa: { id: 0, name: `Evaluator Secondary JA ${runId}` },
        },
        companies: {
            primary: { id: 0, name: `Acme Rocket Co ${runId}` },
            archive: { id: 0, name: `Archive Company ${runId}` },
        },
        deals: {
            primary: { id: 0, name: `E2E Deal 1 ${runId}` },
        },
        activities: {
            evidence: { id: 0, name: `Evidence Call ${runId}` },
        },
        companyName: `Acme Rocket Co ${runId}`,
        ambiguityEmail: `evaluator.${runId}@acme-rocket.example.com`,
        ambiguityEmailJa: `evaluator.ja.${runId}@acme-rocket.example.com`,
    };

    await registerUser(api, fixture);
    fixture.workspaceId = await activeWorkspaceId(api);

    const csrf = await csrfBootstrap(api);
    const seed = seeder(api, fixture.workspaceId, csrf);

    const company = await seed.post("/api/companies", {
        name: fixture.companyName,
        website: "https://acme-rocket.example.com",
        industry: "Aerospace",
    });
    fixture.companies.primary.id = Number(company.id);
    const archiveCompany = await seed.post("/api/companies", {
        name: fixture.companies.archive.name,
        website: `https://archive-${runId}.example.net`,
        industry: "Manufacturing",
    });
    fixture.companies.archive.id = Number(archiveCompany.id);

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

    const ambiguityEn = await createAmbiguityPair(
        seed,
        company.id,
        fixture.ambiguityEmail,
        fixture.contacts.ambiguityPrimary,
        fixture.contacts.ambiguitySecondary,
    );
    fixture.contacts.ambiguityPrimary = ambiguityEn.primary;
    fixture.contacts.ambiguitySecondary = ambiguityEn.secondary;
    const ambiguityJa = await createAmbiguityPair(
        seed,
        company.id,
        fixture.ambiguityEmailJa,
        fixture.contacts.ambiguityPrimaryJa,
        fixture.contacts.ambiguitySecondaryJa,
    );
    fixture.contacts.ambiguityPrimaryJa = ambiguityJa.primary;
    fixture.contacts.ambiguitySecondaryJa = ambiguityJa.secondary;

    const pipeline = await seed.post("/api/pipelines", { name: "E2E Pipeline" });
    const stage = await seed.post(`/api/pipelines/${pipeline.id}/stages`, {
        name: "Qualifying",
        position: 0,
        success: false,
        failure: false,
    });
    for (const [index, value] of [120000, 45000, 8000].entries()) {
        const deal = await seed.post("/api/deals", {
            name: `E2E Deal ${index + 1} ${runId}`,
            value,
            actualValue: 0,
            currency: "USD",
            pipeline: pipeline.id,
            stage: stage.id,
            company: company.id,
        });
        if (index === 0) fixture.deals.primary.id = Number(deal.id);
    }
    const evidenceActivity = await seed.post("/api/activities", {
        type: "Call",
        subject: fixture.activities.evidence.name,
        personId: fixture.contacts.peek.id,
        dealId: fixture.deals.primary.id,
        timestamp: new Date().toISOString().slice(0, 19).replace("T", " "),
    });
    fixture.activities.evidence.id = Number(evidenceActivity.id);

    mkdirSync(path.join(E2E_ARTIFACT_DIR, tenantScope), { recursive: true });
    await api.storageState({ path: storageStatePath(tenantScope) });
    writeFileSync(runFixturePath(tenantScope), JSON.stringify(fixture, null, 2));

    const me = await api.get("/api/auth/me");
    expect(me.status()).toBe(200);
    await api.dispose();
});
