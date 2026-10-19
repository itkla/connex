/** @vitest-environment jsdom */
import { existsSync, readdirSync, readFileSync } from "node:fs";
import path from "node:path";

import { act, useEffect, type ReactNode } from "react";
import { createRoot } from "react-dom/client";
import { renderToStaticMarkup } from "react-dom/server";
import { createTranslator, NextIntlClientProvider } from "next-intl";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import {
    ApiError,
    beginManagedPairing,
    beginProviderConnection,
    cancelManagedPairing,
    clientRecoveryContext,
    createNote,
    createReportSnapshot,
    createWorkspace,
    decideCaptureReview,
    disconnectProviderConnection,
    downloadCsv,
    exportReportCsv,
    getNotesPage,
    isWorkspaceUnavailableError,
    pauseProviderConnection,
    resumeProviderConnection,
    startAiChatTurn,
    switchWorkspace,
    synchronizeBrowserAccount,
    updateMyLocale,
    updateMyTimezone,
    updateNotificationPreferences,
    updateOrganizationIdentity,
    uploadAiChatAttachment,
    uploadAttachmentImage,
    uploadCurrentUserProfilePicture,
    WORKSPACE_UNAVAILABLE_CODE,
} from "@/app/lib/api";
import { userMessageFor, type MessageTranslator } from "@/app/lib/errorMessages";
import { draftKey, listFreshDrafts, readDraft, writeDraft } from "@/app/lib/formDrafts";
import {
    bindRenderedWorkspace,
    matchesRoutePattern,
    pinsToRenderedWorkspace,
    releaseRenderedWorkspace,
    rememberedWorkspaceName,
    rememberWorkspaceNames,
    renderedWorkspaceId,
    UNPINNED_WRITE_ROUTES,
    workspacePinnedRequest,
} from "@/app/lib/renderedWorkspace";
import { useWorkspace, WorkspaceProvider } from "@/app/hooks/useWorkspace";
import DuplicatePreflightWarning from "@/app/components/records/DuplicatePreflightWarning";
import type { Workspace } from "@/app/lib/types";
import commonEn from "@/messages/en/common.json";
import errorsEn from "@/messages/en/errors.json";
import errorsJa from "@/messages/ja/errors.json";

vi.mock("next/navigation", () => ({
    useRouter: () => ({ refresh: vi.fn(), replace: vi.fn(), push: vi.fn() }),
}));

const HEALED = 5;
const RENDERED = 9;
const REVOKED_NAME = "Northwind Field Team";
const REFUSAL_MESSAGE = `Not a member of workspace ${RENDERED}`;

type Recorded = { url: string; method: string; workspace: string | null };

const owners: object[] = [];

function owner(): object {
    const created = {};
    owners.push(created);
    return created;
}

function json(body: unknown, status = 200): Response {
    return new Response(JSON.stringify(body), {
        status,
        headers: { "Content-Type": "application/json" },
    });
}

function stubBackend(answer: (url: string, method: string) => Response | null = () => null): Recorded[] {
    const requests: Recorded[] = [];
    vi.stubGlobal("fetch", vi.fn(async (input: string | URL | Request, init?: RequestInit) => {
        const url = input instanceof Request ? input.url : String(input);
        const method = init?.method ?? "GET";
        if (url.endsWith("/api/auth/csrf")) {
            return json({ token: "csrf-token", headerName: "X-XSRF-TOKEN", requestIdentity: "identity" });
        }
        requests.push({ url, method, workspace: new Headers(init?.headers).get("X-Workspace-Id") });
        return answer(url, method) ?? json({ id: 41, items: [], total: 0 });
    }));
    return requests;
}

function setWorkspaceCookie(workspaceId: number | null): void {
    document.cookie = workspaceId === null
        ? "connex_workspace=; path=/; max-age=0"
        : `connex_workspace=${workspaceId}; path=/`;
}

function refusal(): Response {
    return json({ code: "FORBIDDEN", message: REFUSAL_MESSAGE }, 403);
}

type MessageTree = { [key: string]: string | MessageTree };

function translator(locale: "en" | "ja"): MessageTranslator {
    const messages: MessageTree = locale === "en" ? errorsEn : errorsJa;
    return createTranslator({ locale, messages });
}

function workspace(id: number, name: string): Workspace {
    return {
        id,
        name,
        slug: name.toLowerCase().replaceAll(" ", "-"),
        timezone: null,
        identityVersion: 0,
        role: "member",
        orgId: id,
        orgName: name,
        orgIdentityVersion: 0,
        orgRole: null,
    };
}

async function refusedNote(): Promise<unknown> {
    return createNote({ content: "Quarterly plan", title: null, visibility: "private", author: 7 }).then(
        () => null,
        (error: unknown) => error,
    );
}

beforeEach(() => {
    setWorkspaceCookie(HEALED);
});

afterEach(() => {
    for (const held of owners.splice(0)) releaseRenderedWorkspace(held);
    setWorkspaceCookie(null);
    window.sessionStorage.clear();
    vi.unstubAllGlobals();
    vi.clearAllMocks();
});

