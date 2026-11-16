/** @vitest-environment jsdom */
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { NextIntlClientProvider } from "next-intl";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import WorkflowActionsBridge from "@/app/components/actions/WorkflowActionsBridge";
import SearchResultsView from "@/app/components/SearchResultsView";
import {
    ActionProvider, useActionRecord, useActionSelection, useActions, useAvailableActions, useRegisterActions,
} from "@/app/hooks/useActions";
import type { ActiveRecordRef, ActiveSelection, AppAction, OverlayRequest } from "@/app/lib/actions/types";
import type { SearchResults, User, WorkflowListItem, Workspace } from "@/app/lib/types";
import messages from "@/messages/en/actions.json";
import common from "@/messages/en/common.json";
import operations from "@/messages/en/workflow-operations.json";
import { deferred } from "./helpers/workspaceChainBackend";

const mocks = vi.hoisted(() => ({
    getWorkflows: vi.fn(), permitted: true, switching: false, workspaceId: 7,
    router: { push: vi.fn(), refresh: vi.fn() }, error: vi.fn(),
}));
const workspace: Workspace = {
    id: 7, name: "Workspace", slug: "workspace", timezone: "UTC", identityVersion: 1,
    role: "member", orgId: 3, orgName: "Organization", orgIdentityVersion: 1, orgRole: null,
};
const user: User = {
    id: 11, username: "reviewer", displayName: "Reviewer", email: "reviewer@example.test",
    createdAt: "2026-11-01T00:00:00Z", updatedAt: "2026-11-01T00:00:00Z", timezone: "UTC", locale: "en",
};
vi.mock("next/navigation", () => ({ usePathname: () => "/dashboard", useRouter: () => mocks.router }));
vi.mock("@/app/hooks/useWorkspace", () => ({
    useWorkspace: () => ({ activeWorkspace: { ...workspace, id: mocks.workspaceId }, switching: mocks.switching }),
}));
vi.mock("@/app/hooks/usePermissions", () => ({ usePermission: (key: string) => key === "RULE_MANAGE" && mocks.permitted }));
vi.mock("@/app/hooks/useApiErrorToast", () => ({ useApiErrorToast: () => mocks.error }));
vi.mock("@/app/lib/api", () => ({ getWorkflows: mocks.getWorkflows }));
vi.mock("@/app/components/actions/ActionOverlayHost", () => ({
    default: ({ overlay }: { overlay: OverlayRequest | null }) => <output data-testid="overlay">{JSON.stringify(overlay)}</output>,
}));

const record: ActiveRecordRef = { type: "person", id: 42, label: "Contact" };
const selection: ActiveSelection = {
    type: "deal", ids: new Set([1, 2]), sourceSurface: "record_list",
    scope: { kind: "explicit_selection", recordIds: [1, 2] },
};
const noActions: readonly AppAction[] = [];

function Probe({ activeRecord = null, activeSelection = null, extra = noActions }: {
    activeRecord?: ActiveRecordRef | null;
    activeSelection?: ActiveSelection | null;
    extra?: readonly AppAction[];
}) {
    useActionRecord(activeRecord);
    useActionSelection(activeSelection);
    useRegisterActions(extra);
    const available = useAvailableActions();
    const { run } = useActions();
    return <>
        <ul>{available.map((action) => <li key={action.id} data-group={action.group} data-testid={action.id}>
            <button onClick={() => void run(action.id, { source: "palette" })}>{action.labelKey ?? action.id}</button>
        </li>)}</ul>
        <input aria-label="Editable input" /><textarea aria-label="Editable textarea" /><select aria-label="Editable select" />
        <div contentEditable suppressContentEditableWarning><span data-testid="rich-text">Rich text</span></div>
        <div role="textbox" data-testid="custom-textbox" />
    </>;
}

function tree(props: Parameters<typeof Probe>[0] = {}) {
    return <NextIntlClientProvider locale="en" messages={messages}>
        <ActionProvider user={user}><WorkflowActionsBridge /><Probe {...props} /></ActionProvider>
    </NextIntlClientProvider>;
}

