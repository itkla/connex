import {
    expect,
    test,
    type APIRequestContext,
    type Browser,
    type BrowserContext,
    type Cookie,
    type Page,
} from "@playwright/test";

import { activeWorkspaceId, csrfBootstrap } from "./support/api";
import { startWireTap, type WireExchange, type WireTap } from "./support/wire-tap";

/** The body `AiAssistantNavigationAdmissionFilter` answers every refused request shape with. */
const FILTER_REFUSAL = "Assistant API request shape is not allowed";

/**
 * The body Spring's `DefaultCorsProcessor` answers an `Origin` outside the CORS allowlist with.
 * `SecurityConfig` runs the `CorsFilter` ahead of the admission filter, so this is the refusal a
 * credentialed `cors` fetch from a foreign site meets first.
 */
const CORS_REFUSAL = "Invalid CORS request";

/** The session cookie `proxy.ts` keys route protection on. */
const SESSION_COOKIE = "JSESSIONID";

/**
 * A site sharing nothing with the app. The wire tap answers its page itself, so it needs no DNS,
 * and every request it makes to the app is `Sec-Fetch-Site: cross-site`.
 */
const FOREIGN_ORIGIN = "http://attacker.example";

/** The request shapes a foreign page can aim at an assistant read, keyed by their Fetch Metadata. */
type RequestShape = "cors" | "image" | "navigation" | "opaque";

/** The subresource shapes the foreign page issues as soon as it loads, sorted. */
const SUBRESOURCE_SHAPES: readonly RequestShape[] = ["cors", "image", "opaque"];

/** Every shape, sorted: the subresources plus the navigation a test clicks. */
const ALL_SHAPES: readonly RequestShape[] = ["cors", "image", "navigation", "opaque"];

function baseUrlOf(projectBaseURL: string | undefined): string {
    if (typeof projectBaseURL !== "string") throw new Error("The E2E project requires a base URL");
    return projectBaseURL;
}

/** The audited attachments read for one chat, the route #1520 names. */
function attachmentsPath(chatId: number): string {
    return `/api/ai/assistant/sessions/${chatId}/attachments`;
}

/**
 * Creates a chat through the API the drawer itself uses. Session creation needs `AI_USE` and
 * workspace membership but no configured AI provider.
 *
 * @returns the new chat's id
 */
