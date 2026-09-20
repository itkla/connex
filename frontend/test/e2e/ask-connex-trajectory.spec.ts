import { expect, test, type APIRequestContext } from "@playwright/test";

import {
    activeWorkspaceId,
    configureScriptedAiProvider,
    csrfBootstrap,
    registerUser,
    seeder,
} from "./support/api";
import { message } from "./support/messages";

/** Ask Connex copy, read from the shipped catalogue rather than pasted into this file. */
function copy(key: string): string {
    return message("en", "common", `AskConnex.${key}`);
}

/**
 * The literal token `e2e_send_tools_answer.json` answers to.
 *
 * It rides in the member's own words on purpose: the scripted provider is a pure function of the
 * request, so the question itself chooses which fixture answers it. It carries no digit, which is
 * what keeps `MaskingEngine` from rewriting it on the way out and leaving every turn refusing for a
 * reason no assertion names.
 */
const SELECTOR = "connex_script_browser_send_tools_answer";

/**
 * The contact the fixture's first tool call finds. The fixture searches for the distinctive half of
 * this name rather than the whole of it: the outbound leak scan refuses any payload carrying a
 * registered raw identifier, and native replay carries the model's own arguments back verbatim.
 */
const CONTACT_NAME = "Marlowe Quillfeather";
const CONTACT_QUERY = "Quillfeather";

/** The tail of the answer the fixture returns, after the record chip. */
const ANSWER_TAIL = "nothing is waiting on you there.";

/** The link text the fixture's `[the contact](record:r1)` citation resolves to. */
const ANSWER_LINK_LABEL = "the contact";

/** One viewer-safe milestone of the turn's durable tool-call rows. */
type TurnProgress = { seq: number; source: string; status: string; count: number | null };

/** The member-facing projection of one turn: its terminal state and its tool-call milestones. */
type TurnState = { status: string; terminalReason: string | null; progress: TurnProgress[] };

/** Creates the chat the member will type into, through the API the drawer itself posts to. */
async function createSession(
    api: APIRequestContext,
    workspaceId: number,
    csrf: { token: string; headerName: string },
    title: string,
): Promise<number> {
    const response = await api.post("/api/ai/assistant/sessions", {
        timeout: 120_000,
        headers: {
            "X-Workspace-Id": String(workspaceId),
            [csrf.headerName]: csrf.token,
        },
        data: { title, autoTitle: false },
    });
    expect(response.status(), await response.text()).toBe(201);
    const body = (await response.json()) as { id: number };
    expect(body.id).toBeGreaterThan(0);
    return body.id;
}

/** Reads one turn exactly as the member who asked for it reads it. */
async function readTurn(
    api: APIRequestContext,
    workspaceId: number,
    sessionId: number,
    turnId: number,
): Promise<TurnState> {
    const response = await api.get(
        `/api/ai/assistant/sessions/${sessionId}/turns/${turnId}`,
        { timeout: 120_000, headers: { "X-Workspace-Id": String(workspaceId) } },
    );
    expect(response.status(), await response.text()).toBe(200);
    return (await response.json()) as TurnState;
}

/**
 * One whole agent turn driven from a browser against the scripted provider (#1420 §6).
 *
 * The stack this runs in has the `ai-scripted-provider` profile on for every tenant, but provider
 * readiness is per organization: only the tenant this spec registers for itself gets an
 * `ai_provider_config` row, so the seeded project tenants still refuse honestly and the untouched
 * assertions in `ask-connex.spec.ts` and `ask-connex-command-center.spec.ts` stay green in the same
 * run. That is the merge gate for this mode, and the reason this spec never borrows a project
 * storage state.
 */
test.describe("Ask Connex scripted trajectory", () => {
    test("a member sends a question and reads the answer the scripted tools produced", async ({
        browser,
    }, testInfo) => {
        test.setTimeout(180_000);
        const baseURL = testInfo.project.use.baseURL;
        if (typeof baseURL !== "string") throw new Error("The E2E project requires a base URL");
        const context = await browser.newContext({
            baseURL,
            locale: "en-US",
            timezoneId: "UTC",
            reducedMotion: "reduce",
            storageState: { cookies: [], origins: [] },
        });
        try {
            const api = context.request;
            const runId = `${Date.now().toString(36)}${Math.floor(Math.random() * 1296).toString(36)}`;
            await registerUser(api, {
                username: `e2e_ai_${runId}`,
                password: `E2eHarness!${runId}A1`,
                email: `e2e_ai_${runId}@example.com`,
            });
            const workspaceId = await activeWorkspaceId(api);
            const csrf = await csrfBootstrap(api);
            await configureScriptedAiProvider(api, workspaceId, csrf, "scripted-native-stream");

            const contact = await seeder(api, workspaceId, csrf).post("/api/persons", {
                name: CONTACT_NAME,
                email: `marlowe.quillfeather.${runId}@example.com`,
                title: "Operations Lead",
            });
            const contactId = Number(contact.id);
            expect(contactId).toBeGreaterThan(0);

            const sessionId = await createSession(
                api, workspaceId, csrf, `Scripted trajectory ${runId}`);

            const page = await context.newPage();
            await page.goto(`/ask-connex/${sessionId}`);
            const composer = page.getByRole("combobox", { name: copy("composerAria") });
            await expect(composer).toBeVisible();

            const accepted = page.waitForResponse((response) =>
                response.request().method() === "POST"
                && response.url().includes(`/api/ai/assistant/sessions/${sessionId}/turns`));
            await composer.click();
            await page.keyboard.type(`${SELECTOR} check ${CONTACT_QUERY} before I call them`);
            await page.keyboard.press("Enter");
            const { turnId } = (await (await accepted).json()) as { turnId: number };
            expect(turnId).toBeGreaterThan(0);

            /**
             * The chip is the strongest thing a member can see here: it renders only for a citation
             * the turn actually registered, so it exists only because `search_records` found the
             * contact, `get_record` read it, and the handle the fixture cited was rewritten back
             * into a real record the viewer is authorized to open.
             */
            const chip = page.getByRole("link", { name: ANSWER_LINK_LABEL });
            await expect(chip).toBeVisible({ timeout: 120_000 });
            await expect(chip).toHaveAttribute("href", `/records/contacts/${contactId}`);
            await expect(page.getByText(ANSWER_TAIL).first()).toBeVisible();

            const turn = await readTurn(api, workspaceId, sessionId, turnId);
            expect(turn.status, turn.terminalReason ?? "no terminal reason").toBe("resolved");
            expect(
                turn.progress.map((item) => `${item.source}:${item.status}`),
                `the read tool calls must reach the member's own turn projection: `
                + JSON.stringify(turn.progress),
            ).toContain("records:complete");
        } finally {
            await context.close();
        }
    });
});