describe("rendered workspace binding", () => {
    it("publishes the newest selection and clears it only for the provider that holds it", () => {
        const shell = owner();
        const other = owner();

        bindRenderedWorkspace(shell, RENDERED);
        expect(renderedWorkspaceId()).toBe(RENDERED);

        bindRenderedWorkspace(other, null);
        releaseRenderedWorkspace(other);
        expect(renderedWorkspaceId()).toBe(RENDERED);

        bindRenderedWorkspace(shell, null);
        expect(renderedWorkspaceId()).toBeNull();
    });

    it("keeps a workspace's name after the list drops it, until its provider releases", () => {
        const shell = owner();
        bindRenderedWorkspace(shell, RENDERED);
        rememberWorkspaceNames([workspace(RENDERED, REVOKED_NAME)]);
        rememberWorkspaceNames([workspace(HEALED, "Head Office")]);

        expect(rememberedWorkspaceName(RENDERED)).toBe(REVOKED_NAME);

        releaseRenderedWorkspace(shell);
        expect(renderedWorkspaceId()).toBeNull();
        expect(rememberedWorkspaceName(RENDERED)).toBeNull();
    });
});

describe("state-changing requests follow the rendered workspace, not a healed cookie", () => {
    it("pins a write to the rendered workspace while a read still follows the cookie", async () => {
        const requests = stubBackend();
        bindRenderedWorkspace(owner(), RENDERED);

        await createNote({ content: "Quarterly plan", title: null, visibility: "private", author: 7 });
        await getNotesPage({ q: "plan" });

        expect(requests).toEqual([
            { url: "/api/notes", method: "POST", workspace: String(RENDERED) },
            { url: "/api/notes/page?q=plan", method: "GET", workspace: String(HEALED) },
        ]);
    });

    it("lets a caller's explicit workspace header win over the rendered one", async () => {
        const requests = stubBackend();
        bindRenderedWorkspace(owner(), RENDERED);

        await createNote(
            { content: "Quarterly plan", title: null, visibility: "private", author: 7 },
            { headers: { "X-Workspace-Id": "12" } },
        );

        expect(requests.map(({ workspace }) => workspace)).toEqual(["12"]);
    });

    it("keeps following the cookie where no app shell has published a workspace", async () => {
        const requests = stubBackend();

        await createNote({ content: "Quarterly plan", title: null, visibility: "private", author: 7 });

        expect(requests.map(({ workspace }) => workspace)).toEqual([String(HEALED)]);
    });

    it("pins a multipart upload to the rendered workspace", async () => {
        const requests = stubBackend();
        bindRenderedWorkspace(owner(), RENDERED);

        await uploadAttachmentImage("note", 41, new File(["image"], "board.png", { type: "image/png" }));

        expect(requests).toEqual([
            { url: "/api/attachments/upload-image", method: "POST", workspace: String(RENDERED) },
        ]);
    });

    it("scopes a business-card capture to the rendered workspace", async () => {
        stubBackend((url) => url.endsWith("/api/auth/me") ? json({ id: 3 }) : null);
        bindRenderedWorkspace(owner(), RENDERED);

        const context = await clientRecoveryContext();

        expect(context?.workspaceId).toBe(String(RENDERED));
    });
});

describe("the refused pinned write", () => {
    it("is re-coded as the rendered workspace's refusal and is never retried", async () => {
        const requests = stubBackend((url, method) => method === "POST" ? refusal() : null);
        const shell = owner();
        bindRenderedWorkspace(shell, RENDERED);
        rememberWorkspaceNames([workspace(RENDERED, REVOKED_NAME)]);

        const error = await refusedNote();

        expect(isWorkspaceUnavailableError(error)).toBe(true);
        expect(error).toMatchObject({
            status: 403,
            code: WORKSPACE_UNAVAILABLE_CODE,
            workspaceId: RENDERED,
            workspaceName: REVOKED_NAME,
        });
        expect(requests.filter(({ method }) => method === "POST")).toHaveLength(1);
    });

    it.each([
        ["another workspace's refusal", () => json({ code: "FORBIDDEN", message: `Not a member of workspace ${HEALED}` }, 403)],
        ["a permission refusal", () => json({ code: "PERMISSION_REQUIRED", message: REFUSAL_MESSAGE }, 403)],
        ["a missing record", () => json({ code: "FORBIDDEN", message: REFUSAL_MESSAGE }, 404)],
    ])("leaves %s as it was", async (_name, response) => {
        stubBackend((url, method) => method === "POST" ? response() : null);
        bindRenderedWorkspace(owner(), RENDERED);

        const error = await refusedNote();

        expect(error).toBeInstanceOf(ApiError);
        expect(isWorkspaceUnavailableError(error)).toBe(false);
    });

    it("does not re-code a refused read", async () => {
        stubBackend(() => refusal());
        bindRenderedWorkspace(owner(), RENDERED);

        const error = await getNotesPage({}, { headers: { "X-Workspace-Id": String(RENDERED) } }).then(
            () => null,
            (failure: unknown) => failure,
        );

        expect(error).toMatchObject({ status: 403, code: "FORBIDDEN" });
    });

    it.each(["en", "ja"] as const)("tells the %s reader which workspace the draft belongs to", async (locale) => {
        stubBackend((url, method) => method === "POST" ? refusal() : null);
        bindRenderedWorkspace(owner(), RENDERED);
        rememberWorkspaceNames([workspace(RENDERED, REVOKED_NAME)]);
        const t = translator(locale);

        const message = userMessageFor(await refusedNote(), t, undefined, "ApiErrors.generic");

        expect(message?.description).toBe(
            t("ApiErrors.workspaceUnavailableNamed", { workspace: REVOKED_NAME }),
        );
        expect(message?.description).not.toContain(REFUSAL_MESSAGE);
    });

    it("still explains the refusal when the shell never listed the workspace's name", async () => {
        stubBackend((url, method) => method === "POST" ? refusal() : null);
        bindRenderedWorkspace(owner(), RENDERED);
        const t = translator("en");

        expect(userMessageFor(await refusedNote(), t)?.description).toBe(t("ApiErrors.workspaceUnavailable"));
    });
});

