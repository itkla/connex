import { spawn, type ChildProcess } from "node:child_process";
import { randomUUID } from "node:crypto";
import { createServer, type Server, type ServerResponse } from "node:http";
import { createServer as createTcpServer, type Server as TcpServer, type Socket } from "node:net";
import path from "node:path";

import {
    expect,
    test,
    type APIRequestContext,
    type APIResponse,
    type BrowserContext,
    type Page,
    type Request as PlaywrightRequest,
} from "@playwright/test";

import type { DocumentAcceptancePreview } from "@/app/lib/types";
import { runFixture } from "./support/fixtures";
import { message } from "./support/messages";

const SIGNER_TOKEN = `w42-${"a".repeat(64)}`;
const VIEWER_TOKEN = `w42-${"b".repeat(64)}`;
const UNAVAILABLE_TOKEN = `w42-${"c".repeat(64)}`;
const JAPANESE_TOKEN = `w42-${"d".repeat(64)}`;
const SMTP_CAPTURE_PORT = 2525;
const THEMES: readonly ("light" | "dark")[] = ["light", "dark"];

function isRecord(value: unknown): value is Record<string, unknown> {
    return typeof value === "object" && value !== null;
}

function listeningPort(server: Server | TcpServer): number {
    const address = server.address();
    if (address === null || typeof address === "string") {
        throw new Error("Test server did not bind to a TCP port");
    }
    return address.port;
}

function preview(actionable: boolean, documentLocale: "en" | "ja" = "en"): DocumentAcceptancePreview {
    const japanese = documentLocale === "ja";
    return {
        content: {
            generatedAt: "2026-09-01T10:30:00",
            workspace: { name: "Hikari Systems", address: "Tokyo" },
            company: { name: "Northstar Trading", address: "Osaka" },
            owner: { name: "Aiko Mori" },
            deal: { name: "Autumn renewal", currency: "JPY" },
            sections: {
                title: japanese ? "秋期更新契約" : "Template section title",
                intro: japanese ? "以下の内容をご確認ください。" : "Please review the terms below.",
                terms: japanese ? "お支払い期限は30日以内です。" : "Payment is due within 30 days.",
                footer: japanese ? "よろしくお願いいたします。" : "Thank you.",
            },
            lineItems: [{
                id: 81,
                dealId: 17,
                productId: null,
                name: japanese ? "導入支援サービス" : "Implementation support service",
                sku: null,
                unit: null,
                unitPrice: 125000,
                quantity: 2,
                discountType: null,
                discountValue: null,
                taxRate: 10,
                billingFrequency: "one_time",
                description: japanese
                    ? "初期設定と運用開始時の支援"
                    : "Configuration and launch support",
                servicePeriodStart: null,
                servicePeriodEnd: null,
                position: 0,
                currency: "JPY",
                lineSubtotal: 250000,
                lineTax: 25000,
                lineTotal: 275000,
                createdAt: "2026-09-01T10:00:00Z",
                updatedAt: "2026-09-01T10:00:00Z",
            }],
            totals: {
                currency: "JPY",
                subtotal: 250000,
                tax: 25000,
                oneTimeTotal: 275000,
                recurringTotal: 0,
                grandTotal: 275000,
            },
        },
        dealName: "Autumn renewal",
        workspaceName: "Hikari Systems",
        recipientEmail: "r***@example.test",
        deliveryStatus: "sent",
        recipientStatus: "pending",
        actionable,
        documentType: "contract",
        documentTitle: japanese ? "秋期更新契約書" : "Frozen acceptance agreement",
        documentVersion: 3,
        documentLocale,
        expiresAt: "2026-09-08T10:30:00Z",
    };
}

const SIGNER_PREVIEW = preview(true);
const VIEWER_PREVIEW = preview(false);
const JAPANESE_PREVIEW = preview(true, "ja");

function json(serverResponse: ServerResponse, status: number, body: unknown) {
    serverResponse.writeHead(status, { "Content-Type": "application/json" });
    serverResponse.end(JSON.stringify(body));
}

