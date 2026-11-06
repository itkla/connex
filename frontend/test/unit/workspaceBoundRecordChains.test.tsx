/** @vitest-environment jsdom */
import { createHash, webcrypto } from "node:crypto";
import { act, cloneElement, isValidElement, useEffect, useState, type ComponentProps, type ReactNode } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import CompanyCreateContainer from "@/app/components/actions/create/CompanyCreateContainer";
import ContactCreateContainer from "@/app/components/actions/create/ContactCreateContainer";
import DealCreateContainer from "@/app/components/actions/create/DealCreateContainer";
import CalendarNewDealContainer from "@/app/components/calendar/CalendarNewDealContainer";
import { RecordActivityComposer, RecordTaskComposer } from "@/app/components/records/RecordComposers";
import ContactsBrowser from "@/app/components/records/contacts/ContactsBrowser";
import ContactsGrid from "@/app/components/records/companies/ContactsGrid";
import CompanyActionsMenu from "@/app/components/records/companies/CompanyActionsMenu";
import ContactCard from "@/app/components/records/contacts/ContactCard";
import EditContactSheet from "@/app/components/records/contacts/EditContactSheet";
import EditCompanySheet from "@/app/components/records/companies/EditCompanySheet";
import EditDealSheet from "@/app/components/records/deals/EditDealSheet";
import DealsBrowser from "@/app/components/records/deals/DealsBrowser";
import DealActionsMenu from "@/app/components/records/deals/DealActionsMenu";
import DealLineItems from "@/app/components/records/deals/DealLineItems";
import { submitDealDraftUpdates, toDealDraft } from "@/app/components/records/deals/dealEditConflict";
import type ContactEditConflictDialog from "@/app/components/records/contacts/ContactEditConflictDialog";
import type DealEditConflictDialog from "@/app/components/records/deals/DealEditConflictDialog";
import EditPipelineSheet from "@/app/components/records/pipelines/EditPipelineSheet";
import PipelinesBrowser from "@/app/components/records/pipelines/PipelinesBrowser";
import type TaskDialog from "@/app/components/activity/tasks/TaskDialog";
import type ActivityDialog from "@/app/components/activity/activities/ActivityDialog";
import type NewContactDialog from "@/app/components/records/contacts/NewContactDialog";
import type NewCompanyDialog from "@/app/components/records/companies/NewCompanyDialog";
import type NewDealDialog from "@/app/components/records/deals/NewDealDialog";
import type NewPipelineDialog from "@/app/components/records/pipelines/NewPipelineDialog";
import type QuickEditSheet from "@/app/components/records/contacts/QuickEditSheet";
import type QuickEditCompanySheet from "@/app/components/records/companies/QuickEditCompanySheet";
import type QuickEditDealSheet from "@/app/components/records/deals/QuickEditDealSheet";
import type QuickEditPipelineSheet from "@/app/components/records/pipelines/QuickEditPipelineSheet";
import { TooltipProvider } from "@/components/ui/tooltip";
import { bindRenderedWorkspace, releaseRenderedWorkspace, workspacePinnedRequest } from "@/app/lib/renderedWorkspace";
import type { ColumnDef, RecordMenuModel } from "@/app/components/records/types";
import type { BusinessCardImportDraft, Contact, Deal, DealLineItem } from "@/app/lib/types";
import { company, contact, deal, deferred, image, json, ORIGIN, pipeline, shell, stage, stubBackend, switchWorkspace } from "./helpers/workspaceChainBackend";