function workflow(overrides: Partial<WorkflowListItem> = {}): WorkflowListItem {
    return {
        id: 1, name: "Follow up", description: null, enabled: true, runtimeOwner: "canonical",
        executionAvailable: true, executionUnavailableReason: null, archivedAt: null,
        intakePausedAt: null, intakePausedById: null, draftRevision: 1, recordType: "person",
        executionMode: "user", runAsUserId: user.id, createdById: user.id, updatedById: user.id,
        createdAt: "2026-11-01T00:00:00Z", updatedAt: "2026-11-01T00:00:00Z",
        activeVersion: { id: 1, number: 1, publishedAt: "2026-11-01T00:00:00Z" },
        nodeCount: 3, actionCount: 1, latestRun: null, ...overrides,
    };
}

beforeEach(() => {
    vi.clearAllMocks();
    mocks.permitted = true;
    mocks.switching = false;
    mocks.workspaceId = workspace.id;
    mocks.getWorkflows.mockResolvedValue([workflow()]);
    vi.spyOn(navigator, "platform", "get").mockReturnValue("Win32");
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); });

describe("workflow palette context", () => {
    it("keeps a search-scoped workflow action out of the record group without a record or selection", () => {
        const results: SearchResults = {
            users: [], companies: [], people: [], deals: [], pipelines: [], tags: [], activities: [], notes: [],
            tasks: [], attachments: [], products: [], campaigns: [], reports: [], documentTemplates: [], documents: [], workflows: [],
        };
        const { container } = render(<NextIntlClientProvider locale="en" messages={{ ...messages, ...common, ...operations }}>
            <ActionProvider user={user}><SearchResultsView query="Contact" results={results} /><Probe /></ActionProvider>
        </NextIntlClientProvider>);
        expect(screen.getByTestId("record.run-search-workflow").getAttribute("data-group")).toBe("workspace");
        expect(container.querySelector('[data-group="record"]')).toBeNull();
    });

    it("offers the context-free launcher in Workspace and no record group on Dashboard", async () => {
        const { container } = render(tree());
        const action = await screen.findByTestId("workspace.run-workflow");
        expect(action.getAttribute("data-group")).toBe("workspace");
        expect(container.querySelector('[data-group="record"]')).toBeNull();
        expect(mocks.getWorkflows).toHaveBeenCalledWith(false, expect.objectContaining({ headers: { "X-Workspace-Id": "7" } }));
        fireEvent.click(screen.getByRole("button", { name: "workspace.runWorkflow" }));
        expect(screen.getByTestId("overlay").textContent).toBe(JSON.stringify({
            kind: "workflow-manual-run", sourceSurface: "command_palette", recordType: null, scope: null,
        }));
    });

    it.each([
        { activeRecord: record, activeSelection: null },
        { activeRecord: null, activeSelection: selection },
    ])("offers only the contextual workflow action for $activeRecord $activeSelection", async (props) => {
        render(tree(props));
        await waitFor(() => expect(mocks.getWorkflows).toHaveBeenCalledOnce());
        const action = screen.getByTestId("record.run-workflow");
        expect(action.getAttribute("data-group")).toBe("record");
        expect(screen.queryByTestId("workspace.run-workflow")).toBeNull();
        fireEvent.click(screen.getByRole("button", { name: "record.runWorkflow" }));
        const resolvedScope = props.activeRecord ? { kind: "single_record", recordId: 42 } : selection.scope;
        expect(screen.getByTestId("overlay").textContent).toBe(JSON.stringify({
            kind: "workflow-manual-run", sourceSurface: "command_palette",
            recordType: props.activeRecord ? "person" : "deal", scope: { kind: "command_palette", resolvedScope },
        }));
    });

    it("does not fetch or offer the workspace launcher without manual-run permission", () => {
        mocks.permitted = false;
        render(tree());
        expect(mocks.getWorkflows).not.toHaveBeenCalled();
        expect(screen.queryByTestId("workspace.run-workflow")).toBeNull();
    });

    it.each([
        { items: [] }, { items: [workflow({ activeVersion: null })] }, { items: [workflow({ runtimeOwner: "legacy" })] },
        { items: [workflow({ archivedAt: "2026-11-01T00:00:00Z" })] }, { items: [workflow({ recordType: "task" })] },
        { items: [workflow({ enabled: false })] }, { items: [workflow({ executionAvailable: false })] },
        { items: [workflow({ intakePausedAt: "2026-11-01T00:00:00Z" })] },
    ])("hides the workspace launcher when the catalogue has no runnable workflow: %j", async ({ items }) => {
        mocks.getWorkflows.mockResolvedValue(items);
        render(tree());
        await act(async () => undefined);
        expect(screen.queryByTestId("workspace.run-workflow")).toBeNull();
    });

    it("fails closed on a catalogue lookup failure", async () => {
        mocks.getWorkflows.mockRejectedValue(new Error("Unavailable"));
        render(tree());
        await act(async () => undefined);
        expect(screen.queryByTestId("workspace.run-workflow")).toBeNull();
    });

    it("ignores an old workspace response and hides the action during switching", async () => {
        const held = deferred<WorkflowListItem[]>();
        mocks.getWorkflows.mockReturnValueOnce(held.promise).mockResolvedValue([]);
        const view = render(tree());
        mocks.workspaceId = 8;
        view.rerender(tree());
        await act(async () => held.resolve([workflow()]));
        expect(screen.queryByTestId("workspace.run-workflow")).toBeNull();
        mocks.switching = true;
        view.rerender(tree());
        expect(screen.queryByTestId("workspace.run-workflow")).toBeNull();
    });
});