async function startPreviewServer(): Promise<{
    server: Server;
    origin: string;
    requestCount: (token: string) => number;
    forwardedFor: (token: string) => string | null;
}> {
    const requestCounts = new Map<string, number>();
    const forwardedAddresses = new Map<string, string | null>();
    const server = createServer((request, response) => {
        const requestUrl = new URL(request.url ?? "/", "http://127.0.0.1");
        const match = /^\/api\/document-acceptance\/([^/]+)$/.exec(requestUrl.pathname);
        const token = match ? decodeURIComponent(match[1]) : null;
        if (request.method === "GET" && token) {
            requestCounts.set(token, (requestCounts.get(token) ?? 0) + 1);
            const forwardedFor = request.headers["x-forwarded-for"];
            forwardedAddresses.set(
                token,
                Array.isArray(forwardedFor) ? forwardedFor.join(",") : forwardedFor ?? null,
            );
        }
        if (request.method === "GET" && token === SIGNER_TOKEN) {
            json(response, 200, SIGNER_PREVIEW);
            return;
        }
        if (request.method === "GET" && token === VIEWER_TOKEN) {
            json(response, 200, VIEWER_PREVIEW);
            return;
        }
        if (request.method === "GET" && token === JAPANESE_TOKEN) {
            json(response, 200, JAPANESE_PREVIEW);
            return;
        }
        json(response, 404, {
            code: "RESOURCE_NOT_FOUND",
            message: "Document link is no longer available",
        });
    });
    await new Promise<void>((resolve, reject) => {
        server.once("error", reject);
        server.listen(0, "127.0.0.1", resolve);
    });
    return {
        server,
        origin: `http://127.0.0.1:${listeningPort(server)}`,
        requestCount: (token) => requestCounts.get(token) ?? 0,
        forwardedFor: (token) => forwardedAddresses.get(token) ?? null,
    };
}

async function availablePort(): Promise<number> {
    const server = createServer();
    await new Promise<void>((resolve, reject) => {
        server.once("error", reject);
        server.listen(0, "127.0.0.1", resolve);
    });
    const port = listeningPort(server);
    await closeServer(server);
    return port;
}

async function closeServer(server: Server): Promise<void> {
    await new Promise<void>((resolve, reject) => {
        server.close((error) => error ? reject(error) : resolve());
    });
}

async function startAcceptanceApp(apiOrigin: string): Promise<{
    process: ChildProcess;
    origin: string;
    output: () => string;
}> {
    const port = await availablePort();
    const frontendRoot = process.cwd();
    const nextBin = path.join(frontendRoot, "node_modules", "next", "dist", "bin", "next");
    const nextProcess = spawn(
        process.execPath,
        [nextBin, "start", "--hostname", "127.0.0.1", "--port", String(port)],
        {
            cwd: frontendRoot,
            env: {
                ...process.env,
                API_URL: apiOrigin,
            },
            stdio: ["ignore", "pipe", "pipe"],
        },
    );
    let output = "";
    const appendOutput = (chunk: Buffer) => {
        output = `${output}${chunk.toString()}`.slice(-8_000);
    };
    nextProcess.stdout?.on("data", appendOutput);
    nextProcess.stderr?.on("data", appendOutput);
    const origin = `http://127.0.0.1:${port}`;
    const deadline = Date.now() + 60_000;
    while (Date.now() < deadline) {
        if (nextProcess.exitCode != null) {
            throw new Error(`Acceptance fixture app exited during startup\n${output}`);
        }
        try {
            const response = await fetch(`${origin}/document-acceptance/${UNAVAILABLE_TOKEN}`);
            if (response.ok) return { process: nextProcess, origin, output: () => output };
        } catch {}
        await new Promise((resolve) => setTimeout(resolve, 250));
    }
    await stopProcess(nextProcess);
    throw new Error(`Acceptance fixture app did not start\n${output}`);
}

async function stopProcess(child: ChildProcess): Promise<void> {
    if (child.exitCode != null) return;
    child.kill("SIGTERM");
    await Promise.race([
        new Promise<void>((resolve) => child.once("exit", () => resolve())),
        new Promise<void>((resolve) => setTimeout(resolve, 5_000)),
    ]);
    if (child.exitCode == null) child.kill("SIGKILL");
}