const captured = vi.hoisted<{
    task: ComponentProps<typeof TaskDialog> | null;
    activity: ComponentProps<typeof ActivityDialog> | null;
    contact: ComponentProps<typeof NewContactDialog> | null;
    company: ComponentProps<typeof NewCompanyDialog> | null;
    deal: ComponentProps<typeof NewDealDialog> | null;
    pipeline: ComponentProps<typeof NewPipelineDialog> | null;
    contactSheet: ComponentProps<typeof QuickEditSheet> | null;
    contactConflict: ComponentProps<typeof ContactEditConflictDialog> | null;
    contactDismiss: ((open: boolean) => void) | null;
    contactConfirm: { open: boolean; onKeepEditing: () => void; onDiscard: () => void } | null;
    contactColumns: ColumnDef<Contact>[] | null;
    companySheet: ComponentProps<typeof QuickEditCompanySheet> | null;
    dealSheet: ComponentProps<typeof QuickEditDealSheet> | null;
    dealConflict: ComponentProps<typeof DealEditConflictDialog> | null;
    pipelineSheet: ComponentProps<typeof QuickEditPipelineSheet> | null;
}>(() => ({
    task: null,
    activity: null,
    contact: null,
    company: null,
    deal: null,
    pipeline: null,
    contactSheet: null,
    contactConflict: null,
    contactDismiss: null,
    contactConfirm: null,
    contactColumns: null,
    companySheet: null,
    dealSheet: null,
    dealConflict: null,
    pipelineSheet: null,
}));
const browser = vi.hoisted(() => ({
    activeWorkspaceId: 9,
    noop: () => undefined,
    refresh: vi.fn(),
    selectedIds: new Set([41]),
    filterState: {},
    items: new Array<Contact>(),
    search: new URLSearchParams(),
    showSelectionActions: false,
    renderContactGuard: false,
    patchItem: vi.fn((id: number, patch: Partial<Contact>) => {
        browser.items = browser.items.map((item) => item.id === id ? { ...item, ...patch } : item);
    }),
    replaceItem: vi.fn((id: number, saved: Contact) => {
        browser.items = browser.items.map((item) => item.id === id ? saved : item);
    }),
}));
const translate = vi.hoisted(() => Object.assign((key: string) => key, { has: () => false }));
vi.mock("next/navigation", () => ({
    useRouter: () => ({ refresh: browser.refresh, push: browser.noop, replace: browser.noop }),
    usePathname: () => "/records/pipelines", useSearchParams: () => browser.search,
}));
vi.mock("next-intl", () => ({ useTranslations: () => translate, useTimeZone: () => "UTC", useLocale: () => "en" }));
vi.mock("motion/react", () => ({ useReducedMotion: () => true, motion: { div: ({ children }: { children?: ReactNode }) => <div>{children}</div> } }));
vi.mock("@/app/lib/toast", () => ({ toastError: vi.fn(), toastInfo: vi.fn(), toastSuccess: vi.fn(), toastWarn: vi.fn(), toastDismiss: vi.fn() }));
vi.mock("@/app/hooks/useWorkspace", () => ({ useWorkspace: () => ({ activeWorkspaceId: browser.activeWorkspaceId, workspaces: [] }) }));
vi.mock("@/app/hooks/usePermissions", () => ({ usePermission: () => true }));
vi.mock("@/app/hooks/useActions", () => ({
    useActions: () => ({ actions: [], pendingIds: new Set(), context: { user: { id: 7 } }, run: browser.noop }),
    useActionSelection: browser.noop,
}));
vi.mock("@/app/hooks/useRecordsBrowser", () => ({
    useRecordsBrowser: (options: { items?: unknown[] }) => ({
        displayMode: "grid", effectiveDisplayMode: "grid", setDisplayMode: browser.noop,
        filterState: browser.filterState, setFilterState: browser.noop,
        selectedIds: browser.selectedIds, setSelectedIds: browser.noop,
        deleteDialogOpen: false, setDeleteDialogOpen: browser.noop,
        query: "", setQuery: browser.noop, filteredItems: options.items ?? [], selectedItems: options.items ?? [],
    }),
}));
vi.mock("@/app/hooks/useServerRecords", () => ({
    useServerRecords: () => ({
        items: browser.items, total: 0, loading: false, page: 1, size: 25, query: "",
        sortKey: null, sortDirection: "asc", revision: 0,
        setPage: browser.noop, setSize: browser.noop, setQuery: browser.noop, applyQuery: browser.noop,
        onSortChange: browser.noop, applySort: browser.noop, reload: browser.noop, patchItem: browser.patchItem, replaceItem: browser.replaceItem,
    }),
}));
vi.mock("@/app/components/records/RecordActionMenu", () => ({
    RecordContextMenu: ({ children }: { children?: ReactNode }) => <>{children}</>,
    RecordActionMenuTrigger: ({ model }: { model: RecordMenuModel }) => <button onClick={model.onQuickEdit}>quickEdit</button>,
}));
vi.mock("@/app/components/records/useRecordPeekController", () => ({ useRecordPeekController: () => ({ activeId: null, drawer: null }) }));
vi.mock("@/app/components/records/CustomFieldColumns", () => ({ useCustomFieldColumns: () => ({ columns: [], addColumnSlot: null }) }));
vi.mock("@/app/components/PageShell", () => ({ PageShell: ({ children }: { children?: ReactNode }) => <>{children}</> }));
vi.mock("@/app/components/motion/Rise", () => ({
    default: ({ children }: { children?: ReactNode }) => {
        if (!browser.showSelectionActions || !isValidElement<{ selectionActions?: ReactNode; children?: ReactNode }>(children)) return null;
        const nested = children.props.children;
        if (isValidElement<{ columns?: ColumnDef<Contact>[]; entityLabel?: string }>(nested) && nested.props.entityLabel === "contact") {
            captured.contactColumns = nested.props.columns ?? null;
        }
        const actions = children.props.selectionActions ?? (isValidElement<{ selectionActions?: ReactNode }>(nested) ? nested.props.selectionActions : null);
        return <>{actions}</>;
    },
}));
vi.mock("@/components/ui/segmented-control", () => ({ SegmentedControl: () => null }));
vi.mock("@/components/ui/dialog-status-cover", async (importOriginal) => ({ ...await importOriginal<typeof import("@/components/ui/dialog-status-cover")>(), DialogStatusCover: () => null }));
vi.mock("@/app/components/records/ArchiveRecordDialog", () => ({ default: () => null }));
vi.mock("@/app/components/records/BulkTagDialog", () => ({ default: () => null }));
vi.mock("@/app/components/records/BulkAssignOwnerDialog", () => ({ default: () => null }));
vi.mock("@/app/components/records/contacts/NewContactDialog", () => ({ default: (props: ComponentProps<typeof NewContactDialog>) => { captured.contact = props; return null; } }));
vi.mock("@/app/components/records/companies/NewCompanyDialog", () => ({ default: (props: ComponentProps<typeof NewCompanyDialog>) => { captured.company = props; return null; } }));
vi.mock("@/app/components/records/deals/NewDealDialog", async (importOriginal) => ({ ...await importOriginal<typeof import("@/app/components/records/deals/NewDealDialog")>(), default: (props: ComponentProps<typeof NewDealDialog>) => { captured.deal = props; return null; } }));
vi.mock("@/app/components/records/pipelines/NewPipelineDialog", () => ({ default: (props: ComponentProps<typeof NewPipelineDialog>) => { captured.pipeline = props; return null; } }));
vi.mock("@/app/components/activity/tasks/TaskDialog", () => ({ default: (props: ComponentProps<typeof TaskDialog>) => { captured.task = props; return null; } }));
vi.mock("@/app/components/activity/activities/ActivityDialog", () => ({ default: (props: ComponentProps<typeof ActivityDialog>) => { captured.activity = props; return null; } }));
vi.mock("@/app/components/records/contacts/QuickEditSheet", async (importOriginal) => {
    const { default: ActualQuickEditSheet } = await importOriginal<typeof import("@/app/components/records/contacts/QuickEditSheet")>();
    return { default: (props: ComponentProps<typeof QuickEditSheet>) => {
        captured.contactSheet = props;
        return browser.renderContactGuard ? <ActualQuickEditSheet {...props} /> : <>{props.customFieldsSlot}</>;
    } };
});
vi.mock("@/app/components/records/contacts/ContactEditConflictDialog", () => ({ default: (props: ComponentProps<typeof ContactEditConflictDialog>) => { captured.contactConflict = props; return null; } }));
vi.mock("@/app/components/ConfirmDiscardDialog", () => ({
    default: (props: { open: boolean; onKeepEditing: () => void; onDiscard: () => void }) => {
        captured.contactConfirm = props;
        return null;
    },
}));
vi.mock("@/components/ui/drawer", () => {
    const Pass = ({ children }: { children?: ReactNode }) => <>{children}</>;
    return {
        Drawer: ({ children, onOpenChange }: { children?: ReactNode; onOpenChange: (open: boolean) => void }) => {
            captured.contactDismiss = onOpenChange;
            return <>{children}</>;
        },
        DrawerClose: ({ children }: { children?: ReactNode }) => <button onClick={() => captured.contactDismiss?.(false)}>{children}</button>,
        DrawerContent: Pass, DrawerDescription: Pass, DrawerFooter: Pass, DrawerHeader: Pass, DrawerTitle: Pass,
    };
});
vi.mock("@/app/components/records/companies/QuickEditCompanySheet", () => ({ default: (props: ComponentProps<typeof QuickEditCompanySheet>) => { captured.companySheet = props; return <>{props.customFieldsSlot}</>; } }));
vi.mock("@/app/components/records/deals/QuickEditDealSheet", () => ({ default: (props: ComponentProps<typeof QuickEditDealSheet>) => { captured.dealSheet = props; return <>{props.customFieldsSlot}</>; } }));
vi.mock("@/app/components/records/deals/DealEditConflictDialog", () => ({ default: (props: ComponentProps<typeof DealEditConflictDialog>) => { captured.dealConflict = props; return null; } }));
vi.mock("@/app/components/records/pipelines/QuickEditPipelineSheet", () => ({ default: (props: ComponentProps<typeof QuickEditPipelineSheet>) => { captured.pipelineSheet = props; return null; } }));

