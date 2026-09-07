import { randomUUID } from "node:crypto";
import { createServer as createTcpServer, type Server as TcpServer, type Socket } from "node:net";

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
import {
    activeWorkspaceId,
    registerUser,
} from "./support/api";
import { message } from "./support/messages";

const MOCK_GRANT = "e".repeat(64);
const MOCK_FLOW_ID = "d".repeat(64);
const SMTP_CAPTURE_PORT = 2525;
const THEMES: readonly ("light" | "dark")[] = ["light", "dark"];

function isRecord(value: unknown): value is Record<string, unknown> {
    return typeof value === "object" && value !== null;
}

function listeningPort(server: TcpServer): number {
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
        flowId: MOCK_FLOW_ID,
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

function jsonRoute(body: unknown): { status: number; contentType: string; body: string } {
    return { status: 200, contentType: "application/json", body: JSON.stringify(body) };
}

/**
 * Routes the whole recipient contract in the browser: the CSRF bootstrap, the fragment exchange
 * that answers with the grant cookie, and the three token-free endpoints the granted page calls.
 */
async function mockAcceptanceFlow(
    page: Page,
    state: { preview: DocumentAcceptancePreview },
): Promise<void> {
    await page.route(
        (url) => url.pathname === "/api/auth/csrf",
        (route) => route.fulfill(jsonRoute({
            token: "csrf-token",
            headerName: "X-CSRF-TOKEN",
            parameterName: "_csrf",
            requestIdentity: null,
        })),
    );
    await page.route(
        (url) => url.pathname === "/api/document-acceptance/exchange",
        (route) => route.fulfill({
            status: 303,
            headers: {
                Location: "/document-acceptance",
                "Set-Cookie": `connex_document_acceptance_flow=${MOCK_GRANT};`
                    + " Path=/api/document-acceptance; HttpOnly; SameSite=Strict",
            },
        }),
    );
    await page.route(
        (url) => url.pathname === "/api/document-acceptance",
        (route) => route.fulfill(jsonRoute(state.preview)),
    );
    await page.route(
        (url) => url.pathname === "/api/document-acceptance/viewed",
        (route) => route.fulfill(jsonRoute({
            ...state.preview,
            deliveryStatus: "viewed",
            recipientStatus: "viewed",
        })),
    );
    await page.route(
        (url) => url.pathname === "/api/document-acceptance/accept",
        (route) => route.fulfill(jsonRoute({
            deliveryStatus: "completed",
            recipientStatus: "completed",
            completed: true,
        })),
    );
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
    expectedRecipients: readonly string[],
    port: number,
): Promise<{
    server: TcpServer;
    sockets: Set<Socket>;
    port: number;
    acceptancePaths: Promise<ReadonlyMap<string, string>>;
}> {
    const capturedPaths = deferred<ReadonlyMap<string, string>>();
    const recipientKeys = new Set(expectedRecipients.map((recipient) => recipient.toLowerCase()));
    const paths = new Map<string, string>();
    const sockets = new Set<Socket>();
    const server = createTcpServer((socket) => {
        sockets.add(socket);
        socket.once("close", () => sockets.delete(socket));
        serveSmtp(socket, (body, recipientAddress) => {
            const recipientKey = recipientAddress?.toLowerCase();
            if (!recipientKey || !recipientKeys.has(recipientKey)) return;
            const decoded = decodedSmtpMessage(body);
            const match = /\/document-acceptance#token=w\d+-[a-f0-9]{64}/.exec(decoded);
            const acceptancePath = match?.[0];
            if (!acceptancePath) {
                capturedPaths.reject(new Error("Document acceptance URL was absent from SMTP message"));
                return;
            }
            paths.set(recipientKey, acceptancePath);
            if (paths.size === recipientKeys.size) capturedPaths.resolve(new Map(paths));
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
    workspaceId: number,
): Promise<Record<string, string>> {
    const csrf = await jsonObject(await api.get("/api/auth/csrf"), "CSRF bootstrap");
    if (typeof csrf.token !== "string" || typeof csrf.headerName !== "string") {
        throw new Error("CSRF bootstrap omitted its token or header name");
    }
    return {
        "X-Workspace-Id": String(workspaceId),
        [csrf.headerName]: csrf.token,
    };
}

test.describe("anonymous and presentation document acceptance", () => {
    test.use({ storageState: { cookies: [], origins: [] } });

    test("the bare acceptance route stays public, credential-free, and referrer-free", async ({ page }) => {
        const response = await page.goto("/document-acceptance#token=not-a-bearer");

        expect(response?.status()).toBe(200);
        expect(await response?.headerValue("referrer-policy")).toBe("no-referrer");
        await expect(page).toHaveURL(/\/document-acceptance$/);
        await expect(page.getByRole("heading", {
            name: message("en", "document-acceptance", "DocumentAcceptance.unavailableTitle"),
        })).toBeVisible();
    });

    test("the legacy path bearer no longer resolves to a page", async ({ page }) => {
        const response = await page.goto(`/document-acceptance/w42-${"a".repeat(64)}`);

        expect(response?.status()).toBe(404);
    });

    test("document acceptance renders signer, viewer, and unavailable states across themes @mobile", async ({ page }, testInfo) => {
        test.setTimeout(180_000);
        const mobile = testInfo.project.name === "mobile-chromium";
        const state = { preview: preview(true) };
        await mockAcceptanceFlow(page, state);

        for (const theme of THEMES) {
            await page.goto("/document-acceptance");
            await page.evaluate((value) => window.localStorage.setItem("theme", value), theme);
            await page.emulateMedia({ colorScheme: theme, reducedMotion: "reduce" });

            state.preview = preview(true);
            await page.goto("/document-acceptance");
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

            state.preview = preview(false);
            await page.goto("/document-acceptance");
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
        }

        state.preview = preview(true, "ja");
        await page.goto("/document-acceptance");
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
    });
});

test("authenticated setup completes through a cookie-less public bearer", async ({
    browser,
}, testInfo) => {
    test.setTimeout(120_000);
    const unique = randomUUID();
    const actorId = unique.replaceAll("-", "").slice(0, 16);
    const emptyDocumentRecipient = `rina.sato+empty-${unique}@example.test`;
    const populatedDocumentRecipient = `rina.sato+populated-${unique}@example.test`;
    const smtp = await startSmtpCapture(
        [emptyDocumentRecipient, populatedDocumentRecipient],
        SMTP_CAPTURE_PORT,
    );
    let actorContext: BrowserContext | null = null;
    let anonymousContext: BrowserContext | null = null;

    try {
        const baseURL = testInfo.project.use.baseURL;
        if (typeof baseURL !== "string") throw new Error("The E2E project requires a base URL");
        actorContext = await browser.newContext({
            baseURL,
            storageState: { cookies: [], origins: [] },
        });
        const authenticatedApi = actorContext.request;
        await registerUser(authenticatedApi, {
            username: `acceptance${actorId}`,
            password: `Acceptance!${actorId}A1`,
            email: `acceptance-${actorId}@example.com`,
        });
        const workspaceId = await activeWorkspaceId(authenticatedApi);
        const writeHeaders = await authenticatedWriteHeaders(authenticatedApi, workspaceId);
        const pipeline = await jsonObject(await authenticatedApi.post("/api/pipelines", {
            headers: writeHeaders,
            data: { name: `Acceptance E2E Pipeline ${unique}` },
        }), "acceptance pipeline");
        const pipelineId = numberField(pipeline, "id", "acceptance pipeline");
        const stage = await jsonObject(await authenticatedApi.post(
            `/api/pipelines/${pipelineId}/stages`,
            {
                headers: writeHeaders,
                data: {
                    name: "Acceptance review",
                    position: 0,
                    success: false,
                    failure: false,
                },
            },
        ), "acceptance pipeline stage");
        const stageId = numberField(stage, "id", "acceptance pipeline stage");
        const createAcceptanceDeal = async (variant: string): Promise<number> => {
            const dealName = `Acceptance E2E ${variant} Deal ${unique}`;
            const duplicateReview = await jsonObject(await authenticatedApi.post(
                "/api/duplicate-preflight/deals",
                {
                    headers: writeHeaders,
                    data: { name: dealName, companyId: null },
                },
            ), `${variant} acceptance deal duplicate review`);
            const duplicateReviewToken = duplicateReview.reviewToken;
            if (typeof duplicateReviewToken !== "string") {
                throw new Error(`${variant} acceptance deal duplicate review omitted its token`);
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
                    company: null,
                    duplicateReviewToken,
                },
            }), `${variant} acceptance deal`);
            return numberField(acceptanceDeal, "id", `${variant} acceptance deal`);
        };
        const dealWithoutItemsId = await createAcceptanceDeal("empty");
        const dealWithItemsId = await createAcceptanceDeal("populated");
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
            `/api/deals/${dealWithoutItemsId}/documents`,
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
            `/api/deals/${dealWithoutItemsId}/documents/${documentWithoutItemsId}/status`,
            { headers: writeHeaders, data: { status: "final" } },
        ), "final document without line items");

        await jsonObject(await authenticatedApi.post(
            `/api/deals/${dealWithItemsId}/line-items`,
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
            `/api/deals/${dealWithItemsId}/documents`,
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
            `/api/deals/${dealWithItemsId}/documents/${documentWithItemsId}/status`,
            { headers: writeHeaders, data: { status: "final" } },
        ), "final document with line items");

        const sendDocument = async (
            dealId: number,
            documentId: number,
            recipientEmail: string,
            label: string,
        ): Promise<number> => {
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
                            email: recipientEmail,
                            role: "signer",
                            recipientOrder: 1,
                        }],
                    },
                },
            ), label);
            return numberField(sent, "id", label);
        };

        const emptyDeliveryId = await sendDocument(
            dealWithoutItemsId,
            documentWithoutItemsId,
            emptyDocumentRecipient,
            "empty document delivery",
        );
        const recipientStatusOf = async (
            dealId: number,
            documentId: number,
            targetDeliveryId: number,
        ): Promise<unknown> => {
            const response = await authenticatedApi.get(
                `/api/deals/${dealId}/documents/${documentId}/delivery`,
                { headers: writeHeaders },
            );
            const deliveries: unknown = await response.json();
            if (!Array.isArray(deliveries)) throw new Error("delivery list was not a JSON array");
            const target = deliveries.find((candidate) => (
                isRecord(candidate) && candidate.id === targetDeliveryId
            ));
            if (!isRecord(target) || !Array.isArray(target.recipients)) {
                throw new Error(`delivery ${targetDeliveryId} was absent from its document`);
            }
            const recipient = target.recipients[0];
            return isRecord(recipient) ? recipient.status : undefined;
        };
        const deliveryId = await sendDocument(
            dealWithItemsId,
            documentWithItemsId,
            populatedDocumentRecipient,
            "populated document delivery",
        );
        const acceptancePaths = await withTimeout(
            smtp.acceptancePaths,
            20_000,
            "SMTP document acceptance links",
        );
        const acceptanceWithoutItemsPath = acceptancePaths.get(emptyDocumentRecipient);
        const acceptanceWithItemsPath = acceptancePaths.get(populatedDocumentRecipient);
        if (!acceptanceWithoutItemsPath || !acceptanceWithItemsPath) {
            throw new Error("SMTP capture did not return both document acceptance links");
        }

        anonymousContext = await browser.newContext({
            baseURL,
            locale: "en-US",
            timezoneId: "UTC",
            reducedMotion: "reduce",
            storageState: { cookies: [], origins: [] },
        });
        const observedRequests: PlaywrightRequest[] = [];
        const anonymousPage = await anonymousContext.newPage();
        anonymousPage.on("request", (request) => observedRequests.push(request));
        expect(await anonymousContext.cookies()).toEqual([]);
        const bearerOf = (link: string): string => {
            const bearer = /#token=(w\d+-[a-f0-9]{64})$/.exec(link)?.[1];
            if (!bearer) throw new Error(`Acceptance link carried no fragment bearer: ${link}`);
            return bearer;
        };
        const emptyBearer = bearerOf(acceptanceWithoutItemsPath);
        const populatedBearer = bearerOf(acceptanceWithItemsPath);

        await anonymousPage.goto(acceptanceWithoutItemsPath);
        await expect(anonymousPage).toHaveURL(/\/document-acceptance$/);
        await expect(anonymousPage.getByRole("heading", {
            name: "Full-stack acceptance agreement",
        })).toBeVisible();
        await expect(anonymousPage.getByTestId("document-line-items-table")).toHaveCount(0);
        await expect(anonymousPage.getByTestId("document-line-items-stacked")).toHaveCount(0);
        await expect(anonymousPage.getByRole("button", {
            name: message("en", "document-acceptance", "DocumentAcceptance.accept"),
            exact: true,
        })).toBeEnabled();

        const laterTab = await anonymousContext.newPage();
        await laterTab.goto(acceptanceWithItemsPath);
        await expect(laterTab).toHaveURL(/\/document-acceptance$/);
        await expect(laterTab.getByTestId("document-line-items-table")).toBeVisible();
        await laterTab.close();
        await anonymousPage.getByRole("button", {
            name: message("en", "document-acceptance", "DocumentAcceptance.accept"),
            exact: true,
        }).click();
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
            name: message("en", "document-acceptance", "DocumentAcceptance.unavailableTitle"),
        })).toBeVisible();
        expect(await recipientStatusOf(dealWithoutItemsId, documentWithoutItemsId, emptyDeliveryId))
            .toBe("viewed");
        expect(await recipientStatusOf(dealWithItemsId, documentWithItemsId, deliveryId))
            .toBe("viewed");

        await anonymousPage.goto(acceptanceWithItemsPath);
        await expect(anonymousPage).toHaveURL(/\/document-acceptance$/);
        await expect(anonymousPage.getByRole("heading", {
            name: "Full-stack acceptance agreement",
        })).toBeVisible();
        const desktopLineItems = anonymousPage.getByTestId("document-line-items-table");
        await expect(desktopLineItems).toBeVisible();
        await expect(anonymousPage.getByTestId("document-line-items-stacked")).toBeHidden();
        await expect(desktopLineItems.getByText("Acceptance implementation service", {
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

        expect(observedRequests.length).toBeGreaterThanOrEqual(5);
        for (const bearer of [emptyBearer, populatedBearer]) {
            expect(observedRequests.every((request) => !request.url().includes(bearer))).toBe(true);
            const carriers = observedRequests.filter((request) => (
                request.method() === "POST"
                && new URL(request.url()).pathname === "/api/document-acceptance/exchange"
                && (request.postData() ?? "").includes(bearer)
            ));
            expect(carriers).toHaveLength(1);
            expect(carriers[0].postDataJSON()).toEqual({ token: bearer });
        }
        const grantCookies = (await anonymousContext.cookies())
            .filter((cookie) => cookie.name === "connex_document_acceptance_flow");
        expect(grantCookies).toHaveLength(1);
        expect(grantCookies[0].httpOnly).toBe(true);
        expect(grantCookies[0].path).toBe("/api/document-acceptance");
        expect(grantCookies[0].sameSite).toBe("Strict");
        for (const cookie of await anonymousContext.cookies()) {
            expect(cookie.value).not.toContain(emptyBearer);
            expect(cookie.value).not.toContain(populatedBearer);
        }

        const decidedContext = await browser.newContext({
            baseURL,
            locale: "en-US",
            timezoneId: "UTC",
            reducedMotion: "reduce",
            storageState: { cookies: [], origins: [] },
        });
        try {
            const decidedPage = await decidedContext.newPage();
            await decidedPage.goto(acceptanceWithItemsPath);
            await expect(decidedPage.getByRole("heading", {
                name: message("en", "document-acceptance", "DocumentAcceptance.unavailableTitle"),
            })).toBeVisible();
        } finally {
            await decidedContext.close();
        }

        const deliveriesResponse = await authenticatedApi.get(
            `/api/deals/${dealWithItemsId}/documents/${documentWithItemsId}/delivery`,
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
            actorContext?.close() ?? Promise.resolve(),
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