async function expectResponsiveDocument(page: Page, mobile: boolean) {
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth))
        .toBe(true);
    const documentBox = await page.locator("article").boundingBox();
    const details = await page.locator("aside").boundingBox();
    expect(documentBox).not.toBeNull();
    expect(details).not.toBeNull();
    if (documentBox === null || details === null) {
        throw new Error("Document acceptance layout boxes were unavailable");
    }
    if (mobile) {
        expect(details.y).toBeGreaterThanOrEqual(documentBox.y + documentBox.height - 1);
        await expect(page.getByTestId("document-line-items-stacked")).toBeVisible();
        await expect(page.getByTestId("document-line-items-table")).toBeHidden();
        expect(await page.getByTestId("document-line-items-stacked").evaluate(
            (element) => element.scrollWidth <= element.clientWidth,
        )).toBe(true);
    } else {
        expect(details.x).toBeGreaterThanOrEqual(documentBox.x + documentBox.width - 1);
        await expect(page.getByTestId("document-line-items-table")).toBeVisible();
        await expect(page.getByTestId("document-line-items-stacked")).toBeHidden();
    }
}

function deferred<T>() {
    const state: {
        resolve?: (value: T) => void;
        reject?: (reason?: unknown) => void;
    } = {};
    const promise = new Promise<T>((resolve, reject) => {
        state.resolve = resolve;
        state.reject = reject;
    });
    return {
        promise,
        resolve(value: T) {
            const callback = state.resolve;
            if (!callback) throw new Error("Deferred resolver is unavailable");
            callback(value);
        },
        reject(reason: unknown) {
            const callback = state.reject;
            if (!callback) throw new Error("Deferred rejecter is unavailable");
            callback(reason);
        },
    };
}

function serveSmtp(
    socket: Socket,
    capture: (messageBody: string, recipientAddress: string | null) => void,
): void {
    let buffered = "";
    let messageBody = "";
    let readingData = false;
    let recipientAddress: string | null = null;
    socket.setEncoding("utf8");
    socket.write("220 localhost ESMTP Connex E2E\r\n");
    socket.on("data", (chunk) => {
        buffered += typeof chunk === "string" ? chunk : chunk.toString("utf8");
        while (buffered.length > 0) {
            if (readingData) {
                const terminator = buffered.indexOf("\r\n.\r\n");
                if (terminator < 0) {
                    const retainedLength = Math.min(4, buffered.length);
                    messageBody += buffered.slice(0, buffered.length - retainedLength);
                    buffered = buffered.slice(buffered.length - retainedLength);
                    return;
                }
                messageBody += buffered.slice(0, terminator);
                buffered = buffered.slice(terminator + 5);
                readingData = false;
                capture(messageBody, recipientAddress);
                messageBody = "";
                socket.write("250 2.0.0 accepted\r\n");
                continue;
            }
            const lineEnd = buffered.indexOf("\r\n");
            if (lineEnd < 0) return;
            const command = buffered.slice(0, lineEnd);
            buffered = buffered.slice(lineEnd + 2);
            const verb = command.split(" ", 1)[0]?.toUpperCase();
            if (verb === "EHLO") {
                socket.write("250-localhost\r\n250 8BITMIME\r\n");
            } else if (verb === "HELO") {
                socket.write("250 2.0.0 ok\r\n");
            } else if (verb === "MAIL") {
                recipientAddress = null;
                socket.write("250 2.0.0 ok\r\n");
            } else if (verb === "RCPT") {
                recipientAddress = /^RCPT\s+TO:\s*<([^>]+)>/i.exec(command)?.[1] ?? null;
                socket.write("250 2.0.0 ok\r\n");
            } else if (verb === "RSET") {
                recipientAddress = null;
                socket.write("250 2.0.0 ok\r\n");
            } else if (verb === "DATA") {
                readingData = true;
                socket.write("354 End data with <CR><LF>.<CR><LF>\r\n");
            } else if (verb === "QUIT") {
                socket.end("221 2.0.0 bye\r\n");
                return;
            } else {
                socket.write("250 2.0.0 ok\r\n");
            }
        }
    });
}