let root: Root;
function get<K extends keyof typeof captured>(key: K): NonNullable<typeof captured[K]> {
    const value = captured[key];
    if (value === null) throw new Error("Missing component: " + key);
    return value;
}
async function render(child: ReactNode) {
    await act(async () => root.render(<TooltipProvider>{child}</TooltipProvider>));
    await act(async () => { await new Promise((done) => setTimeout(done, 30)); });
}
async function holdAndSwitch(backend: ReturnType<typeof stubBackend>, submit: () => unknown, heldPath: string) {
    let pending: unknown;
    await act(async () => {
        pending = submit();
        await backend.started.promise;
    });
    switchWorkspace();
    await act(async () => { backend.held.resolve(backend.response(heldPath)); await pending; });
}
function expectWrites(backend: ReturnType<typeof stubBackend>, paths: string[]) {
    expect(backend.requests.map(({ path, workspace }) => ({ path, workspace }))).toEqual(
        paths.map((path) => ({ path, workspace: String(ORIGIN) })),
    );
}
beforeEach(() => {
    browser.activeWorkspaceId = 9;
    vi.stubGlobal("IS_REACT_ACT_ENVIRONMENT", true);
    vi.stubGlobal("crypto", webcrypto);
    vi.stubGlobal("matchMedia", (query: string) => ({ media: query, matches: false, addEventListener: browser.noop, removeEventListener: browser.noop }));
    const container = document.createElement("div");
    document.body.appendChild(container);
    root = createRoot(container);
    for (const key of Object.keys(captured)) Reflect.set(captured, key, null);
    browser.selectedIds = new Set([41]);
    browser.showSelectionActions = false;
    browser.renderContactGuard = false;
    browser.items = [];
    bindRenderedWorkspace(shell, ORIGIN);
    document.cookie = "connex_workspace=" + ORIGIN + "; path=/";
});
afterEach(async () => {
    await act(async () => root.unmount());
    releaseRenderedWorkspace(shell);
    document.body.innerHTML = "";
    window.localStorage.clear();
    window.sessionStorage.clear();
    document.cookie = "connex_workspace=; path=/; max-age=0";
    vi.unstubAllGlobals();
    vi.clearAllMocks();
});

describe("deal edit origins", () => {
    const updates = [41, 42].map((dealId) => ({ dealId, draft: toDealDraft(deal) }));

    it("captures one origin before the first batch write without caller options", async () => {
        const backend = stubBackend("/api/deals/41");
        await holdAndSwitch(backend, () => submitDealDraftUpdates(updates), "/api/deals/41");
        expectWrites(backend, ["/api/deals/41", "/api/deals/42"]);
    });

    it("retains an earlier caller origin and cancellation signal after a switch", async () => {
        const backend = stubBackend();
        const controller = new AbortController();
        const origin = workspacePinnedRequest(ORIGIN, { signal: controller.signal });
        switchWorkspace();
        await submitDealDraftUpdates(updates, origin);
        expectWrites(backend, ["/api/deals/41", "/api/deals/42"]);
        expect(backend.requests.every(({ init }) => init.signal === controller.signal)).toBe(true);
    });

    it.each(["current", "refused"] as const)("pins a conflict's %s read to the write origin after a switch", async (result) => {
        const path = "/api/deals/41";
        const current = { ...deal, version: 2, name: "Latest origin deal" };
        const backend = stubBackend(path, (requestPath, method) => requestPath === path && method === "GET"
            ? result === "current" ? json(current) : json({ code: "FORBIDDEN", message: `Not a member of workspace ${ORIGIN}` }, 403)
            : undefined);
        await render(<EditDealSheet deal={{ ...deal, version: 1 }} timezone="Asia/Tokyo" open onOpenChange={browser.noop} pipelines={[pipeline]} stagesByPipeline={{ 41: [stage] }} />);
        await act(async () => get("dealSheet").updateDraft(41, { name: "My unsaved draft" }));
        let pending: unknown;
        await act(async () => {
            pending = get("dealSheet").saveEdits();
            await backend.started.promise;
        });
        switchWorkspace();
        await act(async () => {
            backend.held.resolve(json({ code: "STALE_RECORD", message: "Changed", currentVersion: 2 }, 409));
            await pending;
        });
        expectWrites(backend, [path]);
        const reads = vi.mocked(fetch).mock.calls.filter(([input, init]) => String(input).endsWith(path) && (init?.method ?? "GET") === "GET");
        expect(reads).toHaveLength(1);
        expect(new Headers(reads[0]?.[1]?.headers).get("X-Workspace-Id")).toBe(String(ORIGIN));
        expect(get("dealSheet").drafts[41].name).toBe("My unsaved draft");
        const snapshot = get("dealConflict").conflict.current;
        if (result === "refused") {
            expect(snapshot).toBeNull();
        } else {
            expect(snapshot).toEqual(current);
            if (snapshot === null) throw new Error("Expected the current origin deal");
            await act(async () => get("dealConflict").onReload(snapshot));
            expect(get("dealSheet").drafts[41]).toMatchObject({ expectedVersion: 2, name: current.name });
        }
    });
});