describe("WorkspaceProvider", () => {
    function Capture({ onRender }: { onRender: (value: ReturnType<typeof useWorkspace>) => void }): ReactNode {
        const workspace = useWorkspace();
        useEffect(() => {
            onRender(workspace);
        }, [onRender, workspace]);
        return null;
    }

    it("publishes the workspace it renders, follows an explicit switch at once, and releases on unmount", async () => {
        let captured: ReturnType<typeof useWorkspace> | null = null;
        const container = document.createElement("div");
        const root = createRoot(container);

        await act(async () => {
            root.render(
                <WorkspaceProvider
                    initialWorkspaces={[workspace(HEALED, "Head Office"), workspace(RENDERED, REVOKED_NAME)]}
                    initialActiveId={RENDERED}
                >
                    <Capture onRender={(value) => { captured = value; }} />
                </WorkspaceProvider>,
            );
        });

        expect(renderedWorkspaceId()).toBe(RENDERED);
        expect(rememberedWorkspaceName(RENDERED)).toBe(REVOKED_NAME);

        let pinnedDuringOperation: number | null = null;
        await act(async () => {
            if (captured === null) throw new Error("WorkspaceProvider did not render");
            await captured.runSelectionChange(async (publishActiveWorkspace) => {
                publishActiveWorkspace(HEALED);
                pinnedDuringOperation = renderedWorkspaceId();
            });
        });

        expect(pinnedDuringOperation).toBe(HEALED);
        expect(renderedWorkspaceId()).toBe(HEALED);

        await act(async () => root.unmount());
        expect(renderedWorkspaceId()).toBeNull();
    });
});

describe("persisted drafts stay with the workspace they were started in", () => {
    it("never offers a draft from another workspace for resume", () => {
        const origin = { userId: 7, workspaceId: RENDERED, formType: "note", scope: "global" };
        writeDraft(draftKey(origin), {
            version: 1,
            scope: "global",
            formType: "note",
            data: { content: "Quarterly plan", personId: null, dealId: null },
        });

        expect(listFreshDrafts({ userId: 7, workspaceId: HEALED })).toEqual([]);
        expect(readDraft({ ...origin, workspaceId: HEALED }, { version: 1 })).toBeNull();
        expect(listFreshDrafts({ userId: 7, workspaceId: RENDERED }).map(({ key }) => key)).toEqual([
            draftKey(origin),
        ]);
    });
});

describe("a duplicate check its workspace refused", () => {
    function render(workspaceRefusal: ApiError | null): string {
        return renderToStaticMarkup(
            <NextIntlClientProvider locale="en" messages={{ ...commonEn, ...errorsEn }} timeZone="UTC">
                <DuplicatePreflightWarning
                    kind="deal"
                    status="error"
                    response={null}
                    onRetry={() => undefined}
                    workspaceRefusal={workspaceRefusal}
                />
            </NextIntlClientProvider>,
        );
    }

    it("explains the refusal instead of offering a retry that cannot succeed", async () => {
        stubBackend((url, method) => method === "POST" ? refusal() : null);
        bindRenderedWorkspace(owner(), RENDERED);
        rememberWorkspaceNames([workspace(RENDERED, REVOKED_NAME)]);
        const error = await refusedNote();
        if (!(error instanceof ApiError)) throw new Error("expected the refusal");
        const t = translator("en");

        const html = render(error);

        expect(html).toContain(
            t("ApiErrors.workspaceUnavailableNamed", { workspace: REVOKED_NAME }).replaceAll("'", "&#x27;"),
        );
        expect(html).not.toContain(commonEn.DuplicateWarning.retry);
    });

    it("keeps the retry for an ordinary failed check", () => {
        const html = render(null);

        expect(html).toContain(commonEn.DuplicateWarning.failed);
        expect(html).toContain(commonEn.DuplicateWarning.retry);
    });
});

function backendSource(relativePath: string): string {
    return readFileSync(path.resolve(process.cwd(), "..", relativePath), "utf8");
}

/** The string literals passed to one Java call, joined where the source concatenates them. */
function javaStringArguments(source: string, call: string): string[] {
    const start = source.indexOf(call);
    if (start < 0) throw new Error(`${call} not found`);
    const end = source.indexOf(");", start);
    const literals = source.slice(start + call.length, end).match(/"(?:[^"\\]|\\.)*"(?:\s*\+\s*"(?:[^"\\]|\\.)*")*/g) ?? [];
    return literals.map((literal) => [...literal.matchAll(/"((?:[^"\\]|\\.)*)"/g)]
        .map((part) => part[1].replaceAll("\\\\", "\\"))
        .join(""));
}

/**
 * Expands the interceptor's route regexes, which use only alternation, one level of `(?:a|b)` groups
 * and `\d+` segments, into the frontend's `{id}` route patterns.
 */
function expandRouteRegex(regex: string): string[] {
    const alternatives: string[] = [];
    let depth = 0;
    let current = "";
    for (const character of regex) {
        if (character === "(") depth += 1;
        if (character === ")") depth -= 1;
        if (character === "|" && depth === 0) {
            alternatives.push(current);
            current = "";
        } else {
            current += character;
        }
    }
    alternatives.push(current);
    return alternatives.flatMap((alternative) => {
        const group = /\(\?:([^)]*)\)/.exec(alternative);
        const expanded = group
            ? group[1].split("|").map((option) => alternative.replace(group[0], option))
            : [alternative];
        return expanded.map((route) => route.replaceAll("\\d+", "{id}"));
    });
}