function decodedSmtpMessage(message: string): string {
    const separator = message.indexOf("\r\n\r\n");
    if (separator < 0) return message;
    const headers = message.slice(0, separator).toLowerCase();
    const payload = message.slice(separator + 4);
    if (headers.includes("content-transfer-encoding: base64")) {
        return Buffer.from(payload.replace(/\s/g, ""), "base64").toString("utf8");
    }
    if (headers.includes("content-transfer-encoding: quoted-printable")) {
        const unfolded = payload.replace(/=\r\n/g, "");
        const bytes: number[] = [];
        for (let index = 0; index < unfolded.length; index += 1) {
            const encoded = unfolded.slice(index, index + 3);
            if (/^=[a-f0-9]{2}$/i.test(encoded)) {
                bytes.push(Number.parseInt(encoded.slice(1), 16));
                index += 2;
            } else {
                bytes.push(unfolded.charCodeAt(index));
            }
        }
        return Buffer.from(bytes).toString("utf8");
    }
    return payload;
}

async function startSmtpCapture(
    expectedMessages: number,
    expectedRecipient: string,
    port: number,
): Promise<{
    server: TcpServer;
    sockets: Set<Socket>;
    port: number;
    acceptancePaths: Promise<string[]>;
}> {
    const capturedPaths = deferred<string[]>();
    const paths: string[] = [];
    const sockets = new Set<Socket>();
    const server = createTcpServer((socket) => {
        sockets.add(socket);
        socket.once("close", () => sockets.delete(socket));
        serveSmtp(socket, (body, recipientAddress) => {
            if (recipientAddress?.toLowerCase() !== expectedRecipient.toLowerCase()) {
                return;
            }
            const decoded = decodedSmtpMessage(body);
            const match = /\/document-acceptance\/w\d+-[a-f0-9]{64}/.exec(decoded);
            const acceptancePath = match?.[0];
            if (!acceptancePath) {
                capturedPaths.reject(new Error("Document acceptance URL was absent from SMTP message"));
                return;
            }
            paths.push(acceptancePath);
            if (paths.length === expectedMessages) capturedPaths.resolve([...paths]);
        });
    });
    await new Promise<void>((resolve, reject) => {
        server.once("error", reject);
        server.listen(port, "127.0.0.1", resolve);
    });
    return {
        server,
        sockets,
        port: listeningPort(server),
        acceptancePaths: capturedPaths.promise,
    };
}

async function closeTcpServer(server: TcpServer, sockets: Set<Socket>): Promise<void> {
    for (const socket of sockets) socket.destroy();
    if (!server.listening) return;
    await new Promise<void>((resolve, reject) => {
        server.close((error) => error ? reject(error) : resolve());
    });
}

async function withTimeout<T>(promise: Promise<T>, milliseconds: number, label: string): Promise<T> {
    let timer: ReturnType<typeof setTimeout> | undefined;
    const timeout = new Promise<T>((_resolve, reject) => {
        timer = setTimeout(() => reject(new Error(`${label} timed out`)), milliseconds);
    });
    try {
        return await Promise.race([promise, timeout]);
    } finally {
        if (timer) clearTimeout(timer);
    }
}

async function jsonObject(response: APIResponse, label: string): Promise<Record<string, unknown>> {
    const text = await response.text();
    if (response.status() >= 300) {
        throw new Error(`${label} returned ${response.status()}: ${text.slice(0, 500)}`);
    }
    let body: unknown;
    try {
        body = JSON.parse(text);
    } catch {
        throw new Error(`${label} did not return JSON: ${text.slice(0, 500)}`);
    }
    if (!isRecord(body)) throw new Error(`${label} did not return a JSON object`);
    return body;
}

function numberField(body: Record<string, unknown>, field: string, label: string): number {
    const value = body[field];
    if (typeof value !== "number") throw new Error(`${label}.${field} was not a number`);
    return value;
}

async function authenticatedWriteHeaders(
    api: APIRequestContext,
    fixture: ReturnType<typeof runFixture>,
): Promise<Record<string, string>> {
    const csrf = await jsonObject(await api.get("/api/auth/csrf"), "CSRF bootstrap");
    if (typeof csrf.token !== "string" || typeof csrf.headerName !== "string") {
        throw new Error("CSRF bootstrap omitted its token or header name");
    }
    return {
        "X-Workspace-Id": String(fixture.workspaceId),
        [csrf.headerName]: csrf.token,
    };
}