describe("contact conflict drafts", () => {
    it.each([true, false])("reloads only the conflicted draft and protects other dirty contacts: %s", async (otherDirty) => {
        const first = { ...contact, version: 1, title: "Original title" };
        const second = { ...contact, id: 42, version: 5, name: "Other contact" };
        const latest = { ...first, version: 2, title: "New title" };
        let refused = false;
        stubBackend(undefined, (path, method) => {
            if (path === "/api/persons/41" && method === "GET") return json(refused ? latest : first);
            if (path === "/api/persons/42" && method === "GET") return json(second);
            if (path === "/api/persons/41" && method === "PUT") {
                refused = true;
                return json({ code: "STALE_RECORD", currentVersion: 2 }, 409);
            }
            return undefined;
        });
        browser.items = [first, second];
        browser.selectedIds = new Set([41, 42]);
        browser.showSelectionActions = true;
        browser.renderContactGuard = true;
        await render(<ContactsBrowser savedViews={[]} defaultView={null} />);
        const edit = [...document.querySelectorAll("button")].find((button) => button.textContent?.trim() === "quickEdit");
        if (!edit) throw new Error("Expected the contact Quick edit action");
        await act(async () => edit.click());
        await act(async () => {
            get("contactSheet").updateDraft(41, { phone: "Refused phone" });
            if (otherDirty) get("contactSheet").updateDraft(42, { title: "Other unsaved title" });
        });
        await act(async () => get("contactSheet").saveEdits());
        expect(get("contactConflict").conflict.current).toEqual(latest);
        await act(async () => get("contactConflict").onReload(latest));
        expect(get("contactSheet").drafts[41]).toMatchObject({ expectedVersion: 2, phone: latest.phone });
        expect(get("contactSheet").drafts[42].title).toBe(otherDirty ? "Other unsaved title" : second.title);
        const cancel = [...document.querySelectorAll("button")].find((button) => button.textContent?.trim() === "cancel");
        if (!cancel) throw new Error("Expected the sheet Cancel button");
        await act(async () => cancel.click());
        if (!otherDirty) {
            expect(get("contactConfirm").open).toBe(false);
            expect(get("contactSheet").editSheetOpen).toBe(false);
            return;
        }
        expect(get("contactConfirm").open).toBe(true);
        expect(get("contactSheet").editSheetOpen).toBe(true);
        await act(async () => get("contactConfirm").onKeepEditing());
        await act(async () => get("contactDismiss")(false));
        expect(get("contactConfirm").open).toBe(true);
        expect(get("contactSheet").drafts[42].title).toBe("Other unsaved title");
        await act(async () => get("contactConfirm").onDiscard());
        expect(get("contactSheet").editSheetOpen).toBe(false);
    });

    it("restores the table snapshot while retaining a refused inline title only in the conflict draft", async () => {
        const first = { ...contact, version: 1, title: "Original title", phone: "Original phone" };
        const latest = { ...first, version: 2, phone: "New phone" };
        stubBackend(undefined, (path, method) => {
            if (path === "/api/persons/41" && method === "GET") return json(latest);
            if (path === "/api/persons/41" && method === "PUT") return json({ code: "STALE_RECORD", currentVersion: 2 }, 409);
            return undefined;
        });
        browser.items = [first];
        browser.showSelectionActions = true;
        browser.renderContactGuard = true;
        await render(<ContactsBrowser savedViews={[]} defaultView={null} />);
        const inline = get("contactColumns").find((column) => column.key === "title")?.editable;
        if (!inline) throw new Error("Expected the title inline editor");
        await act(async () => inline.save(first, "Refused title"));
        expect(get("contactConflict").conflict.current).toEqual(latest);
        await act(async () => get("contactConflict").onKeepEditing());
        expect(get("contactSheet").drafts[41].title).toBe("Refused title");
        expect(browser.items[0]).toEqual(first);
        await act(async () => get("contactDismiss")(false));
        if (get("contactConfirm").open) await act(async () => get("contactConfirm").onDiscard());
        expect(get("contactSheet").editSheetOpen).toBe(false);
        expect(browser.items[0]?.title).toBe("Original title");
    });
});

describe("clearing contact inline fields", () => {
    it.each(["email", "phone", "title"] as const)("clears existing %s with an explicit empty value and adopts the cleared response", async (field) => {
        const first = { ...contact, version: 1, email: "contact@example.test", phone: "123456", title: "Director" };
        const saved = { ...first, version: 2, [field]: "" };
        const backend = stubBackend(undefined, (path, method) => path === "/api/persons/41" && method === "PUT" ? json(saved) : undefined);
        browser.items = [first];
        browser.showSelectionActions = true;
        await render(<ContactsBrowser savedViews={[]} defaultView={null} />);
        const inline = get("contactColumns").find((column) => column.key === field)?.editable;
        if (!inline) throw new Error("Expected the contact inline editor");
        await act(async () => inline.save(first, ""));
        expectWrites(backend, ["/api/persons/41"]);
        const body = backend.requests[0]?.init.body;
        if (typeof body !== "string") throw new Error("Expected the serialized contact update");
        expect(JSON.parse(body)).toMatchObject({ expectedVersion: 1, [field]: "" });
        expect(browser.items[0]).toEqual(saved);
        expect(browser.items[0]?.[field]).toBe("");
    });
});