const INTERCEPTOR = "backend/src/main/java/ooo/klae/connex/backend/tenant/TenantResolutionInterceptor.java";

describe("the routes that keep their previous selection behaviour", () => {
    it("excludes exactly the routes WebConfig keeps away from the tenant interceptor", () => {
        const webConfig = backendSource("backend/src/main/java/ooo/klae/connex/backend/config/WebConfig.java");

        expect([...UNPINNED_WRITE_ROUTES.interceptorExcluded]).toEqual(
            javaStringArguments(webConfig, ".excludePathPatterns("),
        );
    });

    it("excludes exactly the routes the interceptor resolves or exempts on its own", () => {
        const interceptor = backendSource(INTERCEPTOR);
        const derived = [
            ...javaStringArguments(interceptor, "SELECTION_PATH = Pattern.compile("),
            "/api/ownership-offers/\\d+/(?:accept|decline)",
            ...javaStringArguments(interceptor, "ORGANIZATION_LIFECYCLE_PATH = Pattern.compile("),
            ...javaStringArguments(interceptor, "WORKSPACE_LIFECYCLE_PATH = Pattern.compile("),
        ].flatMap(expandRouteRegex);

        expect(interceptor).toContain('selfPath.matches("/api/ownership-offers/\\\\d+/(?:accept|decline)")');
        expect([...UNPINNED_WRITE_ROUTES.interceptorSelfResolved].sort()).toEqual(derived.sort());
    });

    it("excludes account and organization writes only where the ledger shows no workspace permission", () => {
        const ledger = readFileSync(path.resolve(process.cwd(), "..", "docs/backend/api-surface.tsv"), "utf8")
            .trim()
            .split("\n")
            .slice(1)
            .map((line) => line.split("\t"));
        const covered = ledger
            .filter(([method]) => method !== "GET" && method !== "HEAD" && method !== "OPTIONS")
            .map(([method, route, , , , authorization]) => ({
                method,
                route,
                concrete: route.replace(/\{[^}]+\}/g, "12"),
                authorization,
            }))
            .filter(({ concrete }) => UNPINNED_WRITE_ROUTES.accountAndOrganization
                .some((pattern) => matchesRoutePattern(pattern, concrete)));

        for (const pattern of UNPINNED_WRITE_ROUTES.accountAndOrganization) {
            expect(covered.some(({ concrete }) => matchesRoutePattern(pattern, concrete)), pattern).toBe(true);
        }
        expect(covered.map(({ method, concrete }) => `${method} ${concrete}`)).toEqual(expect.arrayContaining([
            "PATCH /api/users/me",
            "PATCH /api/users/me/locale",
            "PUT /api/users/me/profile-picture",
            "PATCH /api/orgs/12",
            "POST /api/account/connections/12/authorize",
            "POST /api/account/connections/12/pause",
            "POST /api/account/connections/12/resume",
            "DELETE /api/account/connections/12",
            "POST /api/account/connections/native/12/pairing",
            "DELETE /api/account/connections/native/12/pairing",
        ]));
        expect(covered.filter(({ authorization }) => /\b[A-Z]+(?:_[A-Z]+)+\b/.test(authorization))).toEqual([]);
        const connectionControls = covered.filter(({ route }) => route.startsWith("/api/account/connections/"));
        expect(connectionControls.map(({ route }) => route).sort()).toEqual([
            "/api/account/connections/native/{provider}/pairing",
            "/api/account/connections/native/{provider}/pairing",
            "/api/account/connections/{provider}",
            "/api/account/connections/{provider}/authorize",
            "/api/account/connections/{provider}/pause",
            "/api/account/connections/{provider}/resume",
        ]);
        for (const { route, authorization } of connectionControls) {
            const service = route === "/api/account/connections/native/{provider}/pairing"
                ? "NativeConnectService"
                : "ProviderConnectionService";
            expect(authorization).toMatch(new RegExp(`^${service}#[a-zA-Z]+:domain authorization$`));
        }
    });

    it.each([
        ["/api/notes", true],
        ["/api/persons/12", true],
        ["/api/comment-threads", true],
        ["/api/account/connections/google/reviews/3", true],
        ["/api/dashboard-layout", true],
        ["/api/workspaces/12/members", true],
        ["/api/users/me?view=full", false],
        ["/api/users/me/locale", false],
        ["/api/orgs/12", false],
        ["/api/orgs/12/data-subject-requests", false],
        ["/api/notification-preferences/quiet-hours", false],
        ["/api/account/connections/google/pause", false],
        ["/api/account/connections/google", false],
        ["/api/account/connections/native/google/pairing", false],
        ["/api/account/connections/google/sync", true],
        ["/api/account/connections/google/captured/3/approve", true],
        ["/api/account/connections/google/capture-policy", true],
        ["/api/workspaces/12/switch", false],
        ["/api/auth/logout", false],
    ])("treats a write to %s as workspace-bound: %s", (route, pinned) => {
        expect(pinsToRenderedWorkspace(route)).toBe(pinned);
    });
});

