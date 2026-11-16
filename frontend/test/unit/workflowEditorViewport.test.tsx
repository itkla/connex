/** @vitest-environment jsdom */
import { act, useEffect } from "react";
import { createRoot, type Root } from "react-dom/client";
import { NextIntlClientProvider } from "next-intl";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { createEmptyWorkflowGraph } from "@/app/components/settings/workflows/workflowGraph";
import { useWorkflowEditor } from "@/app/components/settings/workflows/useWorkflowEditor";
import { getWorkflowById, getWorkflowVersions, publishWorkflow, saveWorkflowDraft, validateWorkflow } from "@/app/lib/api";
import type { WorkflowDto, WorkflowValidation } from "@/app/lib/types";
import en from "@/messages/en/workspace.json";

globalThis.IS_REACT_ACT_ENVIRONMENT = true;

vi.mock("next/navigation", () => ({ useRouter: () => ({ replace: vi.fn() }) }));
vi.mock("@/app/lib/toast", () => ({ toastError: vi.fn(), toastSuccess: vi.fn() }));
vi.mock("@/app/lib/api", () => ({
    ApiError: class extends Error {},
    createWorkflow: vi.fn(),
    disableWorkflow: vi.fn(),
    enableWorkflow: vi.fn(),
    getWorkflowById: vi.fn(),
    getWorkflowVersions: vi.fn(),
    publishWorkflow: vi.fn(),
    saveWorkflowDraft: vi.fn(),
    search: vi.fn(),
    simulateWorkflow: vi.fn(),
    validateWorkflow: vi.fn(),
}));

const graph = createEmptyWorkflowGraph();
const workflow: WorkflowDto = {
    id: 41,
    name: "Won deal follow-up",
    enabled: false,
    runtimeOwner: "legacy",
    executionAvailable: true,
    draftRevision: 1,
    recordType: "deal",
    executionMode: "user",
    definition: graph.definition,
    canvas: { ...graph.canvas, viewport: { x: 0, y: 0, zoom: 1 } },
    createdAt: "2026-11-04T12:00:00Z",
    updatedAt: "2026-11-04T12:00:00Z",
};
const validation: WorkflowValidation = {
    draftRevision: 1,
    valid: true,
    canPublish: true,
    systemAuthoringAllowed: true,
    requiredPermissions: [],
    missingPermissions: [],
    errors: [],
};

let root: Root | null = null;
let currentEditor: ReturnType<typeof useWorkflowEditor> | null = null;

function editor() {
    if (!currentEditor) throw new Error("Editor has not mounted");
    return currentEditor;
}

function EditorHarness() {
    const value = useWorkflowEditor({ workflowId: 41, activeWorkspaceId: 1, switching: false, canRunAsSystem: true });
    useEffect(() => {
        currentEditor = value;
    });
    return null;
}

async function mount() {
    const container = document.createElement("div");
    document.body.appendChild(container);
    root = createRoot(container);
    await act(async () => {
        root?.render(
            <NextIntlClientProvider locale="en" messages={en} timeZone="UTC">
                <EditorHarness />
            </NextIntlClientProvider>,
        );
    });
    expect(editor().loading).toBe(false);
}

beforeEach(() => {
    vi.mocked(getWorkflowById).mockResolvedValue(workflow);
    vi.mocked(getWorkflowVersions).mockResolvedValue([]);
    vi.mocked(validateWorkflow).mockResolvedValue(validation);
    vi.mocked(publishWorkflow).mockResolvedValue(workflow);
});

afterEach(async () => {
    await act(async () => root?.unmount());
    root = null;
    currentEditor = null;
    document.body.replaceChildren();
    vi.clearAllMocks();
});

describe("workflow viewport validation", () => {
    it("validates after the default viewport fits and keeps manual pans explicitly saveable", async () => {
        await mount();
        await act(async () => editor().moveViewport({ x: 120, y: 20, zoom: 0.75 }));
        expect(editor().dirty).toBe(true);
        expect(editor().validationDirty).toBe(false);
        await act(async () => editor().validate());
        expect(validateWorkflow).toHaveBeenCalledTimes(1);
        expect(editor().validation).toEqual(validation);

        const viewport = { x: 240, y: 60, zoom: 0.5 };
        await act(async () => editor().moveViewport(viewport));
        expect(editor().validation).toEqual(validation);
        await act(async () => editor().publish());
        expect(publishWorkflow).toHaveBeenCalledWith(41, 1, { headers: { "X-Workspace-Id": "1" } });

        vi.mocked(saveWorkflowDraft).mockResolvedValue({
            ...workflow,
            canvas: { ...workflow.canvas, viewport },
            draftRevision: 2,
        });
        await act(async () => editor().save());
        expect(saveWorkflowDraft).toHaveBeenCalledWith(41, expect.objectContaining({
            canvas: { ...workflow.canvas, viewport },
            expectedRevision: 1,
        }), { headers: { "X-Workspace-Id": "1" } });
        expect(editor().dirty).toBe(false);
    });

    it("accepts in-flight validation evidence when only the viewport changed", async () => {
        let finishValidation: (value: WorkflowValidation) => void = () => { throw new Error("Validation has not started"); };
        vi.mocked(validateWorkflow).mockReturnValue(new Promise((resolve) => { finishValidation = resolve; }));
        await mount();
        let pendingValidation: Promise<void> | undefined;
        await act(async () => { pendingValidation = editor().validate(); });
        await act(async () => editor().moveViewport({ x: 120, y: 20, zoom: 0.75 }));
        await act(async () => {
            finishValidation(validation);
            await pendingValidation;
        });
        expect(editor().validation).toEqual(validation);
    });

    it("still blocks validation and clears its evidence for unsaved workflow edits", async () => {
        await mount();
        await act(async () => editor().validate());
        vi.mocked(validateWorkflow).mockClear();
        await act(async () => editor().changeName("Changed workflow"));
        await act(async () => editor().moveViewport({ x: 120, y: 20, zoom: 0.75 }));
        expect(editor().history.present.name).toBe("Changed workflow");
        expect(editor().validationDirty).toBe(true);
        expect(editor().validation).toBeNull();
        await act(async () => editor().validate());
        expect(validateWorkflow).not.toHaveBeenCalled();
    });
});
