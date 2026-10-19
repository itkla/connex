/** @vitest-environment jsdom */
import { act, type ComponentProps, type ReactNode } from "react";
import { createRoot, type Root } from "react-dom/client";
import { Editor } from "@tiptap/core";
import StarterKit from "@tiptap/starter-kit";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { EditorToolbar, type ToolbarLabels } from "@/app/components/activity/notes/editor/EditorToolbar";
import { createMarkdownTableExtensions } from "@/app/components/activity/notes/editor/MarkdownTable";
import { createMarkdownImageExtension } from "@/app/components/activity/notes/editor/MarkdownImage";
import { NoteDialogForm } from "@/app/components/activity/notes/NoteDialog";
import type RichNoteEditor from "@/app/components/activity/notes/RichNoteEditor";
import CompaniesBrowser from "@/app/components/records/companies/CompaniesBrowser";
import type NewCompanyDialog from "@/app/components/records/companies/NewCompanyDialog";
import type { PendingContact } from "@/app/components/records/companies/CompanyContactsField";
import { isInlineManagedImageFile } from "@/app/lib/managed-image";
import { bindRenderedWorkspace, releaseRenderedWorkspace, rememberWorkspaceNames } from "@/app/lib/renderedWorkspace";
import { toastError } from "@/app/lib/toast";
import type { Note } from "@/app/lib/types";
import activityEn from "@/messages/en/activity.json";

const translate = vi.hoisted(() => Object.assign(vi.fn((key: string) => key), { has: () => false }));
const browser = vi.hoisted(() => ({
    items: [],
    selectedIds: new Set<number>(),
    filterState: {},
    noop: () => undefined,
}));
let companyDialog: ComponentProps<typeof NewCompanyDialog> | null = null;
let noteContent: string | null = null;

vi.mock("next/navigation", () => ({
    useRouter: () => ({ refresh: browser.noop, push: browser.noop }),
}));
vi.mock("next-intl", () => ({ useTranslations: () => translate, useLocale: () => "en" }));
vi.mock("motion/react", () => ({ useReducedMotion: () => true }));
vi.mock("@/app/lib/toast", () => ({ toastError: vi.fn(), toastInfo: vi.fn(), toastSuccess: vi.fn() }));
vi.mock("@/app/lib/managed-image", async (importOriginal) => ({
    ...await importOriginal<typeof import("@/app/lib/managed-image")>(),
    isInlineManagedImageFile: vi.fn(async () => true),
}));
vi.mock("@/app/hooks/useWorkspace", () => ({ useWorkspace: () => ({ activeWorkspaceId: 9 }) }));
vi.mock("@/app/hooks/usePermissions", () => ({ usePermission: () => true }));
vi.mock("@/app/hooks/useActions", () => ({
    useActions: () => ({ actions: [], pendingIds: new Set(), context: { user: { id: 7 } }, run: browser.noop }),
    useActionSelection: () => undefined,
}));
vi.mock("@/app/hooks/useRecordsBrowser", () => ({
    useRecordsBrowser: () => ({
        displayMode: "grid", effectiveDisplayMode: "grid", setDisplayMode: browser.noop,
        filterState: browser.filterState, setFilterState: browser.noop,
        selectedIds: browser.selectedIds, setSelectedIds: browser.noop,
        deleteDialogOpen: false, setDeleteDialogOpen: browser.noop,
    }),
}));
vi.mock("@/app/hooks/useServerRecords", () => ({
    useServerRecords: () => ({
        items: browser.items, total: 1, loading: false, page: 1, size: 25, query: "",
        sortKey: null, sortDirection: "asc", revision: 0,
        setPage: browser.noop, setSize: browser.noop, setQuery: browser.noop, applyQuery: browser.noop,
        onSortChange: browser.noop, applySort: browser.noop, reload: browser.noop, patchItem: browser.noop,
    }),
}));
vi.mock("@/app/components/records/useRecordPeekController", () => ({
    useRecordPeekController: () => ({ activeId: null, drawer: null }),
}));
vi.mock("@/app/components/records/CustomFieldColumns", () => ({
    useCustomFieldColumns: () => ({ columns: [], addColumnSlot: null }),
}));
vi.mock("@/app/components/PageShell", () => ({
    PageShell: ({ children }: { children?: ReactNode }) => <>{children}</>,
}));
vi.mock("@/app/components/motion/Rise", () => ({ default: () => null }));
vi.mock("@/app/components/records/companies/QuickEditCompanySheet", () => ({ default: () => null }));
vi.mock("@/app/components/records/ArchiveRecordDialog", () => ({ default: () => null }));
vi.mock("@/app/components/records/BulkTagDialog", () => ({ default: () => null }));
vi.mock("@/app/components/records/BulkAssignOwnerDialog", () => ({ default: () => null }));
vi.mock("@/app/components/records/companies/NewCompanyDialog", () => ({
    default: (props: ComponentProps<typeof NewCompanyDialog>) => {
        companyDialog = props;
        return null;
    },
}));
vi.mock("@/app/components/activity/notes/RichNoteEditor", () => ({
    default: ({ ensureNoteId, value }: ComponentProps<typeof RichNoteEditor>) => {
        noteContent = value;
        return <EditorToolbar editor={editor} labels={labels} compact ensureNoteId={ensureNoteId} />;
    },
}));
vi.mock("@/components/ui/dialog-status-cover", () => ({
    DialogStatusCover: () => null,
    resolveDialogStatus: () => "idle",
}));
vi.mock("@/components/ui/segmented-control", () => ({ SegmentedControl: () => null }));