describe("with the shell rendering a revoked workspace and the cookie healed", () => {
    beforeEach(() => {
        bindRenderedWorkspace(owner(), RENDERED);
    });

    it("leaves account and organization writes on the cookie, as before", async () => {
        const requests = stubBackend((url) => url.endsWith("/profile-picture")
            ? json({ id: 7, profilePictureUrl: "/api/attachments/3" })
            : null);

        await updateMyTimezone("Asia/Tokyo");
        await updateMyLocale("ja");
        await uploadCurrentUserProfilePicture(new File(["image"], "me.png", { type: "image/png" }));
        await updateOrganizationIdentity(12, "Northwind", "Northwind Old", 3);
        await updateNotificationPreferences([]);
        await beginProviderConnection("google");
        await pauseProviderConnection("google");
        await resumeProviderConnection("google");
        await disconnectProviderConnection("microsoft");
        await beginManagedPairing("google");
        await cancelManagedPairing("microsoft");

        expect(requests.map(({ url, method, workspace }) => [method, url, workspace])).toEqual([
            ["PATCH", "/api/users/me", String(HEALED)],
            ["PATCH", "/api/users/me/locale", String(HEALED)],
            ["PUT", "/api/users/me/profile-picture", String(HEALED)],
            ["PATCH", "/api/orgs/12", String(HEALED)],
            ["PUT", "/api/notification-preferences", String(HEALED)],
            ["POST", "/api/account/connections/google/authorize", String(HEALED)],
            ["POST", "/api/account/connections/google/pause", String(HEALED)],
            ["POST", "/api/account/connections/google/resume", String(HEALED)],
            ["DELETE", "/api/account/connections/microsoft", String(HEALED)],
            ["POST", "/api/account/connections/native/google/pairing", String(HEALED)],
            ["DELETE", "/api/account/connections/native/microsoft/pairing", String(HEALED)],
        ]);
    });

    it("pins a content write and reports its refusal, but never re-codes an account refusal", async () => {
        const requests = stubBackend((url, method) => {
            if (method === "GET") return null;
            const pinned = url.endsWith("/reviews/3") ? RENDERED : HEALED;
            return json({ code: "FORBIDDEN", message: `Not a member of workspace ${pinned}` }, 403);
        });

        const review = await decideCaptureReview("google", 3, { action: "ignore", rememberExact: false, version: 1 }).then(
            () => null,
            (error: unknown) => error,
        );
        const timezone = await updateMyTimezone("Asia/Tokyo").then(() => null, (error: unknown) => error);

        expect(requests.map(({ workspace }) => workspace)).toEqual([String(RENDERED), String(HEALED)]);
        expect(isWorkspaceUnavailableError(review)).toBe(true);
        expect(timezone).toMatchObject({ status: 403, code: "FORBIDDEN" });
    });
});

describe("a write that waits on its request identity before sending", () => {
    function stubHeldCsrf(): { requests: Recorded[]; release: () => void } {
        const requests: Recorded[] = [];
        const held: { release: () => void } = { release: () => undefined };
        let firstCsrf = true;
        const csrf = () => json({ token: "csrf-token", headerName: "X-XSRF-TOKEN", requestIdentity: "identity" });
        vi.stubGlobal("fetch", vi.fn(async (input: string | URL | Request, init?: RequestInit) => {
            const url = input instanceof Request ? input.url : String(input);
            if (url.endsWith("/api/auth/csrf")) {
                if (!firstCsrf) return csrf();
                firstCsrf = false;
                return new Promise<Response>((resolve) => {
                    held.release = () => resolve(csrf());
                });
            }
            const method = init?.method ?? "GET";
            requests.push({ url, method, workspace: new Headers(init?.headers).get("X-Workspace-Id") });
            if (url.endsWith(`/api/workspaces/${HEALED}/switch`)) {
                setWorkspaceCookie(HEALED);
                return new Response("", { status: 200 });
            }
            return json({ turnId: 3, sessionId: 4, generationHandle: "handle", status: "accepted", id: 41 });
        }));
        return { requests, release: () => held.release() };
    }

    beforeEach(() => {
        setWorkspaceCookie(RENDERED);
    });

    it("is abandoned when a workspace switch begins while it waits", async () => {
        const shell = owner();
        bindRenderedWorkspace(shell, RENDERED);
        const backend = stubHeldCsrf();

        const turn = startAiChatTurn(4, { content: "Pricing question for the board", pageContext: [] }).then(
            () => null,
            (error: unknown) => error,
        );
        await switchWorkspace(HEALED);
        bindRenderedWorkspace(shell, HEALED);
        backend.release();

        expect(await turn).toBeInstanceOf(Error);
        expect(backend.requests.filter(({ url }) => url.endsWith("/turns"))).toEqual([]);
    });

    it("carries the workspace it was started in even if the selection moves before it sends", async () => {
        const shell = owner();
        bindRenderedWorkspace(shell, RENDERED);
        const backend = stubHeldCsrf();

        const turn = startAiChatTurn(4, { content: "Pricing question for the board", pageContext: [] }).then(
            () => null,
            (error: unknown) => error,
        );
        bindRenderedWorkspace(shell, HEALED);
        setWorkspaceCookie(HEALED);
        backend.release();
        await turn;

        expect(backend.requests.filter(({ url }) => url.endsWith("/turns"))).toEqual([
            { url: "/api/ai/assistant/sessions/4/turns", method: "POST", workspace: String(RENDERED) },
        ]);
    });

    it("pins a report write before its identity wait", async () => {
        const shell = owner();
        bindRenderedWorkspace(shell, RENDERED);
        const backend = stubHeldCsrf();

        const snapshot = createReportSnapshot(14, {});
        bindRenderedWorkspace(shell, HEALED);
        backend.release();
        await snapshot;

        expect(backend.requests.filter(({ url }) => url.endsWith("/snapshots"))).toEqual([
            { url: "/api/reports/14/snapshots", method: "POST", workspace: String(RENDERED) },
        ]);
    });
});