async function createChat(api: APIRequestContext, title: string): Promise<number> {
    const workspaceId = await activeWorkspaceId(api);
    const csrf = await csrfBootstrap(api);
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
 * The attacker's page. On load it reads the target as an `<img>`, as an opaque `no-cors` fetch and
 * as a credentialed `cors` fetch; the link is the top-level navigation a test clicks on purpose.
 */
function foreignPage(target: string): string {
    const url = JSON.stringify(target);
    return [
        "<!doctype html><html><head><title>attacker</title></head><body>",
        `<a id="navigate" href="${target}">open</a>`,
        `<img alt="" src="${target}">`,
        "<script>",
        `fetch(${url}, { mode: "no-cors", credentials: "include" }).catch(() => undefined);`,
        `fetch(${url}, { mode: "cors", credentials: "include" }).catch(() => undefined);`,
        "</script>",
        "</body></html>",
    ].join("\n");
}

function header(exchange: WireExchange, name: string): string | undefined {
    const value = exchange.requestHeaders[name];
    return typeof value === "string" ? value : undefined;
}

function shapeOf(exchange: WireExchange): RequestShape | null {
    const destination = header(exchange, "sec-fetch-dest");
    const mode = header(exchange, "sec-fetch-mode");
    if (destination === "image" && mode === "no-cors") return "image";
    if (destination === "document" && mode === "navigate") return "navigation";
    if (destination === "empty" && mode === "no-cors") return "opaque";
    if (destination === "empty" && mode === "cors") return "cors";
    return null;
}

/** The distinct shapes that have reached `path` through the tap so far, sorted for comparison. */
function shapesAt(tap: WireTap, path: string): RequestShape[] {
    const shapes = new Set<RequestShape>();
    for (const exchange of tap.exchanges()) {
        const shape = exchange.url.pathname === path ? shapeOf(exchange) : null;
        if (shape !== null) shapes.add(shape);
    }
    return [...shapes].sort();
}

/** The first exchange of one shape that reached `path`; the caller has already waited for it. */
function exchangeOf(tap: WireTap, path: string, shape: RequestShape): WireExchange {
    const exchange = tap.exchanges().find((candidate) => (
        candidate.url.pathname === path && shapeOf(candidate) === shape
    ));
    if (exchange === undefined) throw new Error(`No ${shape} request reached ${path}`);
    return exchange;
}

/** The session the storage-state tenant is signed in with; the cookie a forgery would ride on. */
function sessionCookie(cookies: readonly Cookie[]): Cookie {
    const session = cookies.find((cookie) => cookie.name === SESSION_COOKIE);
    if (session === undefined) {
        throw new Error(`The storage-state tenant carries no ${SESSION_COOKIE} cookie`);
    }
    return session;
}

/** Opens a context whose every request crosses the tap, holding the given cookies. */
async function tappedContext(
    browser: Browser,
    tap: WireTap,
    cookies: readonly Cookie[],
): Promise<BrowserContext> {
    return browser.newContext({
        locale: "en-US",
        timezoneId: "UTC",
        reducedMotion: "reduce",
        proxy: { server: tap.proxyServer, bypass: "<-loopback>" },
        storageState: { cookies: [...cookies], origins: [] },
    });
}

/**
 * Asserts the browser sent a cross-site request of the expected shape and the app refused it with
 * the expected body.
 */
function expectRefused(exchange: WireExchange, body: string, reason: string): void {
    expect(header(exchange, "sec-fetch-site"), `${exchange.url.pathname} was not cross-site`)
        .toBe("cross-site");
    expect(exchange.status, reason).toBe(403);
    expect(exchange.body, reason).toBe(body);
}

/** Why a filter refusal is only possible when the browser's Fetch Metadata reached the backend. */
const METADATA_REACHED_BACKEND =
    "the admission filter admits a request with no Fetch Metadata, so its 403 body (not the 401 an "
    + "unauthenticated read gets, nor the 200 a live session gets) proves Sec-Fetch-* crossed the "
    + "Next.js /api rewrite intact";

/** Why the credentialed cross-origin fetch meets the CORS layer's refusal rather than the filter's. */
const CORS_REFUSES_FIRST =
    "SecurityConfig runs the CorsFilter before the admission filter, and an Origin outside "
    + "connex.cors.allowed-origins is refused there first; both are refusals, but a change in which "
    + "one answers must be reflected in docs/backend/AI_SECURITY.md";

/** Waits for the first GET the page makes to exactly `path`, ignoring its query string. */
function nextRead(page: Page, path: string) {
    return page.waitForResponse((response) => (
        response.request().method() === "GET" && new URL(response.url()).pathname === path
    ));
}

test.describe("Ask Connex read admission in a real browser", () => {
    test("a foreign page cannot reach an audited read as an image, an opaque fetch, a cors fetch or a navigation", async ({ browser, context, page }, testInfo) => {
        const baseURL = baseUrlOf(testInfo.project.use.baseURL);
        const chatId = await createChat(page.request, `Admission refusal ${Date.now()}`);
        const path = attachmentsPath(chatId);
        const cookies = await context.cookies();
        const session = sessionCookie(cookies);

        const tap = await startWireTap({
            app: baseURL,
            pages: { [FOREIGN_ORIGIN]: foreignPage(new URL(path, baseURL).toString()) },
        });
        const foreign = await tappedContext(browser, tap, cookies);
        try {
            const attacker = await foreign.newPage();
            await attacker.goto(`${FOREIGN_ORIGIN}/`);
            await expect.poll(() => shapesAt(tap, path)).toEqual(SUBRESOURCE_SHAPES);

            await attacker.locator("#navigate").click();
            await expect(attacker).toHaveURL(new URL(path, baseURL).toString());
            await expect(attacker.locator("body")).toHaveText(FILTER_REFUSAL);
            await expect.poll(() => shapesAt(tap, path)).toEqual(ALL_SHAPES);

            expectRefused(exchangeOf(tap, path, "image"), FILTER_REFUSAL, METADATA_REACHED_BACKEND);
            expectRefused(exchangeOf(tap, path, "opaque"), FILTER_REFUSAL, METADATA_REACHED_BACKEND);
            expectRefused(exchangeOf(tap, path, "cors"), CORS_REFUSAL, CORS_REFUSES_FIRST);

            const navigation = exchangeOf(tap, path, "navigation");
            expect(
                header(navigation, "cookie") ?? "",
                "a SameSite=Lax session rides a top-level cross-site GET, which is what makes this the realistic forgery",
            ).toContain(`${SESSION_COOKIE}=${session.value}`);
            expectRefused(navigation, FILTER_REFUSAL, METADATA_REACHED_BACKEND);
        } finally {
            await foreign.close();
            await tap.close();
        }

        const control = await page.request.get(path);
        expect(control.status(), "the same session without Fetch Metadata takes the fail-open path").toBe(200);
        expect(await control.json()).toEqual([]);
    });

    test("SameSite=None session cookies ride cross-site subresources and are still refused", async ({ browser, context, page }, testInfo) => {
        const baseURL = baseUrlOf(testInfo.project.use.baseURL);
        const chatId = await createChat(page.request, `Admission SameSite None ${Date.now()}`);
        const path = attachmentsPath(chatId);
        const cookies = await context.cookies();
        const session = sessionCookie(cookies);
        const crossSiteCookies = cookies.map((cookie) => ({
            ...cookie,
            sameSite: "None" as const,
            secure: true,
        }));

        const tap = await startWireTap({
            app: baseURL,
            pages: { [FOREIGN_ORIGIN]: foreignPage(new URL(path, baseURL).toString()) },
        });
        const foreign = await tappedContext(browser, tap, crossSiteCookies);
        try {
            const attacker = await foreign.newPage();
            await attacker.goto(`${FOREIGN_ORIGIN}/`);
            await expect.poll(() => shapesAt(tap, path)).toEqual(SUBRESOURCE_SHAPES);

            for (const shape of SUBRESOURCE_SHAPES) {
                expect(
                    header(exchangeOf(tap, path, shape), "cookie") ?? "",
                    `the ${shape} request must carry the live session, or this test proves nothing about SameSite=None`,
                ).toContain(`${SESSION_COOKIE}=${session.value}`);
            }
            expectRefused(exchangeOf(tap, path, "image"), FILTER_REFUSAL, METADATA_REACHED_BACKEND);
            expectRefused(exchangeOf(tap, path, "opaque"), FILTER_REFUSAL, METADATA_REACHED_BACKEND);
            expectRefused(exchangeOf(tap, path, "cors"), CORS_REFUSAL, CORS_REFUSES_FIRST);
        } finally {
            await foreign.close();
            await tap.close();
        }

        const control = await page.request.get(path);
        expect(control.status(), "the session the subresources carried is live and can read the route").toBe(200);
    });

    test("the assistant workspace still reads its own chat from the app origin", async ({ page }) => {
        const title = `Admission same origin ${Date.now()}`;
        const chatId = await createChat(page.request, title);
        const detail = `/api/ai/assistant/sessions/${chatId}`;
        const reads = [
            nextRead(page, detail),
            nextRead(page, attachmentsPath(chatId)),
            nextRead(page, `${detail}/tool-calls`),
        ];

        await page.goto(`/ask-connex/${chatId}`);
        await expect(page.getByRole("heading", { level: 1, name: title })).toBeVisible();

        for (const response of await Promise.all(reads)) {
            const sent = await response.request().allHeaders();
            expect(sent["sec-fetch-site"], response.url()).toBe("same-origin");
            expect(sent["sec-fetch-mode"], response.url()).toBe("cors");
            expect(sent["sec-fetch-dest"], response.url()).toBe("empty");
            expect(response.status(), `${response.url()}: ${await response.text()}`).toBe(200);
        }

        const inPage = await page.evaluate(async (path) => {
            const response = await fetch(path, { credentials: "include", cache: "no-store" });
            return { status: response.status, body: await response.text() };
        }, attachmentsPath(chatId));
        expect(inPage.status, inPage.body).toBe(200);
        expect(JSON.parse(inPage.body)).toEqual([]);
    });
});
