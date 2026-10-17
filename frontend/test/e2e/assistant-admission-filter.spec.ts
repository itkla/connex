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

/**
 * A path under the app origin that the wire tap answers itself. The page there stands in for an app
 * document carrying injected markup, so every request it makes to the app is
 * `Sec-Fetch-Site: same-origin`.
 */
const SAME_ORIGIN_PROBE_PATH = "/__wire-tap/same-origin-probe";

/** The request shapes a page can aim at an assistant read, keyed by their Fetch Metadata. */
type RequestShape = "cors" | "image" | "navigation" | "opaque";

/** The subresource shapes a probe page issues as soon as it loads, sorted. */
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
    const body: unknown = await response.json();
    if (typeof body !== "object" || body === null || !("id" in body) || typeof body.id !== "number") {
        throw new Error(`Session creation answered without a numeric id: ${JSON.stringify(body)}`);
    }
    expect(body.id).toBeGreaterThan(0);
    return body.id;
}

/**
 * A page that, on load, reads the target as an `<img>`, as an opaque `no-cors` fetch and as a
 * credentialed `cors` fetch; the link is the top-level navigation a test clicks on purpose. Served
 * on a foreign origin it is the attacker's page; served under the app origin it is injected markup.
 */
function probePage(target: string): string {
    const url = JSON.stringify(target);
    return [
        "<!doctype html><html><head><title>probe</title></head><body>",
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

/**
 * Whether an exchange is a read of `path`. A preflight is an `OPTIONS` carrying the `cors` shape's
 * metadata, and the CORS layer refuses a foreign one with the same body as the credentialed read,
 * so only a `GET` counts.
 */
function isReadOf(exchange: WireExchange, path: string): boolean {
    return exchange.method === "GET" && exchange.url.pathname === path;
}

/** The distinct shapes that have read `path` through the tap so far, sorted for comparison. */
function shapesAt(tap: WireTap, path: string): RequestShape[] {
    const shapes = new Set<RequestShape>();
    for (const exchange of tap.exchanges()) {
        const shape = isReadOf(exchange, path) ? shapeOf(exchange) : null;
        if (shape !== null) shapes.add(shape);
    }
    return [...shapes].sort();
}

/** The first read of `path` in one shape; the caller has already waited for it. */
function exchangeOf(tap: WireTap, path: string, shape: RequestShape): WireExchange {
    const exchange = tap.exchanges().find((candidate) => (
        isReadOf(candidate, path) && shapeOf(candidate) === shape
    ));
    if (exchange === undefined) throw new Error(`No ${shape} read of ${path} crossed the tap`);
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

/**
 * Whether the browser attached the live session to an exchange. The answer is a boolean so a
 * failing assertion prints no cookie values.
 */
function carriesSession(exchange: WireExchange, session: Cookie): boolean {
    const pairs = (header(exchange, "cookie") ?? "").split(/;\s*/);
    return pairs.includes(`${SESSION_COOKIE}=${session.value}`);
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
 * Asserts the browser sent a request from the expected site and the app refused it with the
 * expected body.
 */
function expectRefused(
    exchange: WireExchange,
    site: "cross-site" | "same-origin",
    body: string,
    reason: string,
): void {
    expect(header(exchange, "sec-fetch-site"), `${exchange.url.pathname} was not ${site}`).toBe(site);
    expect(exchange.status, reason).toBe(403);
    expect(exchange.body, reason).toBe(body);
}

/** Why a filter refusal is only possible when the browser's Fetch Metadata reached the backend. */
const METADATA_REACHED_BACKEND =
    "the admission filter admits a request with no Fetch Metadata, so its 403 body (not the 401 an "
    + "unauthenticated read gets, nor the 200 a live session gets) proves the browser's Sec-Fetch-* "
    + "headers crossed the Next.js /api rewrite";

/** Why the credentialed cross-origin fetch meets the CORS layer's refusal rather than the filter's. */
const CORS_REFUSES_FIRST =
    "SecurityConfig runs the CorsFilter before the admission filter, and an Origin outside "
    + "connex.cors.allowed-origins is refused there first; both are refusals, but a change in which "
    + "one answers must be reflected in docs/backend/AI_SECURITY.md";

/** Why a same-origin link navigation isolates the destination check. */
const DESTINATION_ALONE =
    "a same-origin navigation passes the site check and is not no-cors, so only "
    + "Sec-Fetch-Dest: document can refuse it; this 403 body proves that header crossed the Next.js "
    + "/api rewrite and that the destination check refuses it in a real browser";

/** Why a same-origin opaque fetch isolates the mode check. */
const MODE_ALONE =
    "a same-origin fetch with Sec-Fetch-Dest: empty passes the destination and site checks, so only "
    + "Sec-Fetch-Mode: no-cors can refuse it; this 403 body proves that header crossed the Next.js "
    + "/api rewrite and that the mode check refuses it in a real browser";

/** Why an `<img>` in the app's own document must still be refused. */
const INJECTED_IMAGE =
    "an <img> in an app document, such as one in model-authored Ask Connex markdown, is same-origin "
    + "and carries the session, so the site check admits it and the destination and mode checks "
    + "must refuse it";

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
            pages: { [`${FOREIGN_ORIGIN}/`]: probePage(new URL(path, baseURL).toString()) },
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

            expectRefused(exchangeOf(tap, path, "image"), "cross-site", FILTER_REFUSAL, METADATA_REACHED_BACKEND);
            expectRefused(exchangeOf(tap, path, "opaque"), "cross-site", FILTER_REFUSAL, METADATA_REACHED_BACKEND);
            expectRefused(exchangeOf(tap, path, "cors"), "cross-site", CORS_REFUSAL, CORS_REFUSES_FIRST);

            const navigation = exchangeOf(tap, path, "navigation");
            expect(
                carriesSession(navigation, session),
                "a SameSite=Lax session rides a top-level cross-site GET, which is what makes this the realistic forgery",
            ).toBe(true);
            expectRefused(navigation, "cross-site", FILTER_REFUSAL, METADATA_REACHED_BACKEND);
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
            pages: { [`${FOREIGN_ORIGIN}/`]: probePage(new URL(path, baseURL).toString()) },
        });
        const foreign = await tappedContext(browser, tap, crossSiteCookies);
        try {
            const attacker = await foreign.newPage();
            await attacker.goto(`${FOREIGN_ORIGIN}/`);
            await expect.poll(() => shapesAt(tap, path)).toEqual(SUBRESOURCE_SHAPES);

            for (const shape of SUBRESOURCE_SHAPES) {
                expect(
                    carriesSession(exchangeOf(tap, path, shape), session),
                    `the ${shape} request must carry the live session, or this test proves nothing about SameSite=None`,
                ).toBe(true);
            }
            expectRefused(exchangeOf(tap, path, "image"), "cross-site", FILTER_REFUSAL, METADATA_REACHED_BACKEND);
            expectRefused(exchangeOf(tap, path, "opaque"), "cross-site", FILTER_REFUSAL, METADATA_REACHED_BACKEND);
            expectRefused(exchangeOf(tap, path, "cors"), "cross-site", CORS_REFUSAL, CORS_REFUSES_FIRST);
        } finally {
            await foreign.close();
            await tap.close();
        }

        const control = await page.request.get(path);
        expect(control.status(), "the session the subresources carried is live and can read the route").toBe(200);
    });

    test("same-origin shapes the workspace never makes are refused by the destination or mode check alone", async ({ browser, context, page }, testInfo) => {
        const baseURL = baseUrlOf(testInfo.project.use.baseURL);
        const chatId = await createChat(page.request, `Admission same-origin shapes ${Date.now()}`);
        const path = attachmentsPath(chatId);
        const cookies = await context.cookies();
        const session = sessionCookie(cookies);
        const probeUrl = new URL(SAME_ORIGIN_PROBE_PATH, baseURL).toString();

        const tap = await startWireTap({
            app: baseURL,
            pages: { [probeUrl]: probePage(new URL(path, baseURL).toString()) },
        });
        const tapped = await tappedContext(browser, tap, cookies);
        try {
            const probe = await tapped.newPage();
            await probe.goto(probeUrl);
            await expect.poll(() => shapesAt(tap, path)).toEqual(SUBRESOURCE_SHAPES);

            await probe.locator("#navigate").click();
            await expect(probe).toHaveURL(new URL(path, baseURL).toString());
            await expect(probe.locator("body"), DESTINATION_ALONE).toHaveText(FILTER_REFUSAL);
            await expect.poll(() => shapesAt(tap, path)).toEqual(ALL_SHAPES);

            for (const shape of ALL_SHAPES) {
                expect(
                    carriesSession(exchangeOf(tap, path, shape), session),
                    `the same-origin ${shape} request must carry the live session, so only the filter can refuse it`,
                ).toBe(true);
            }
            expectRefused(exchangeOf(tap, path, "navigation"), "same-origin", FILTER_REFUSAL, DESTINATION_ALONE);
            expectRefused(exchangeOf(tap, path, "opaque"), "same-origin", FILTER_REFUSAL, MODE_ALONE);
            expectRefused(exchangeOf(tap, path, "image"), "same-origin", FILTER_REFUSAL, INJECTED_IMAGE);

            const admitted = exchangeOf(tap, path, "cors");
            expect(header(admitted, "sec-fetch-site"), "the control read was not same-origin").toBe("same-origin");
            expect(admitted.status, "the shape the workspace makes is admitted on the same page and session").toBe(200);
            expect(JSON.parse(admitted.body)).toEqual([]);
        } finally {
            await tapped.close();
            await tap.close();
        }
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
            expect(response.status(), response.url()).toBe(200);
        }

        const inPage = await page.evaluate(async (path) => {
            const response = await fetch(path, { credentials: "include", cache: "no-store" });
            return { status: response.status, body: await response.text() };
        }, attachmentsPath(chatId));
        expect(inPage.status, inPage.body).toBe(200);
        expect(JSON.parse(inPage.body)).toEqual([]);
    });
});