describe("creating a workspace from the shell", () => {
    function stubWorkspaces(memberships: number[]): Recorded[] {
        return stubBackend((url, method) => {
            if (url !== "/api/workspaces") return null;
            return method === "GET"
                ? json({
                    workspaces: memberships.map((id) => workspace(id, id === RENDERED ? REVOKED_NAME : "Head Office")),
                    activeWorkspaceId: HEALED,
                })
                : json(workspace(77, "Field team"));
        });
    }

    it("refuses before sending when the workspace on screen is gone, naming it", async () => {
        bindRenderedWorkspace(owner(), RENDERED);
        rememberWorkspaceNames([workspace(RENDERED, REVOKED_NAME)]);
        const requests = stubWorkspaces([HEALED]);

        const error = await createWorkspace("Field team").then(() => null, (failure: unknown) => failure);

        expect(error).toMatchObject({ code: WORKSPACE_UNAVAILABLE_CODE, workspaceId: RENDERED, workspaceName: REVOKED_NAME });
        expect(requests.filter(({ method }) => method === "POST")).toEqual([]);
    });

    it("creates from the workspace on screen when the viewer still belongs to it", async () => {
        bindRenderedWorkspace(owner(), RENDERED);
        const requests = stubWorkspaces([HEALED, RENDERED]);

        await createWorkspace("Field team");

        expect(requests.filter(({ method }) => method === "POST")).toEqual([
            { url: "/api/workspaces", method: "POST", workspace: String(RENDERED) },
        ]);
    });

    it("abandons creation when the account changes during the membership preflight", async () => {
        bindRenderedWorkspace(owner(), RENDERED);
        synchronizeBrowserAccount(1);
        const requests: Recorded[] = [];
        let releasePreflight: (response: Response) => void = () => undefined;
        const preflight = new Promise<Response>((resolve) => { releasePreflight = resolve; });
        vi.stubGlobal("fetch", vi.fn(async (input: string | URL | Request, init?: RequestInit) => {
            const url = input instanceof Request ? input.url : String(input);
            if (url.endsWith("/api/auth/csrf")) {
                return json({ token: "csrf-token", headerName: "X-XSRF-TOKEN", requestIdentity: "identity" });
            }
            const method = init?.method ?? "GET";
            requests.push({ url, method, workspace: new Headers(init?.headers).get("X-Workspace-Id") });
            return method === "GET" ? preflight : json(workspace(77, "Field team"));
        }));

        const creation = createWorkspace("Field team").then(() => null, (error: unknown) => error);
        await vi.waitFor(() => expect(requests).toContainEqual({
            url: "/api/workspaces", method: "GET", workspace: String(HEALED),
        }));
        synchronizeBrowserAccount(2);
        releasePreflight(json({
            workspaces: [workspace(RENDERED, REVOKED_NAME)],
            activeWorkspaceId: RENDERED,
        }));

        expect(await creation).toBeInstanceOf(Error);
        expect(requests.filter(({ method }) => method === "POST")).toEqual([]);
    });

    it("re-reads memberships even when the cookie names the workspace on screen", async () => {
        setWorkspaceCookie(RENDERED);
        bindRenderedWorkspace(owner(), RENDERED);
        const requests = stubWorkspaces([RENDERED]);

        await createWorkspace("Field team");

        expect(requests).toEqual([
            { url: "/api/workspaces", method: "GET", workspace: String(RENDERED) },
            { url: "/api/workspaces", method: "POST", workspace: String(RENDERED) },
        ]);
    });

    it("refuses a revoked origin even when the cookie still names it", async () => {
        setWorkspaceCookie(RENDERED);
        bindRenderedWorkspace(owner(), RENDERED);
        const requests = stubWorkspaces([HEALED]);

        await expect(createWorkspace("Field team")).rejects.toMatchObject({
            code: WORKSPACE_UNAVAILABLE_CODE,
            workspaceId: RENDERED,
        });

        expect(requests).toEqual([{ url: "/api/workspaces", method: "GET", workspace: String(RENDERED) }]);
    });

    it("reports a server refusal when the origin is revoked after preflight", async () => {
        bindRenderedWorkspace(owner(), RENDERED);
        const requests = stubBackend((url, method) => {
            if (url !== "/api/workspaces") return null;
            return method === "GET"
                ? json({ workspaces: [workspace(RENDERED, REVOKED_NAME)], activeWorkspaceId: RENDERED })
                : refusal();
        });

        await expect(createWorkspace("Field team")).rejects.toMatchObject({
            code: WORKSPACE_UNAVAILABLE_CODE,
            workspaceId: RENDERED,
        });

        expect(requests.filter(({ method }) => method === "POST")).toEqual([
            { url: "/api/workspaces", method: "POST", workspace: String(RENDERED) },
        ]);
    });

    it.each([null, RENDERED])("omits the onboarding pin with no shell and cookie %s", async (cookie) => {
        setWorkspaceCookie(cookie);
        const requests = stubWorkspaces([]);

        await createWorkspace("Field team");

        expect(requests).toEqual([{ url: "/api/workspaces", method: "POST", workspace: null }]);
    });
});