describe("in-flight contact batches", () => {
    it("retains newer input through both responses and retries only the remaining contact draft", async () => {
        const first = { ...contact, version: 1 };
        const second = { ...contact, id: 42, version: 5, name: "Other contact" };
        const firstSaved = { ...first, version: 2, name: "First submitted" };
        const secondSaved = { ...second, version: 6, name: "Second submitted" };
        const backend = stubBackend("/api/persons/41", (path, method) => {
            if (path === "/api/persons/41" && method === "GET") return json(first);
            if (path === "/api/persons/42" && method === "GET") return json(second);
            if (path === "/api/persons/41" && method === "PUT") return json({ ...firstSaved, version: 3, name: "Newest first input" });
            if (path === "/api/persons/42" && method === "PUT") return json(secondSaved);
            return undefined;
        });
        const secondStarted = deferred<void>();
        const secondResponse = deferred<Response>();
        const transport = vi.mocked(fetch);
        const original = transport.getMockImplementation();
        if (!original) throw new Error("Expected the stub transport");
        transport.mockImplementation((input, init = {}) => {
            const response = original(input, init);
            if (String(input).endsWith("/api/persons/42") && init.method === "PUT") {
                secondStarted.resolve();
                return secondResponse.promise;
            }
            return response;
        });
        browser.items = [first, second];
        browser.selectedIds = new Set([41, 42]);
        browser.showSelectionActions = true;
        await render(<ContactsBrowser savedViews={[]} defaultView={null} />);
        const edit = [...document.querySelectorAll("button")].find((button) => button.textContent?.trim() === "quickEdit");
        if (!edit) throw new Error("Expected the multi-contact Quick edit action");
        await act(async () => edit.click());
        await act(async () => {
            get("contactSheet").updateDraft(41, { name: "First submitted" });
            get("contactSheet").updateDraft(42, { name: " Second submitted " });
        });
        let pending: unknown;
        await act(async () => { pending = get("contactSheet").saveEdits(); await backend.started.promise; });
        expect(backend.requests).toHaveLength(1);
        await act(async () => get("contactSheet").updateDraft(41, { name: "Newer first input" }));
        await act(async () => { backend.held.resolve(json(firstSaved)); await secondStarted.promise; });
        expect(get("contactSheet").drafts[41]).toMatchObject({ name: "Newer first input", expectedVersion: 2 });
        await act(async () => get("contactSheet").updateDraft(41, { name: "Newest first input" }));
        await act(async () => { secondResponse.resolve(json(secondSaved)); await pending; });
        expect(get("contactSheet").editSheetOpen).toBe(true);
        expect(get("contactSheet").drafts[41]).toMatchObject({ name: "Newest first input", expectedVersion: 2 });
        expect(get("contactSheet").drafts[42]).toMatchObject({ name: "Second submitted", expectedVersion: 6 });
        await act(async () => get("contactSheet").saveEdits());
        expectWrites(backend, ["/api/persons/41", "/api/persons/42", "/api/persons/41"]);
        const retryBody = backend.requests[2]?.init.body;
        if (typeof retryBody !== "string") throw new Error("Expected the retried contact payload");
        expect(JSON.parse(retryBody)).toMatchObject({ expectedVersion: 2, name: "Newest first input" });
        expect(get("contactSheet").editSheetOpen).toBe(false);
    });
});

describe("in-flight deal drafts", () => {
    it("retains input made before and after the first batch response and retries only that draft", async () => {
        const first = { ...deal, version: 1 };
        const second = { ...deal, id: 42, version: 5, name: "Other deal" };
        const firstSaved = { ...first, version: 2, name: "First submitted" };
        const secondSaved = { ...second, version: 6, name: "Second submitted" };
        const backend = stubBackend("/api/deals/41", (path, method) => {
            if (path === "/api/deals/page") return json({ items: [first, second], total: 2 });
            if (path === "/api/deals/41" && method === "PUT") return json({ ...firstSaved, version: 3, name: "Newest first input" });
            if (path === "/api/deals/42" && method === "PUT") return json(secondSaved);
            return undefined;
        });
        const secondStarted = deferred<void>();
        const secondResponse = deferred<Response>();
        const transport = vi.mocked(fetch);
        const original = transport.getMockImplementation();
        if (!original) throw new Error("Expected the stub transport");
        transport.mockImplementation((input, init = {}) => {
            const response = original(input, init);
            if (String(input).endsWith("/api/deals/42") && init.method === "PUT") {
                secondStarted.resolve();
                return secondResponse.promise;
            }
            return response;
        });
        browser.selectedIds = new Set([41, 42]);
        browser.showSelectionActions = true;
        await render(<DealsBrowser
            deals={[first, second]} total={2} metrics={{ byCurrency: [], totalCount: 2 }}
            serverFacets={{ status: [], stages: [], pipelines: [], companies: [], currencies: [], risk: [], owners: [] }}
            savedViews={[]} defaultView={null} timezone="UTC" currentUserId={7}
        />);
        const edit = [...document.querySelectorAll("button")].find((button) => button.textContent?.trim() === "quickEdit");
        if (!edit) throw new Error("Expected the multi-record Quick edit action");
        await act(async () => edit.click());
        await act(async () => {
            get("dealSheet").updateDraft(41, { name: "First submitted" });
            get("dealSheet").updateDraft(42, { name: " Second submitted " });
        });
        let pending: unknown;
        await act(async () => {
            pending = get("dealSheet").saveEdits();
            await backend.started.promise;
        });
        expect(get("dealSheet").isSaving).toBe(true);
        await act(async () => get("dealSheet").updateDraft(41, { name: "Newer first input" }));
        await act(async () => {
            backend.held.resolve(json(firstSaved));
            await secondStarted.promise;
        });
        expect(get("dealSheet").drafts[41]).toMatchObject({ name: "Newer first input", expectedVersion: 2 });
        expect(get("dealSheet").selectedDeals.find((row) => row.id === 41)).toMatchObject(firstSaved);
        await act(async () => get("dealSheet").updateDraft(41, { name: "Newest first input" }));
        await act(async () => { secondResponse.resolve(json(secondSaved)); await pending; });
        expect(get("dealSheet").open).toBe(true);
        expect(get("dealSheet").isSaving).toBe(false);
        expect(get("dealSheet").drafts[41]).toMatchObject({ name: "Newest first input", expectedVersion: 2 });
        expect(get("dealSheet").drafts[42]).toMatchObject({ name: "Second submitted", expectedVersion: 6 });
        await act(async () => get("dealSheet").saveEdits());
        expectWrites(backend, ["/api/deals/41", "/api/deals/42", "/api/deals/41"]);
        const retryBody = backend.requests[2]?.init.body;
        if (typeof retryBody !== "string") throw new Error("Expected the retried draft payload");
        expect(JSON.parse(retryBody)).toMatchObject({ expectedVersion: 2, name: "Newest first input" });
        expect(get("dealSheet").open).toBe(false);
    });

    it("retains newer detail input and uses the acknowledged version on the next save", async () => {
        const firstSaved = { ...deal, version: 2, name: "Submitted detail" };
        const backend = stubBackend("/api/deals/41", (path, method) => path === "/api/deals/41" && method === "PUT"
            ? json({ ...firstSaved, version: 3, name: "Newer detail input" }) : undefined);
        const onOpenChange = vi.fn();
        await render(<EditDealSheet deal={{ ...deal, version: 1 }} timezone="Asia/Tokyo" open onOpenChange={onOpenChange} pipelines={[pipeline]} stagesByPipeline={{ 41: [stage] }} />);
        await act(async () => get("dealSheet").updateDraft(41, { name: "Submitted detail" }));
        let pending: unknown;
        await act(async () => { pending = get("dealSheet").saveEdits(); await backend.started.promise; });
        await act(async () => get("dealSheet").updateDraft(41, { name: "Newer detail input" }));
        await act(async () => { backend.held.resolve(json(firstSaved)); await pending; });
        expect(get("dealSheet").drafts[41]).toMatchObject({ name: "Newer detail input", expectedVersion: 2 });
        expect(get("dealSheet").timezone).toBe("Asia/Tokyo");
        expect(get("dealSheet").selectedDeals[0]).toMatchObject(firstSaved);
        expect(get("dealSheet").isSaving).toBe(false);
        expect(onOpenChange).not.toHaveBeenCalled();
        expect(browser.refresh).not.toHaveBeenCalled();
        await act(async () => get("dealSheet").saveEdits());
        expectWrites(backend, ["/api/deals/41", "/api/deals/41"]);
        const retryBody = backend.requests[1]?.init.body;
        if (typeof retryBody !== "string") throw new Error("Expected the retried detail payload");
        expect(JSON.parse(retryBody)).toMatchObject({ expectedVersion: 2, name: "Newer detail input" });
        expect(onOpenChange).toHaveBeenCalledExactlyOnceWith(false);
        expect(browser.refresh).toHaveBeenCalledOnce();
    });
});