describe("registered shortcut dispatcher", () => {
    it.each([
        ["t", "create-task"], ["n", "create-note"], ["a", "create-activity"],
    ])("dispatches Ctrl+Alt+%s through the real seed action", async (key, kind) => {
        render(tree({ activeRecord: record }));
        fireEvent.keyDown(document.body, { key, code: `Key${key.toUpperCase()}`, ctrlKey: true, altKey: true });
        await waitFor(() => expect(screen.getByTestId("overlay").textContent).toContain(`"kind":"${kind}"`));
        expect(screen.getByTestId("overlay").textContent).toContain('"personId":42');
    });

    it("dispatches Cmd+Option+A even when macOS reports an Option-modified key", async () => {
        vi.spyOn(navigator, "platform", "get").mockReturnValue("MacIntel");
        render(tree());
        fireEvent.keyDown(document.body, { key: "å", code: "KeyA", metaKey: true, altKey: true });
        await waitFor(() => expect(screen.getByTestId("overlay").textContent).toContain('"kind":"create-activity"'));
    });

    it.each(["Editable input", "Editable textarea", "Editable select", "rich-text", "custom-textbox"])(
        "skips editable targets: %s", async (target) => {
            render(tree());
            const editable = screen.queryByLabelText(target) ?? screen.getByTestId(target);
            fireEvent.keyDown(editable, { key: "a", ctrlKey: true, altKey: true });
            await act(async () => undefined);
            expect(screen.getByTestId("overlay").textContent).toBe("null");
        },
    );

    it.each([{ isComposing: true }, { keyCode: 229 }, { repeat: true }, { shiftKey: true }])(
        "skips composition, repeated keys and mismatched modifiers: %j", async (flags) => {
            render(tree());
            fireEvent.keyDown(document.body, { key: "a", ctrlKey: true, altKey: true, ...flags });
            await act(async () => undefined);
            expect(screen.getByTestId("overlay").textContent).toBe("null");
        },
    );

    it("skips the full composition interval and resumes afterwards", async () => {
        render(tree());
        fireEvent.compositionStart(document.body);
        fireEvent.keyDown(document.body, { key: "a", ctrlKey: true, altKey: true });
        expect(screen.getByTestId("overlay").textContent).toBe("null");
        fireEvent.compositionEnd(document.body);
        fireEvent.keyDown(document.body, { key: "a", ctrlKey: true, altKey: true });
        await waitFor(() => expect(screen.getByTestId("overlay").textContent).toContain('"kind":"create-activity"'));
    });

    it("runs an available registered action once, with shortcut source, and removes it on unmount", async () => {
        const execute = vi.fn();
        const extra: readonly AppAction[] = [{
            id: "workspace.test", group: "workspace", label: "Test", shortcut: "Option+Control+P",
            isAvailable: (context) => context.record !== null, execute,
        }];
        const view = render(tree({ extra }));
        expect(fireEvent.keyDown(document.body, { key: "p", ctrlKey: true, altKey: true })).toBe(true);
        expect(execute).not.toHaveBeenCalled();
        view.rerender(tree({ extra, activeRecord: record }));
        expect(fireEvent.keyDown(document.body, { key: "p", ctrlKey: true, altKey: true })).toBe(false);
        await waitFor(() => expect(execute).toHaveBeenCalledOnce());
        expect(execute).toHaveBeenCalledWith(expect.objectContaining({ record }), expect.objectContaining({ source: "shortcut" }));
        view.unmount();
        fireEvent.keyDown(document.body, { key: "p", ctrlKey: true, altKey: true });
        expect(execute).toHaveBeenCalledOnce();
    });
});