class RefusingUpload {
    static sent: Array<{ workspace: string | null }> = [];
    status = 0;
    statusText = "";
    responseText = "";
    readonly upload = { addEventListener: () => undefined };
    private readonly headers = new Headers();
    private readonly listeners = new Map<string, () => void>();

    open(): void {}

    setRequestHeader(name: string, value: string): void {
        this.headers.append(name, value);
    }

    addEventListener(type: string, listener: () => void): void {
        this.listeners.set(type, listener);
    }

    abort(): void {}

    send(): void {
        RefusingUpload.sent.push({ workspace: this.headers.get("X-Workspace-Id") });
        this.status = 403;
        this.statusText = "Forbidden";
        this.responseText = JSON.stringify({ code: "FORBIDDEN", message: REFUSAL_MESSAGE });
        queueMicrotask(() => this.listeners.get("load")?.());
    }
}

describe("transports outside the JSON pipeline", () => {
    beforeEach(() => {
        bindRenderedWorkspace(owner(), RENDERED);
        rememberWorkspaceNames([workspace(RENDERED, REVOKED_NAME)]);
    });

    it("re-codes a refused CSV export from the workspace it carried", async () => {
        const requests = stubBackend((url, method) => method === "POST" ? refusal() : null);

        const error = await downloadCsv("/api/exports/deals/segment", "deals.csv", {
            method: "POST",
            body: JSON.stringify({ segmentId: 4 }),
        }).then(() => null, (failure: unknown) => failure);

        expect(requests.map(({ workspace }) => workspace)).toEqual([String(RENDERED)]);
        expect(error).toMatchObject({ code: WORKSPACE_UNAVAILABLE_CODE, workspaceName: REVOKED_NAME });
    });

    it("re-codes a refused report export from the workspace it carried", async () => {
        const requests = stubBackend((url, method) => method === "POST" ? refusal() : null);

        const error = await exportReportCsv(14, {}).then(() => null, (failure: unknown) => failure);

        expect(requests.map(({ workspace }) => workspace)).toEqual([String(RENDERED)]);
        expect(error).toMatchObject({ code: WORKSPACE_UNAVAILABLE_CODE, workspaceId: RENDERED });
    });

    it("re-codes a refused assistant attachment upload from the workspace it carried", async () => {
        stubBackend();
        RefusingUpload.sent = [];
        vi.stubGlobal("XMLHttpRequest", RefusingUpload);

        const error = await uploadAiChatAttachment(
            8,
            new File(["notes"], "brief.txt", { type: "text/plain" }),
            () => undefined,
        ).then(() => null, (failure: unknown) => failure);

        expect(RefusingUpload.sent).toEqual([{ workspace: String(RENDERED) }]);
        expect(error).toMatchObject({ code: WORKSPACE_UNAVAILABLE_CODE, workspaceId: RENDERED });
    });

    it("sends the explicit pin used by AskConnexProvider exactly once", async () => {
        stubBackend();
        RefusingUpload.sent = [];
        vi.stubGlobal("XMLHttpRequest", RefusingUpload);

        const error = await uploadAiChatAttachment(
            8,
            new File(["notes"], "brief.txt", { type: "text/plain" }),
            () => undefined,
            workspacePinnedRequest(RENDERED, { signal: new AbortController().signal }),
        ).then(() => null, (failure: unknown) => failure);

        expect(RefusingUpload.sent).toEqual([{ workspace: String(RENDERED) }]);
        expect(error).toMatchObject({ code: WORKSPACE_UNAVAILABLE_CODE, workspaceId: RENDERED });
    });

    it("uses a case-insensitive explicit upload pin instead of appending the rendered pin", async () => {
        stubBackend();
        RefusingUpload.sent = [];
        vi.stubGlobal("XMLHttpRequest", RefusingUpload);

        await expect(uploadAiChatAttachment(
            8,
            new File(["notes"], "brief.txt", { type: "text/plain" }),
            () => undefined,
            { headers: { "x-workspace-id": String(HEALED) } },
        )).rejects.toMatchObject({ code: "FORBIDDEN" });

        expect(RefusingUpload.sent).toEqual([{ workspace: String(HEALED) }]);
    });

    it.each(["9, 9", "not-an-id", "", "0", "2147483648"])(
        "refuses a malformed workspace pin %j before sending JSON, CSV or XHR",
        async (pin) => {
            const requests = stubBackend();
            RefusingUpload.sent = [];
            vi.stubGlobal("XMLHttpRequest", RefusingUpload);
            const init: RequestInit = { headers: { "X-Workspace-Id": pin } };

            await expect(createNote({ content: "Draft", visibility: "private", author: 7 }, init))
                .rejects.toThrow("X-Workspace-Id must contain one valid workspace id");
            await expect(downloadCsv("/api/exports/deals/segment", "deals.csv", { ...init, method: "POST" }))
                .rejects.toThrow("X-Workspace-Id must contain one valid workspace id");
            await expect(uploadAiChatAttachment(
                8,
                new File(["notes"], "brief.txt", { type: "text/plain" }),
                () => undefined,
                init,
            )).rejects.toThrow("X-Workspace-Id must contain one valid workspace id");

            expect(requests).toEqual([]);
            expect(RefusingUpload.sent).toEqual([]);
        },
    );

    it("refuses differently-cased duplicate workspace headers before sending", async () => {
        const requests = stubBackend();
        const headers: [string, string][] = [["X-Workspace-Id", "9"], ["x-workspace-id", "9"]];

        await expect(createNote({ content: "Draft", visibility: "private", author: 7 }, { headers }))
            .rejects.toThrow("X-Workspace-Id must contain one valid workspace id");

        expect(requests).toEqual([]);
    });

    it("pins a later save to the workspace its content was composed in", () => {
        const pinned = workspacePinnedRequest(RENDERED, { signal: new AbortController().signal });

        expect(new Headers(pinned.headers).get("X-Workspace-Id")).toBe(String(RENDERED));
        expect(pinned.signal).toBeDefined();
        expect(workspacePinnedRequest(null)).toEqual({});
    });
});