describe("detail edit snapshots", () => {
    it("uses the reconciled deal after renaming a line while route refresh is held", async () => {
        window.history.replaceState(null, "", "/records/pipelines");
        const initial = { ...deal, version: 1 };
        let current = initial;
        let lineRequests = 0;
        const routeRefresh = deferred<Deal>();
        const line: DealLineItem = {
            id: 11, dealId: deal.id, name: "Consulting", unitPrice: 100, quantity: 1,
            billingFrequency: "one_time", position: 0, currency: "USD",
            lineSubtotal: 100, lineTax: 0, lineTotal: 100, createdAt: "", updatedAt: "",
        };
        const lines = { items: [line], totals: { currency: "USD", subtotal: 100, tax: 0, oneTimeTotal: 100, recurringTotal: 0, grandTotal: 100 } };
        const backend = stubBackend(undefined, (path, method) => {
            if (path === "/api/deals/41/line-items/11" && method === "PUT") {
                lineRequests += 1;
                if (lineRequests === 2) current = { ...current, version: 2 };
                return json({ ...lines, items: [{ ...line, name: "Renamed consulting" }] });
            }
            if (path === "/api/deals/41" && method === "GET") return json(current);
            if (path === "/api/deals/41" && method === "PUT") {
                current = { ...current, version: 3, name: "Edited after line rename" };
                return json(current);
            }
            return undefined;
        });
        function Route() {
            const [pageDeal, setPageDeal] = useState<Deal>(initial);
            useEffect(() => {
                let mounted = true;
                void routeRefresh.promise.then((snapshot) => { if (mounted) setPageDeal(snapshot); });
                return () => { mounted = false; };
            }, []);
            return <>
                <output>{pageDeal.version}</output>
                <DealActionsMenu deal={pageDeal} timezone="Asia/Tokyo" pipelines={[pipeline]} stagesByPipeline={{ 41: [stage] }} currentUserId={7} />
                <DealLineItems dealId={deal.id} dealWorkspaceId={ORIGIN} dealCurrency="USD" initial={lines} />
            </>;
        }
        await render(<Route />);
        const name = document.querySelector<HTMLInputElement>('input[aria-label="columnItem"]');
        if (!name) throw new Error("Expected the line name input");
        await act(async () => {
            name.value = "Renamed consulting";
            name.dispatchEvent(new FocusEvent("focusout", { bubbles: true }));
            await vi.waitFor(() => expect(browser.refresh).toHaveBeenCalledOnce());
        });
        expect(document.querySelector("output")?.textContent).toBe("1");
        document.cookie = "connex_workspace=5; path=/";
        const edit = [...document.querySelectorAll("button")].find((button) => button.textContent?.trim() === "edit");
        if (!edit) throw new Error("Expected the detail Edit action");
        await act(async () => edit.click());
        expect(get("dealSheet").open).toBe(true);
        const reads = vi.mocked(fetch).mock.calls.filter(([input, init]) => String(input).endsWith("/api/deals/41") && (init?.method ?? "GET") === "GET");
        expect(reads).toHaveLength(1);
        expect(new Headers(reads[0]?.[1]?.headers).get("X-Workspace-Id")).toBe(String(ORIGIN));
        expect(get("dealSheet").drafts[41].expectedVersion).toBe(2);
        expect(get("dealSheet").timezone).toBe("Asia/Tokyo");
        await act(async () => get("dealSheet").updateDraft(41, { name: "Edited after line rename" }));
        await act(async () => get("dealSheet").saveEdits());
        expectWrites(backend, ["/api/deals/41/line-items/11", "/api/deals/41/line-items/11", "/api/deals/41"]);
        for (const [index, request] of backend.requests.slice(0, 2).entries()) {
            const payload: unknown = JSON.parse(String(request.init.body));
            expect(payload).toMatchObject(index === 0 ? { previewOnly: true } : { name: "Renamed consulting" });
            expect(payload).not.toHaveProperty("expectedVersion");
        }
        const body = backend.requests[2]?.init.body;
        if (typeof body !== "string") throw new Error("Expected a deal update payload");
        expect(JSON.parse(body)).toMatchObject({ expectedVersion: 2, name: "Edited after line rename" });
        expect(captured.dealConflict).toBeNull();
        expect(get("dealSheet").open).toBe(false);
        expect(document.querySelector("output")?.textContent).toBe("1");
        await act(async () => routeRefresh.resolve(current));
        expect(document.querySelector("output")?.textContent).toBe("3");
    });

    it("does not open detail Edit after its pinned snapshot read crosses a workspace switch", async () => {
        stubBackend();
        const snapshot = deferred<Response>();
        const started = deferred<void>();
        const transport = vi.mocked(fetch);
        const original = transport.getMockImplementation();
        if (!original) throw new Error("Expected the stub transport");
        let readOrigin: string | null = null;
        transport.mockImplementation((input, init = {}) => {
            if (String(input).endsWith("/api/deals/41") && (init.method ?? "GET") === "GET") {
                readOrigin = new Headers(init.headers).get("X-Workspace-Id");
                started.resolve();
                return snapshot.promise;
            }
            return original(input, init);
        });
        await render(<DealActionsMenu deal={{ ...deal, version: 1 }} timezone="Asia/Tokyo" pipelines={[pipeline]} stagesByPipeline={{ 41: [stage] }} currentUserId={7} />);
        const edit = [...document.querySelectorAll("button")].find((button) => button.textContent?.trim() === "edit");
        if (!edit) throw new Error("Expected the detail Edit action");
        await act(async () => { edit.click(); await started.promise; });
        expect(edit.disabled).toBe(true);
        expect(readOrigin).toBe(String(ORIGIN));
        switchWorkspace();
        await act(async () => { snapshot.resolve(json({ ...deal, version: 2 })); await snapshot.promise; });
        expect(get("dealSheet").open).toBe(false);
        expect(edit.disabled).toBe(false);
    });
});

