/** @vitest-environment jsdom */
import { existsSync, readdirSync, readFileSync } from "node:fs";
import path from "node:path";

import { act, type ReactNode } from "react";
import { createRoot } from "react-dom/client";
import { renderToStaticMarkup } from "react-dom/server";
import { createTranslator, NextIntlClientProvider } from "next-intl";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import {
    ApiError,
    clientRecoveryContext,
    createNote,
    getNotesPage,
    isWorkspaceUnavailableError,
    uploadAttachmentImage,
    WORKSPACE_UNAVAILABLE_CODE,
} from "@/app/lib/api";
import { userMessageFor, type MessageTranslator } from "@/app/lib/errorMessages";
import { draftKey, listFreshDrafts, readDraft, writeDraft } from "@/app/lib/formDrafts";
import {
    bindRenderedWorkspace,
    releaseRenderedWorkspace,
    rememberedWorkspaceName,
    rememberWorkspaceNames,
    renderedWorkspaceId,
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
        onRender(useWorkspace());
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
            .filter((file) => /document\.cookie[\s\S]{0,40}connex_workspace|connex_workspace=[\s\S]{0,40}document\.cookie/.test(source(file)))
            .sort();

        expect(readers).toEqual([...READ_ONLY_COOKIE_READERS].sort());
    });
});