test.describe("anonymous and presentation document acceptance", () => {
    test.use({ storageState: { cookies: [], origins: [] } });

    test("document acceptance reaches the running frontend and backend without authentication", async ({ page }) => {
        const response = await page.goto("/document-acceptance/not-a-bearer");

        expect(response?.status()).toBe(200);
        expect(await response?.headerValue("referrer-policy")).toBe("no-referrer");
        await expect(page).toHaveURL(/\/document-acceptance\/not-a-bearer$/);
        await expect(page.getByRole("heading", {
            name: message("en", "document-acceptance", "DocumentAcceptance.unavailableTitle"),
        })).toBeVisible();
    });

    test("document acceptance renders signer, viewer, and unavailable states across themes @mobile", async ({ page }, testInfo) => {
        test.setTimeout(180_000);
        const fixture = await startPreviewServer();
        const mobile = testInfo.project.name === "mobile-chromium";
        let app: Awaited<ReturnType<typeof startAcceptanceApp>> | null = null;

        try {
            app = await startAcceptanceApp(fixture.origin);
        await page.setExtraHTTPHeaders({ "X-Forwarded-For": "203.0.113.44" });
        await page.route(/\/api\/document-acceptance\/[^/]+\/viewed$/, async (route) => {
            const requestUrl = route.request().url();
            const token = requestUrl.includes(VIEWER_TOKEN)
                ? VIEWER_TOKEN
                : requestUrl.includes(JAPANESE_TOKEN)
                    ? JAPANESE_TOKEN
                    : SIGNER_TOKEN;
            const viewed = {
                ...(token === VIEWER_TOKEN
                    ? VIEWER_PREVIEW
                    : token === JAPANESE_TOKEN
                        ? JAPANESE_PREVIEW
                        : SIGNER_PREVIEW),
                deliveryStatus: "viewed",
                recipientStatus: "viewed",
            };
            await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(viewed) });
        });
        await page.route(/\/api\/document-acceptance\/[^/]+\/accept$/, async (route) => {
            await route.fulfill({
                status: 200,
                contentType: "application/json",
                body: JSON.stringify({
                    deliveryStatus: "completed",
                    recipientStatus: "completed",
                    completed: true,
                }),
            });
        });
        await page.goto(`${app.origin}/document-acceptance/${UNAVAILABLE_TOKEN}`);
        for (const theme of THEMES) {
            await page.evaluate((value) => window.localStorage.setItem("theme", value), theme);
            await page.emulateMedia({ colorScheme: theme, reducedMotion: "reduce" });

            const requestsBeforeRender = fixture.requestCount(SIGNER_TOKEN);
            await page.goto(`${app.origin}/document-acceptance/${SIGNER_TOKEN}`);
            expect(fixture.requestCount(SIGNER_TOKEN)).toBe(requestsBeforeRender + 1);
            expect(fixture.forwardedFor(SIGNER_TOKEN)).toBe("203.0.113.44");
            await expect(page.locator("html")).toHaveClass(new RegExp(`(?:^|\\s)${theme}(?:\\s|$)`));
            await expect(page.getByRole("heading", { name: "Frozen acceptance agreement" })).toBeVisible();
            await expect(page.getByRole("button", {
                name: message("en", "document-acceptance", "DocumentAcceptance.accept"),
                exact: true,
            })).toBeEnabled();
            await expect(page.getByRole("button", {
                name: message("en", "document-acceptance", "DocumentAcceptance.decline"),
                exact: true,
            })).toBeEnabled();
            await expectResponsiveDocument(page, mobile);
            expect(await page.evaluate(() => matchMedia("(prefers-reduced-motion: reduce)").matches)).toBe(true);
            await page.getByRole("button", {
                name: message("en", "document-acceptance", "DocumentAcceptance.accept"),
                exact: true,
            }).click();
            await page.getByLabel(message(
                "en",
                "document-acceptance",
                "DocumentAcceptance.typedNameLabel",
            ), { exact: true }).fill("Rina Sato");
            await page.getByRole("button", {
                name: message("en", "document-acceptance", "DocumentAcceptance.confirmAccept"),
                exact: true,
            }).click();
            await expect(page.getByRole("heading", {
                name: message("en", "document-acceptance", "DocumentAcceptance.acceptedTitle"),
            })).toBeVisible();

            await page.goto(`${app.origin}/document-acceptance/${VIEWER_TOKEN}`);
            await expect(page.locator("html")).toHaveClass(new RegExp(`(?:^|\\s)${theme}(?:\\s|$)`));
            await expect(page.getByRole("heading", {
                name: message("en", "document-acceptance", "DocumentAcceptance.viewerTitle"),
            })).toBeVisible();
            await expect(page.getByRole("button", {
                name: message("en", "document-acceptance", "DocumentAcceptance.accept"),
                exact: true,
            })).toHaveCount(0);
            await expect(page.getByRole("button", {
                name: message("en", "document-acceptance", "DocumentAcceptance.decline"),
                exact: true,
            })).toHaveCount(0);
            await expectResponsiveDocument(page, mobile);

            await page.goto(`${app.origin}/document-acceptance/${UNAVAILABLE_TOKEN}`);
            await expect(page.locator("html")).toHaveClass(new RegExp(`(?:^|\\s)${theme}(?:\\s|$)`));
            await expect(page.getByRole("heading", {
                name: message("en", "document-acceptance", "DocumentAcceptance.unavailableTitle"),
            })).toBeVisible();
            expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth))
                .toBe(true);
        }

        await page.goto(`${app.origin}/document-acceptance/${JAPANESE_TOKEN}`);
        await expect(page).toHaveTitle(
            `${message("ja", "document-acceptance", "DocumentAcceptance.metaTitle")} | Connex`,
        );
        await expect(page.getByRole("heading", { name: "秋期更新契約書" })).toBeVisible();
        await expect(page.getByRole("button", {
            name: message("ja", "document-acceptance", "DocumentAcceptance.accept"),
            exact: true,
        })).toBeEnabled();
        const lineItems = page.getByTestId(
            mobile ? "document-line-items-stacked" : "document-line-items-table",
        );
        await expect(lineItems.getByText("導入支援サービス", { exact: true })).toBeVisible();
        await expectResponsiveDocument(page, mobile);
        } catch (error) {
            const appOutput = app?.output() ?? "Acceptance fixture app did not start";
            throw new Error(`${error instanceof Error ? error.message : String(error)}\n${appOutput}`);
        } finally {
            if (app) await stopProcess(app.process);
            await closeServer(fixture.server);
        }
    });
});