describe("record composers preserve one origin", () => {
    it.each(["task", "activity"] as const)("%s creation and deal-person linking", async (kind) => {
        const path = kind === "task" ? "/api/tasks" : "/api/activities";
        const backend = stubBackend(path);
        const props = { anchor: { kind: "person" as const, person: contact, companyId: null }, currentUserId: 7, open: true };
        await render(kind === "task" ? <RecordTaskComposer {...props} /> : <RecordActivityComposer {...props} />);
        await holdAndSwitch(backend, () => {
            if (kind === "task") return get("task").createRequest?.({ description: "Origin task", assignedToId: 7, personId: 41, dealId: 41 });
            return get("activity").createRequest?.({ type: "Call", subject: "Origin activity", createdById: 7, personId: 41, dealId: 41 });
        }, path);
        expectWrites(backend, [path, "/api/deals/41/people/41"]);
    });
});

describe("contact creation and pictures preserve one origin", () => {
    const surfaces = ["browser", "company grid", "company menu", "quick create"] as const;
    it.each(surfaces.flatMap((surface) => ["manual", "import"].map((mode) => ({ surface, mode }))))("$surface $mode while creation is pending", async ({ surface, mode }) => {
        const first = mode === "import" ? "/api/business-cards/import" : "/api/persons";
        const paths = [first, "/api/persons/41/profile-picture"];
        const heldPath = first;
        const backend = stubBackend(heldPath);
        await render(surface === "browser" ? <ContactsBrowser savedViews={[]} defaultView={null} />
            : surface === "company grid" ? <ContactsGrid contacts={[]} company={company} allTags={[]} />
                : surface === "company menu" ? <CompanyActionsMenu company={company} />
                    : <ContactCreateContainer open onOpenChange={browser.noop} />);
        await act(async () => {
            get("contact").setNewContactPayload(contact);
            get("contact").setImageFile(image);
        });
        const card: BusinessCardImportDraft | undefined = mode === "import" ? {
            requestId: "3246a805-449d-4d5e-bc76-c5272aad7f8e", image, contact,
            recoveryContext: { workspaceId: String(ORIGIN), scope: createHash("sha256").update(String(ORIGIN) + "\u0000" + 7).digest("hex") },
            personAction: { type: "create" }, companyAction: { type: "none" },
        } : undefined;
        await holdAndSwitch(backend, () => get("contact").createNewContact(card), heldPath);
        expectWrites(backend, paths);
    });
    it.each(surfaces)("%s accepts the preflight origin after the selection changed", async (surface) => {
        const backend = stubBackend();
        await render(surface === "browser" ? <ContactsBrowser savedViews={[]} defaultView={null} />
            : surface === "company grid" ? <ContactsGrid contacts={[]} company={company} allTags={[]} />
                : surface === "company menu" ? <CompanyActionsMenu company={company} />
                    : <ContactCreateContainer open onOpenChange={browser.noop} />);
        await act(async () => { get("contact").setNewContactPayload(contact); get("contact").setImageFile(image); });
        const controller = new AbortController();
        const origin = workspacePinnedRequest(ORIGIN, { signal: controller.signal });
        switchWorkspace();
        await act(async () => { await get("contact").createNewContact(undefined, null, origin); });
        expectWrites(backend, ["/api/persons", "/api/persons/41/profile-picture"]);
        expect(backend.requests.every(({ init }) => init.signal === controller.signal)).toBe(true);
    });
});

describe("record edit chains preserve one origin", () => {
    it.each(["card", "contact", "company", "deal"].flatMap((surface) => (surface === "card" || surface === "deal" ? ["first"] : ["first", "custom fields"]).map((hold) => ({ surface, hold }))))("$surface while $hold is pending", async ({ surface, hold }) => {
        const entityPath = surface === "company" ? "/api/companies/41" : surface === "deal" ? "/api/deals/41" : "/api/persons/41";
        const fieldPath = entityPath + "/custom-fields";
        const picturePath = entityPath + (surface === "company" ? "/logo" : "/profile-picture");
        const heldPath = hold === "first" ? entityPath : fieldPath;
        const backend = stubBackend(heldPath, (path, method) => surface === "card" && path === entityPath && method === "GET" ? json(contact) : undefined);
        await render(surface === "card" ? <ContactCard id={41} workspaceId={ORIGIN} name={contact.name} />
            : surface === "contact" ? <EditContactSheet contact={contact} open onOpenChange={browser.noop} />
                : surface === "company" ? <EditCompanySheet company={company} open onOpenChange={browser.noop} />
                    : <EditDealSheet deal={deal} timezone="UTC" open onOpenChange={browser.noop} pipelines={[pipeline]} stagesByPipeline={{ 41: [stage] }} />);
        if (surface === "card") {
            const edit = [...document.querySelectorAll("button")].find((button) => button.textContent === "quickEdit");
            if (!edit) throw new Error("Expected the card Quick edit action");
            await act(async () => edit.click());
        }
        await act(async () => {
            if (surface === "company") { get("companySheet").updateDraft(41, { name: "Updated company" }); get("companySheet").updateLogoFile?.(41, image); }
            else if (surface === "deal") get("dealSheet").updateDraft(41, { name: "Updated deal" });
            else { get("contactSheet").updateDraft(41, { name: "Updated contact" }); get("contactSheet").updateImageFile?.(41, image); }
        });
        if (surface !== "card") {
            const input = document.getElementById("cfe-1");
            if (!(input instanceof HTMLInputElement)) throw new Error("Custom field did not load");
            await act(async () => {
                Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value")?.set?.call(input, "West");
                input.dispatchEvent(new Event("input", { bubbles: true }));
            });
        }
        await holdAndSwitch(backend, () => surface === "company" ? get("companySheet").saveEdits() : surface === "deal" ? get("dealSheet").saveEdits() : get("contactSheet").saveEdits(), heldPath);
        expectWrites(backend, [entityPath, ...surface === "card" ? [] : [fieldPath], ...surface === "deal" ? [] : [picturePath]]);
    });
});