const ORIGIN = 9;
const NEXT = 5;
const shell = {};
const image = new File(["image"], "board.png", { type: "image/png" });
const labels: ToolbarLabels = {
    ...activityEn.ActivityNotesEditor,
    h1: "Heading 1", h2: "Heading 2", h3: "Heading 3",
    file: "File", fileSearchPlaceholder: "Search", fileSearching: "Searching",
    fileNoResults: "No results", fileSearchError: "Search failed", fileRetry: "Retry", filePickerAria: "Files",
};

type Recorded = { path: string; workspace: string | null; body: BodyInit | null | undefined; signal?: AbortSignal | null };

function deferred<T>() {
    let resolve!: (value: T) => void;
    const promise = new Promise<T>((done) => { resolve = done; });
    return { promise, resolve };
}

function json(body: unknown, status = 200): Response {
    return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

function stubBackend(heldPath?: string) {
    const requests: Recorded[] = [];
    const held = deferred<Response>();
    const started = deferred<void>();
    let hold = true;
    vi.stubGlobal("fetch", vi.fn(async (input: string | URL | Request, init?: RequestInit) => {
        const path = new URL(input instanceof Request ? input.url : String(input), "http://localhost").pathname;
        if (path === "/api/auth/csrf") {
            return json({ token: "csrf-token", headerName: "X-XSRF-TOKEN", requestIdentity: "identity" });
        }
        if (init?.method && init.method !== "GET") {
            requests.push({ path, workspace: new Headers(init.headers).get("X-Workspace-Id"), body: init.body, signal: init.signal });
        }
        if (path === heldPath && hold) {
            hold = false;
            started.resolve();
            return held.promise;
        }
        if (path.endsWith("/facets")) return json({ industries: [], owners: [], warmth: [] });
        if (path === "/api/attachments/upload-image") return json({ id: 41, url: "/api/attachments/41/content", fileName: image.name });
        if (path === "/api/notes" || path === "/api/companies" || path === "/api/persons" || path.endsWith("/logo") || path.endsWith("/profile-picture")) {
            return json({ id: 41, logoUrl: "/api/logo", imageUrl: "/api/picture" });
        }
        return json([]);
    }));
    return { requests, held, started };
}

let root: Root;
let editor: Editor | null = null;

async function render(child: ReactNode) {
    await act(async () => root.render(child));
}

function switchWorkspace() {
    document.cookie = `connex_workspace=${NEXT}; path=/`;
    bindRenderedWorkspace(shell, NEXT);
}

function dialog(): ComponentProps<typeof NewCompanyDialog> {
    if (companyDialog === null) throw new Error("The company dialog did not render");
    return companyDialog;
}

beforeEach(() => {
    vi.stubGlobal("IS_REACT_ACT_ENVIRONMENT", true);
    const container = document.createElement("div");
    document.body.appendChild(container);
    root = createRoot(container);
    bindRenderedWorkspace(shell, ORIGIN);
    document.cookie = `connex_workspace=${ORIGIN}; path=/`;
    companyDialog = null;
    noteContent = null;
    vi.stubGlobal("matchMedia", (query: string) => ({
        media: query, matches: false, addEventListener: browser.noop, removeEventListener: browser.noop,
    }));
});

afterEach(async () => {
    await act(async () => root.unmount());
    editor?.destroy();
    editor = null;
    releaseRenderedWorkspace(shell);
    document.body.innerHTML = "";
    document.cookie = "connex_workspace=; path=/; max-age=0";
    window.localStorage.clear();
    vi.unstubAllGlobals();
    vi.clearAllMocks();
});

describe("note image upload across a workspace switch", () => {
    it.each([
        ["note creation", false, 403],
        ["image upload", false, 403],
        ["image upload", true, 403],
        ["image upload", true, 500],
    ] as const)("reports a %s failure with existing note %s and status %s while keeping the content", async (stage, existing, status) => {
        const heldPath = stage === "note creation" ? "/api/notes" : "/api/attachments/upload-image";
        const backend = stubBackend(heldPath);
        rememberWorkspaceNames([{ id: ORIGIN, name: "Northwind Field Team" }]);
        document.cookie = `connex_workspace=${NEXT}; path=/`;
        editor = new Editor({
            extensions: [StarterKit, ...createMarkdownTableExtensions(), createMarkdownImageExtension("Image failed")],
            content: "<p>Origin draft</p>",
        });
        const note: Note | null = existing ? {
            id: 41,
            author: 7,
            content: "Origin draft",
            createdAt: "2026-10-18T00:00:00Z",
            updatedAt: "2026-10-18T00:00:00Z",
        } : null;
        const onClose = vi.fn();
        await render(
            <NoteDialogForm
                note={note} persons={[]} deals={[]} currentUserId={7}
                defaultPerson={null} defaultDeal={null} defaultContent="Origin draft"
                onSubmittingChange={browser.noop} onCancel={browser.noop} onClose={onClose}
            />,
        );
        await act(async () => document.querySelector<HTMLButtonElement>(`button[aria-label="${labels.image}"]`)?.click());
        const input = document.querySelector<HTMLInputElement>('input[type="file"]');
        if (input === null) throw new Error("The image input did not render");
        await act(async () => {
            Object.defineProperty(input, "files", { value: [image] });
            input.dispatchEvent(new Event("change", { bubbles: true }));
        });
        await backend.started.promise;
        await act(async () => {
            backend.held.resolve(status === 403
                ? json({ code: "FORBIDDEN", message: `Not a member of workspace ${ORIGIN}` }, status)
                : json({ code: "INTERNAL_ERROR" }, status));
        });

        expect(backend.requests.map(({ path, workspace }) => ({ path, workspace }))).toEqual([
            ...existing ? [] : [{ path: "/api/notes", workspace: String(ORIGIN) }],
            ...stage === "note creation" ? [] : [{ path: "/api/attachments/upload-image", workspace: String(ORIGIN) }],
        ]);
        if (status === 403) {
            expect(toastError).toHaveBeenCalledExactlyOnceWith(
                "ApiErrors.title",
                { description: "ApiErrors.workspaceUnavailableNamed" },
            );
            expect(translate).toHaveBeenCalledWith("ApiErrors.workspaceUnavailableNamed", { workspace: "Northwind Field Team" });
        } else {
            expect(toastError).toHaveBeenCalledExactlyOnceWith(labels.imageUploadFailed);
        }
        expect(noteContent).toBe("Origin draft");
        expect(editor.getHTML()).toBe("<p>Origin draft</p>");
        expect(onClose).not.toHaveBeenCalled();
        expect(input.isConnected).toBe(true);
    });

    it.each(["note creation", "file validation"])("keeps the origin while %s is pending", async (waitingFor) => {
        const backend = stubBackend(waitingFor === "note creation" ? "/api/notes" : undefined);
        const validation = deferred<boolean>();
        const controller = new AbortController();
        if (waitingFor === "file validation") vi.mocked(isInlineManagedImageFile).mockReturnValueOnce(validation.promise);
        editor = new Editor({
            extensions: [StarterKit, ...createMarkdownTableExtensions(), createMarkdownImageExtension("Image failed")],
            content: "<p>Origin draft</p>",
        });
        await render(
            <NoteDialogForm
                note={null} persons={[]} deals={[]} currentUserId={7}
                defaultPerson={null} defaultDeal={null} defaultContent="Origin draft"
                requestInit={{ signal: controller.signal }}
                onSubmittingChange={browser.noop} onCancel={browser.noop} onClose={browser.noop}
            />,
        );
        await act(async () => document.querySelector<HTMLButtonElement>(`button[aria-label="${labels.image}"]`)?.click());
        const input = document.querySelector<HTMLInputElement>('input[type="file"]');
        if (input === null) throw new Error("The image input did not render");
        await act(async () => {
            Object.defineProperty(input, "files", { value: [image] });
            input.dispatchEvent(new Event("change", { bubbles: true }));
        });
        if (waitingFor === "note creation") {
            await backend.started.promise;
            expect(backend.requests).toHaveLength(1);
        } else {
            expect(backend.requests).toEqual([]);
        }

        switchWorkspace();
        await act(async () => {
            backend.held.resolve(json({ id: 41 }));
            validation.resolve(true);
        });

        expect(backend.requests.map(({ path, workspace }) => ({ path, workspace }))).toEqual([
            { path: "/api/notes", workspace: String(ORIGIN) },
            { path: "/api/attachments/upload-image", workspace: String(ORIGIN) },
        ]);
        expect(backend.requests[0].signal).toBe(controller.signal);
        expect(backend.requests[0].body).toContain("Origin draft");
        expect(editor.getHTML()).toContain('/api/attachments/41/content');
    });
});

describe("company creation with a logo and staged contacts across a workspace switch", () => {
    it.each(["/api/companies", "/api/companies/41/logo", "/api/persons"])("keeps every write in the origin while %s is pending", async (heldPath) => {
        const backend = stubBackend(heldPath);
        await render(<CompaniesBrowser savedViews={[]} defaultView={null} />);
        await act(async () => {
            dialog().setPayload({ name: "Origin company" });
            dialog().setLogoFile(image);
        });
        const contacts: PendingContact[] = ["First", "Second"].map((name) => ({
            tempId: name, name, email: `${name}@example.com`, title: "Origin title", phone: "",
            imageFile: image, duplicateReviewSignature: null, duplicateReviewToken: null,
        }));
        let submitted: void | Promise<void> = undefined;
        await act(async () => {
            submitted = dialog().createNewCompany(null, contacts);
            await backend.started.promise;
        });

        switchWorkspace();
        await act(async () => {
            backend.held.resolve(json({ id: 41, logoUrl: "/api/logo", imageUrl: "/api/picture" }));
            await submitted;
        });

        expect(backend.requests.map(({ path, workspace }) => ({ path, workspace }))).toEqual([
            { path: "/api/companies", workspace: String(ORIGIN) },
            { path: "/api/companies/41/logo", workspace: String(ORIGIN) },
            { path: "/api/persons", workspace: String(ORIGIN) },
            { path: "/api/persons/41/profile-picture", workspace: String(ORIGIN) },
            { path: "/api/persons", workspace: String(ORIGIN) },
            { path: "/api/persons/41/profile-picture", workspace: String(ORIGIN) },
        ]);
        expect(backend.requests[0].body).toContain("Origin company");
        expect(backend.requests.filter(({ path }) => path === "/api/persons").map(({ body }) => JSON.parse(String(body))))
            .toMatchObject([{ name: "First", companyId: 41 }, { name: "Second", companyId: 41 }]);
    });
});