/**
 * Every surface that submits composed content, with the token proving how it reports a refused
 * pinned write. The pin itself lives in `app/lib/api.ts`, so each surface must reach the backend only
 * through it: no raw transport and no read of the workspace cookie.
 */
const COMPOSERS: ReadonlyArray<{ file: string; reports: string }> = [
    { file: "app/components/activity/notes/NoteDialog.tsx", reports: "useApiErrorToast" },
    { file: "app/components/activity/notes/NoteEditorView.tsx", reports: "isWorkspaceUnavailableError" },
    { file: "app/components/activity/tasks/TaskDialog.tsx", reports: "isWorkspaceUnavailableError" },
    { file: "app/components/activity/tasks/EditTaskSheet.tsx", reports: "useApiErrorToast" },
    { file: "app/components/activity/activities/ActivityDialog.tsx", reports: "useApiErrorToast" },
    { file: "app/components/activity/activities/EditActivitySheet.tsx", reports: "useApiErrorToast" },
    { file: "app/components/records/RecordComposers.tsx", reports: "useApiErrorToast" },
    { file: "app/components/actions/create/DealCreateContainer.tsx", reports: "useApiErrorToast" },
    { file: "app/components/records/deals/NewDealDialog.tsx", reports: "workspaceRefusal" },
    { file: "app/components/records/deals/EditDealSheet.tsx", reports: "useApiErrorToast" },
    { file: "app/components/actions/create/CompanyCreateContainer.tsx", reports: "useApiErrorToast" },
    { file: "app/components/records/companies/NewCompanyDialog.tsx", reports: "workspaceRefusal" },
    { file: "app/components/records/companies/EditCompanySheet.tsx", reports: "useApiErrorToast" },
    { file: "app/components/actions/create/ContactCreateContainer.tsx", reports: "useApiErrorToast" },
    { file: "app/components/records/contacts/NewContactDialog.tsx", reports: "workspaceRefusal" },
    { file: "app/components/records/contacts/EditContactSheet.tsx", reports: "useApiErrorToast" },
    { file: "app/components/actions/ActionOverlayHost.tsx", reports: "rendered.originWorkspaceId" },
    { file: "app/components/actions/QuickCreateLauncher.tsx", reports: "run(action.id" },
    { file: "app/components/actions/MobileCreateFlow.tsx", reports: "DialogForm" },
    { file: "app/components/calendar/QuickCreateHost.tsx", reports: "TaskDialog" },
    { file: "app/components/records/comments/CommentsSection.tsx", reports: "isWorkspaceUnavailableError" },
    { file: "app/components/import/ImportDialog.tsx", reports: "isWorkspaceUnavailableError" },
    { file: "app/components/import/InteractionHistoryImportDialog.tsx", reports: "isWorkspaceUnavailableError" },
    { file: "app/components/account/ConnectionsPanel.tsx", reports: "useApiErrorToast" },
    { file: "app/components/DraftResumeBridge.tsx", reports: "listFreshDrafts({ userId, workspaceId: activeWorkspaceId })" },
];

/** Files that read the workspace cookie only to look things up, never to address a write. */
const READ_ONLY_COOKIE_READERS = [
    "app/components/AuthForm.tsx",
    "app/components/activity/notes/MentionEditor.tsx",
    "app/components/activity/notes/editor/mentionData.ts",
    "app/sso/link/SsoLinkForm.tsx",
];

function source(relativePath: string): string {
    return readFileSync(path.resolve(process.cwd(), relativePath), "utf8");
}

function sourceFiles(directory: string): string[] {
    return readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
        const entryPath = path.join(directory, entry.name);
        if (entry.isDirectory()) return sourceFiles(entryPath);
        return /\.tsx?$/.test(entry.name) ? [entryPath] : [];
    });
}

describe("every composer path submits through the pinned pipeline", () => {
    it.each(COMPOSERS)("$file reaches the backend only through the shared client", ({ file, reports }) => {
        expect(existsSync(path.resolve(process.cwd(), file)), `${file} moved; update this inventory`).toBe(true);
        const contents = source(file);

        expect(contents).not.toMatch(/(?<!\w)fetch\(/);
        expect(contents).not.toContain("XMLHttpRequest");
        expect(contents).not.toContain("document.cookie");
        expect(contents).toContain(reports);
    });

    it("leaves the workspace cookie to the shared client and read-only lookups", () => {
        const readers = sourceFiles(path.resolve(process.cwd(), "app"))
            .map((file) => path.relative(process.cwd(), file))
            .filter((file) => file !== path.join("app", "lib", "api.ts"))
            .filter((file) => {
                const contents = source(file);
                return contents.includes("document.cookie")
                    && contents.includes("connex_workspace")
                    && /document\.cookie[\s\S]{0,40}connex_workspace|connex_workspace=[\s\S]{0,40}document\.cookie/.test(contents);
            })
            .sort();

        expect(readers).toEqual([...READ_ONLY_COOKIE_READERS].sort());
    }, 120_000);
});