test("authenticated setup completes through a cookie-less public bearer", async ({
    browser,
    request: authenticatedApi,
}, testInfo) => {
    test.setTimeout(120_000);
    const fixture = runFixture(testInfo.project.name);
    const smtp = await startSmtpCapture(2, "rina.sato@example.test", SMTP_CAPTURE_PORT);
    let anonymousContext: BrowserContext | null = null;

    try {
        const writeHeaders = await authenticatedWriteHeaders(authenticatedApi, fixture);

        const unique = randomUUID();
        const sourceDeal = await jsonObject(await authenticatedApi.get(
            `/api/deals/${fixture.deals.primary.id}`,
            { headers: writeHeaders },
        ), "source deal");
        const pipelineId = numberField(sourceDeal, "pipeline", "source deal");
        const stageId = numberField(sourceDeal, "stage", "source deal");
        const companyId = sourceDeal.company;
        if (companyId !== null && typeof companyId !== "number") {
            throw new Error("source deal.company was not nullable numeric data");
        }
        const dealName = `Acceptance E2E Deal ${unique}`;
        const duplicateReview = await jsonObject(await authenticatedApi.post(
            "/api/duplicate-preflight/deals",
            {
                headers: writeHeaders,
                data: { name: dealName, companyId },
            },
        ), "acceptance deal duplicate review");
        const duplicateReviewToken = duplicateReview.reviewToken;
        if (typeof duplicateReviewToken !== "string") {
            throw new Error("Acceptance deal duplicate review omitted its token");
        }
        const acceptanceDeal = await jsonObject(await authenticatedApi.post("/api/deals", {
            headers: writeHeaders,
            data: {
                name: dealName,
                value: 10_000,
                actualValue: 0,
                currency: "USDT",
                pipeline: pipelineId,
                stage: stageId,
                company: companyId,
                duplicateReviewToken,
            },
        }), "acceptance deal");
        const dealId = numberField(acceptanceDeal, "id", "acceptance deal");
        const template = await jsonObject(await authenticatedApi.post("/api/document-templates", {
            headers: writeHeaders,
            data: {
                name: `Acceptance E2E ${unique}`,
                type: "contract",
                locale: "en",
                title: "Full-stack acceptance agreement",
                intro: "Review this generated agreement.",
                terms: "Acceptance records the signer's decision.",
                footer: "Generated by the Connex E2E suite.",
                body: null,
                active: true,
            },
        }), "document template");
        const templateId = numberField(template, "id", "document template");
        const generatedWithoutItems = await jsonObject(await authenticatedApi.post(
            `/api/deals/${dealId}/documents`,
            { headers: writeHeaders, data: { templateId } },
        ), "generated document without line items");
        const documentWithoutItemsId = numberField(
            generatedWithoutItems,
            "id",
            "generated document without line items",
        );
        const contentWithoutItems = generatedWithoutItems.content;
        if (!isRecord(contentWithoutItems)
                || !Array.isArray(contentWithoutItems.lineItems)
                || !isRecord(contentWithoutItems.totals)) {
            throw new Error("Generated empty document omitted its frozen content");
        }
        expect(contentWithoutItems.lineItems).toHaveLength(0);
        expect(contentWithoutItems.totals.currency).toBeUndefined();
        await jsonObject(await authenticatedApi.put(
            `/api/deals/${dealId}/documents/${documentWithoutItemsId}/status`,
            { headers: writeHeaders, data: { status: "final" } },
        ), "final document without line items");

        await jsonObject(await authenticatedApi.post(
            `/api/deals/${dealId}/line-items`,
            {
                headers: writeHeaders,
                data: {
                    name: "Acceptance implementation service",
                    unitPrice: 1250,
                    quantity: 2,
                    taxRate: 10,
                    billingFrequency: "one_time",
                    description: "Frozen into the acceptance document",
                    position: 0,
                },
            },
        ), "deal line item");

        const generatedWithItems = await jsonObject(await authenticatedApi.post(
            `/api/deals/${dealId}/documents`,
            { headers: writeHeaders, data: { templateId } },
        ), "generated document with line items");
        const documentWithItemsId = numberField(
            generatedWithItems,
            "id",
            "generated document with line items",
        );
        const contentWithItems = generatedWithItems.content;
        if (!isRecord(contentWithItems)
                || !Array.isArray(contentWithItems.lineItems)
                || !isRecord(contentWithItems.totals)) {
            throw new Error("Generated populated document omitted its frozen content");
        }
        expect(contentWithItems.lineItems.length).toBeGreaterThan(0);
        expect(contentWithItems.totals.currency).toBe("USDT");
        await jsonObject(await authenticatedApi.put(
            `/api/deals/${dealId}/documents/${documentWithItemsId}/status`,
            { headers: writeHeaders, data: { status: "final" } },
        ), "final document with line items");

        const sendDocument = async (documentId: number, label: string): Promise<number> => {
            const sent = await jsonObject(await authenticatedApi.post(
                `/api/deals/${dealId}/documents/${documentId}/delivery`,
                {
                    headers: { ...writeHeaders, "Idempotency-Key": randomUUID() },
                    data: {
                        provider: "in_app",
                        message: "Please review and accept this agreement.",
                        expiresAt: "2099-12-31T23:59:59",
                        recipients: [{
                            personId: null,
                            name: "Rina Sato",
                            email: "rina.sato@example.test",
                            role: "signer",
                            recipientOrder: 1,
                        }],
                    },
                },
            ), label);
            return numberField(sent, "id", label);
        };

        await sendDocument(documentWithoutItemsId, "empty document delivery");
        const deliveryId = await sendDocument(documentWithItemsId, "populated document delivery");
        const acceptancePaths = await withTimeout(
            smtp.acceptancePaths,
            20_000,
            "SMTP document acceptance links",
        );
        const acceptanceWithoutItemsPath = acceptancePaths[0];
        const acceptanceWithItemsPath = acceptancePaths[1];
        if (!acceptanceWithoutItemsPath || !acceptanceWithItemsPath) {
            throw new Error("SMTP capture did not return both document acceptance links");
        }

        const baseURL = testInfo.project.use.baseURL;
        if (typeof baseURL !== "string") throw new Error("The E2E project requires a base URL");
        anonymousContext = await browser.newContext({
            baseURL,
            locale: "en-US",
            timezoneId: "UTC",
            reducedMotion: "reduce",
            storageState: { cookies: [], origins: [] },
        });
        const bearerRequests: PlaywrightRequest[] = [];
        const anonymousPage = await anonymousContext.newPage();
        anonymousPage.on("request", (request) => {
            const pathname = new URL(request.url()).pathname;
            if (pathname.startsWith("/document-acceptance/")
                    || pathname.startsWith("/api/document-acceptance/")) {
                bearerRequests.push(request);
            }
        });
        expect(await anonymousContext.cookies()).toEqual([]);

        await anonymousPage.goto(acceptanceWithoutItemsPath);
        await expect(anonymousPage.getByRole("heading", {
            name: "Full-stack acceptance agreement",
        })).toBeVisible();
        await expect(anonymousPage.getByTestId("document-line-items-table")).toHaveCount(0);
        await expect(anonymousPage.getByTestId("document-line-items-stacked")).toHaveCount(0);
        await expect(anonymousPage.getByRole("button", {
            name: message("en", "document-acceptance", "DocumentAcceptance.accept"),
            exact: true,
        })).toBeEnabled();

        await anonymousPage.goto(acceptanceWithItemsPath);
        await expect(anonymousPage.getByRole("heading", {
            name: "Full-stack acceptance agreement",
        })).toBeVisible();
        await expect(anonymousPage.getByText("Acceptance implementation service", {
            exact: true,
        })).toBeVisible();
        const acceptButton = anonymousPage.getByRole("button", {
            name: message("en", "document-acceptance", "DocumentAcceptance.accept"),
            exact: true,
        });
        await expect(acceptButton).toBeEnabled();
        await acceptButton.click();
        await anonymousPage.getByLabel(message(
            "en",
            "document-acceptance",
            "DocumentAcceptance.typedNameLabel",
        ), { exact: true }).fill("Rina Sato");
        await anonymousPage.getByRole("button", {
            name: message("en", "document-acceptance", "DocumentAcceptance.confirmAccept"),
            exact: true,
        }).click();
        await expect(anonymousPage.getByRole("heading", {
            name: message("en", "document-acceptance", "DocumentAcceptance.acceptedTitle"),
        })).toBeVisible();

        expect(bearerRequests.length).toBeGreaterThanOrEqual(5);
        for (const request of bearerRequests) {
            const requestHeaders = await request.allHeaders();
            expect(requestHeaders.cookie).toBeUndefined();
            expect(requestHeaders.authorization).toBeUndefined();
            expect(requestHeaders["x-workspace-id"]).toBeUndefined();
            expect(Object.keys(requestHeaders).some((name) => name.includes("csrf"))).toBe(false);
        }
        expect(await anonymousContext.cookies()).toEqual([]);

        const deliveriesResponse = await authenticatedApi.get(
            `/api/deals/${dealId}/documents/${documentWithItemsId}/delivery`,
            { headers: writeHeaders },
        );
        const deliveriesText = await deliveriesResponse.text();
        if (deliveriesResponse.status() >= 300) {
            throw new Error(`delivery receipt returned ${deliveriesResponse.status()}: ${deliveriesText.slice(0, 500)}`);
        }
        let deliveries: unknown;
        try {
            deliveries = JSON.parse(deliveriesText);
        } catch {
            throw new Error(`delivery receipt did not return JSON: ${deliveriesText.slice(0, 500)}`);
        }
        if (!Array.isArray(deliveries)) throw new Error("delivery receipt was not a JSON array");
        const completed = deliveries.find((candidate) => (
            isRecord(candidate) && candidate.id === deliveryId
        ));
        if (!isRecord(completed)) throw new Error("completed delivery was absent from its document");
        expect(completed.status).toBe("completed");
        if (!Array.isArray(completed.recipients)) {
            throw new Error("completed delivery omitted recipients");
        }
        const recipient = completed.recipients[0];
        if (!isRecord(recipient)) throw new Error("completed delivery omitted its signer");
        expect(recipient.status).toBe("completed");
        expect(typeof recipient.firstViewedAt).toBe("string");
        expect(recipient.typedName).toBe("Rina Sato");
    } finally {
        const cleanupResults = await Promise.allSettled([
            anonymousContext?.close() ?? Promise.resolve(),
            closeTcpServer(smtp.server, smtp.sockets),
        ]);
        for (const result of cleanupResults) {
            if (result.status === "rejected") {
                throw result.reason instanceof Error
                    ? result.reason
                    : new Error(String(result.reason));
            }
        }
    }
});
