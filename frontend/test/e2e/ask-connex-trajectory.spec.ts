import { randomUUID } from "node:crypto";
import { expect, test, type APIRequestContext, type Page } from "@playwright/test";

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

/** Account-security copy for the passkey enrolment this spec has to complete before it can ask. */
function accountCopy(key: string): string {
    return message("en", "account", `AccountSecurity.${key}`);
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

/** Statuses a turn still moves out of; everything else is terminal and will not change again. */
const PENDING_STATUSES = new Set(["queued", "running"]);

/**
 * Collects the streamed answer fragments the requester's browser is pushed over its own queue.
 *
 * This is the only assertion in the spec that the buffered path cannot satisfy. Every other
 * artefact here — the chip, the answer tail, the tool-call rows — is read from the settled
 * transcript, which `scripted-native` produces just as well as `scripted-native-stream`. The
 * streamed channel exists only while the turn runs, and a frame that already arrived cannot be
 * missed by a late assertion, so capturing frames beats racing the DOM for the live tail.
 *
 * Arrival is not ordered against anything the spec reads over HTTP. Each batch is queued for the
 * requester only after its partial-content transaction commits, and the broker and the browser
 * deliver it on their own schedule, so a turn can already read `resolved` while its last fragments
 * are still in flight. Callers therefore poll `fragments` rather than read it once.
 */
function collectStreamedAnswer(page: Page): {
    connected: () => boolean;
    fragments: () => string[];
} {
    const fragments: string[] = [];
    const socketFrames: string[] = [];
    let connected = false;
    page.on("websocket", (socket) => {
        socket.on("framereceived", (frame) => {
            const payload = typeof frame.payload === "string"
                ? frame.payload
                : frame.payload.toString("utf8");
            if (payload.startsWith("CONNECTED")) connected = true;
            socketFrames.push(payload);
        });
    });
    return {
        connected: () => connected,
        fragments: () => {
            for (const payload of socketFrames.splice(0)) {
                for (const body of payload.split("\n\n")) {
                    const start = body.indexOf("{");
                    if (start < 0) continue;
                    let parsed: unknown;
                    try {
                        parsed = JSON.parse(body.slice(start).replace(/\0/g, ""));
                    } catch {
                        continue;
                    }
                    if (typeof parsed !== "object" || parsed === null) continue;
                    const { kind, text } = parsed as { kind?: unknown; text?: unknown };
                    if (kind === "delta" && typeof text === "string") fragments.push(text);
                }
            }
            return [...fragments];
        },
    };
}

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

/**
 * Attaches a virtual authenticator to one page, mirroring `organization-settings.spec.ts`.
 *
 * @param page the page whose browser context receives the authenticator
 * @returns a callback removing it again
 */
async function installVirtualAuthenticator(page: Page): Promise<() => Promise<void>> {
    const session = await page.context().newCDPSession(page);
    await session.send("WebAuthn.enable");
    try {
        const { authenticatorId } = await session.send("WebAuthn.addVirtualAuthenticator", {
            options: {
                protocol: "ctap2",
                ctap2Version: "ctap2_1",
                transport: "internal",
                hasResidentKey: true,
                hasUserVerification: true,
                automaticPresenceSimulation: true,
                isUserVerified: true,
            },
        });
        return async () => {
            if (page.isClosed()) return;
            await session.send("WebAuthn.removeVirtualAuthenticator", { authenticatorId });
            await session.send("WebAuthn.disable");
            await session.detach();
        };
    } catch (error) {
        await session.send("WebAuthn.disable");
        await session.detach();
        throw error;
    }
}

/**
 * Enrols a passkey so the session carries the step-up the provider settings demand.
 *
 * `AiProviderConfigService.save` calls `SessionSecurityService.requireRecentAuthentication`
 * unconditionally, and only a completed WebAuthn ceremony marks that proof on the session — a
 * password login never does, whatever `CONNEX_PRIVILEGED_MFA_ENFORCED` says. So an organization
 * cannot be pointed at any provider, scripted or real, without one, and this spec has to do what an
 * administrator does rather than around it.
 *
 * @param page a page carrying the registered session and a virtual authenticator
 * @param password the password that session registered with
 */
async function enrolPasskey(page: Page, password: string): Promise<void> {
    await page.goto("/settings/personal/security");
    await page.getByRole("button", { name: accountCopy("add") }).first().click();
    const dialog = page.getByRole("dialog", { name: accountCopy("passwordTitle") });
    await dialog.getByLabel(accountCopy("passwordLabel")).fill(password);
    await dialog.getByRole("button", { name: accountCopy("continue"), exact: true }).click();
    await expect(page.getByText(accountCopy("added"), { exact: true })).toBeVisible();
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
 * Waits for the turn to stop moving, then hands back what it settled as.
 *
 * Diagnosing the turn before looking at the page is the difference between a failure that names
 * its own cause and one that names the DOM. Any breakage that settles the turn non-`resolved` — a
 * selector the masker rewrote, an assembler change that moves the cursor, a skill-authority
 * refusal — leaves the chip absent; asserting the chip first would burn the whole timeout and
 * report a missing link while the terminal reason that explains it went unread. The ordering costs
 * nothing: `AiChatTurnPersistenceService.resolve` inserts the assistant message and flips the turn
 * to `resolved` in one transaction, so a turn that reads `resolved` already has the message the
 * chip renders from.
 *
 * @param api request context carrying the requester's session
 * @param workspaceId the turn's tenant
 * @param sessionId the chat the turn belongs to
 * @param turnId the turn to wait on
 * @returns the settled turn projection
 */
async function awaitTerminal(
    api: APIRequestContext,
    workspaceId: number,
    sessionId: number,
    turnId: number,
): Promise<TurnState> {
    let turn = await readTurn(api, workspaceId, sessionId, turnId);
    const deadline = Date.now() + 120_000;
    while (PENDING_STATUSES.has(turn.status) && Date.now() < deadline) {
        await new Promise((resolve) => setTimeout(resolve, 250));
        turn = await readTurn(api, workspaceId, sessionId, turnId);
    }
    expect(
        turn.status,
        `the turn never settled resolved: ${JSON.stringify(turn)}. A terminal reason names the `
        + "control that refused; a still-running turn means the worker never finished.",
    ).toBe("resolved");
    return turn;
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
 *
 * The spec reads the turn to a terminal state before it looks at the page, so a refused turn fails
 * on its terminal reason rather than on a missing link. The streamed-fragment assertion is the only
 * one the buffered `scripted-native` class could not satisfy; the rest read the settled transcript.
 * It polls, because a `resolved` turn says nothing about whether its last frame has reached the
 * page yet.
 * The citation chip is the strongest thing a member can see: it renders only for a citation the
 * turn registered, so it exists only because `search_records` found the contact, `get_record` read
 * it, and the cited handle was rewritten back into a record the viewer is authorized to open.
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
        let removeVirtualAuthenticator: (() => Promise<void>) | null = null;
        try {
            const api = context.request;
            const runId = randomUUID().replace(/-/g, "").slice(0, 12);
            const password = `E2eHarness!${runId}A1`;
            await registerUser(api, {
                username: `e2e_ai_${runId}`,
                password,
                email: `e2e_ai_${runId}@example.com`,
            });
            const workspaceId = await activeWorkspaceId(api);
            const csrf = await csrfBootstrap(api);

            const page = await context.newPage();
            removeVirtualAuthenticator = await installVirtualAuthenticator(page);
            await enrolPasskey(page, password);
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

            const streamed = collectStreamedAnswer(page);
            await page.goto(`/ask-connex/${sessionId}`);
            const composer = page.getByRole("combobox", { name: copy("composerAria") });
            await expect(composer).toBeVisible();
            await expect
                .poll(streamed.connected, {
                    timeout: 30_000,
                    message: "the assistant session socket never completed its STOMP handshake, so "
                        + "no streamed fragment could reach this page however the turn ran",
                })
                .toBe(true);

            const accepted = page.waitForResponse((response) =>
                response.request().method() === "POST"
                && response.url().includes(`/api/ai/assistant/sessions/${sessionId}/turns`));
            await composer.click();
            await page.keyboard.type(`${SELECTOR} check ${CONTACT_QUERY} before I call them`);
            await page.getByRole("button", { name: copy("send") }).click();
            const { turnId } = (await (await accepted).json()) as { turnId: number };
            expect(turnId).toBeGreaterThan(0);

            const turn = await awaitTerminal(api, workspaceId, sessionId, turnId);
            expect(
                turn.progress.map((item) => `${item.source}:${item.status}`),
                `the read tool calls must reach the member's own turn projection: `
                + JSON.stringify(turn.progress),
            ).toContain("records:complete");

            await expect
                .poll(() => streamed.fragments().join(""), {
                    timeout: 30_000,
                    message: "the requester's own queue must carry the answer as it is written; "
                        + "the received string below is every streamed fragment that did arrive, "
                        + "concatenated",
                })
                .toContain(ANSWER_TAIL);

            const chip = page.getByRole("link", { name: ANSWER_LINK_LABEL });
            await expect(chip).toBeVisible({ timeout: 30_000 });
            await expect(chip).toHaveAttribute("href", `/records/contacts/${contactId}`);
            await expect(page.getByText(ANSWER_TAIL).first()).toBeVisible();
        } finally {
            await removeVirtualAuthenticator?.();
            await context.close();
        }
    });
});