describe("company quick creation preserves one origin", () => {
    it.each(["/api/companies", "/api/companies/41/logo", "/api/persons"])("while %s is pending", async (heldPath) => {
        const backend = stubBackend(heldPath);
        await render(<CompanyCreateContainer open onOpenChange={browser.noop} />);
        await act(async () => { get("company").setPayload({ name: company.name }); get("company").setLogoFile(image); });
        const pending = [1, 2].map((id) => ({ tempId: String(id), name: "Staged " + id, email: "", phone: "", title: "", imageFile: image, duplicateReviewSignature: null, duplicateReviewToken: null }));
        await holdAndSwitch(backend, () => get("company").createNewCompany(null, pending), heldPath);
        expectWrites(backend, ["/api/companies", "/api/companies/41/logo", "/api/persons", "/api/persons/41/profile-picture", "/api/persons", "/api/persons/41/profile-picture"]);
    });
});

describe("pipeline chains preserve one origin", () => {
    it.each(["sheet", "browser"] as const)("%s restores rejected stage ordering without losing field edits", async (surface) => {
        const won = { ...stage, id: stage.id + 1, name: "Won", position: 1, success: true };
        const backend = stubBackend(undefined, (path, method) => path === "/api/pipelines/41/stages"
            ? method === "PUT" ? json({ error: "Unavailable" }, 503) : json([stage, won])
            : undefined);
        if (surface === "browser") browser.search = new URLSearchParams("edit=41");
        await render(surface === "sheet"
            ? <EditPipelineSheet pipeline={pipeline} stages={[stage, won]} open onOpenChange={browser.noop} />
            : <PipelinesBrowser pipelines={[pipeline]} />);
        await act(async () => get("pipelineSheet").updateDraft(41, {
            stages: [won, { ...stage, name: "Renamed" }],
            stageOrderBeforeMove: [`saved-${stage.id}`, `saved-${won.id}`],
        }));
        await act(async () => { await get("pipelineSheet").saveEdits(); });
        expect(get("pipelineSheet").drafts[41].stages.map((item) => item.name)).toEqual(["Renamed", "Won"]);
        expect(get("pipelineSheet").orderRestorationVersion).toBe(1);
        expectWrites(backend, ["/api/pipelines/41/stages"]);
    });
    it.each(["sheet", "browser"] as const)("%s updates name then stages", async (surface) => {
        const backend = stubBackend("/api/pipelines/41");
        await render(surface === "sheet" ? <EditPipelineSheet pipeline={pipeline} stages={[stage]} open onOpenChange={browser.noop} /> : <PipelinesBrowser pipelines={[pipeline]} />);
        await act(async () => { get("pipelineSheet").updateDraft(41, { name: "Updated pipeline", stages: [{ ...stage, name: "Updated stage" }] }); });
        await holdAndSwitch(backend, () => get("pipelineSheet").saveEdits(), "/api/pipelines/41");
        expectWrites(backend, ["/api/pipelines/41", "/api/pipelines/41/stages"]);
    });
    it("creates a pipeline then stages", async () => {
        const backend = stubBackend("/api/pipelines");
        await render(<PipelinesBrowser pipelines={[pipeline]} />);
        await act(async () => get("pipeline").setPayload({ name: "New pipeline", stages: [{ name: "First" }, { name: "Second" }] }));
        await holdAndSwitch(backend, () => get("pipeline").createNewPipeline(), "/api/pipelines");
        expectWrites(backend, ["/api/pipelines", "/api/pipelines/41/stages", "/api/pipelines/41/stages"]);
    });
});

describe("deal creation callbacks accept the preflight origin", () => {
    it.each(["browser", "company menu", "quick create", "calendar"])("%s scopes created choices and ignores old callbacks", async (surface) => {
        stubBackend(undefined, (path, method) => path === "/api/pipelines" && method === "GET" ? json([]) : undefined);
        const element = surface === "browser"
            ? <DealsBrowser deals={[]} total={0} metrics={{ byCurrency: [], totalCount: 0 }} serverFacets={{ status: [], stages: [], pipelines: [], companies: [], currencies: [], risk: [], owners: [] }} savedViews={[]} defaultView={null} timezone="UTC" currentUserId={7} />
            : surface === "company menu" ? <CompanyActionsMenu company={company} />
            : surface === "quick create" ? <DealCreateContainer open onOpenChange={browser.noop} />
            : <CalendarNewDealContainer open onOpenChange={browser.noop} defaultExpectedCloseDate="2026-10-20" />;
        await render(element);
        const publish = get("deal").onPipelineCreated;
        if (!publish) throw new Error("Missing choice publication callback");
        await act(async () => publish(pipeline, [stage]));
        expect(get("deal").pipelines).toEqual([pipeline]);
        browser.activeWorkspaceId = 5;
        switchWorkspace();
        await render(cloneElement(element));
        await act(async () => publish(pipeline, [stage]));
        expect(get("deal").pipelines).toEqual([]);
        expect(get("deal").stagesByPipeline).toEqual({});
        browser.activeWorkspaceId = ORIGIN;
        bindRenderedWorkspace(shell, ORIGIN);
        await render(cloneElement(element));
        await act(async () => publish(pipeline, [stage]));
        expect(get("deal").pipelines).toEqual([]);
    });
    it.each(["browser", "company menu", "quick create", "calendar"])("%s", async (surface) => {
        const backend = stubBackend();
        await render(surface === "browser" ? <DealsBrowser deals={[]} total={0} metrics={{ byCurrency: [], totalCount: 0 }} serverFacets={{ status: [], stages: [], pipelines: [], companies: [], currencies: [], risk: [], owners: [] }} savedViews={[]} defaultView={null} timezone="UTC" currentUserId={7} /> : surface === "company menu" ? <CompanyActionsMenu company={company} /> : surface === "quick create" ? <DealCreateContainer open onOpenChange={browser.noop} /> : <CalendarNewDealContainer open onOpenChange={browser.noop} defaultExpectedCloseDate="2026-10-20" />);
        const origin = workspacePinnedRequest(ORIGIN);
        switchWorkspace();
        await act(async () => { await get("deal").createNewDeal("review-token", origin); });
        expectWrites(backend, ["/api/deals"]);
    });
});
