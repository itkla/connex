/** @vitest-environment jsdom */
import { act } from "react";
import { createRoot, type Root } from "react-dom/client";
import { NextIntlClientProvider } from "next-intl";
import { afterEach, describe, expect, it, vi } from "vitest";

import { WorkflowRecipeDetail } from "@/app/components/settings/workflows/recipes/WorkflowRecipeGallery";
import { getUsers, getWorkflowRecipe, installWorkflowRecipe, previewWorkflowRecipe } from "@/app/lib/api";
import type { WorkflowRecipe, WorkflowRecipePreview } from "@/app/lib/types";
import enOperations from "@/messages/en/workflow-operations.json";
import jaOperations from "@/messages/ja/workflow-operations.json";
import enWorkspace from "@/messages/en/workspace.json";
import jaWorkspace from "@/messages/ja/workspace.json";

globalThis.IS_REACT_ACT_ENVIRONMENT = true;

vi.mock("next/navigation", () => ({ useRouter: () => ({ push: vi.fn() }) }));
vi.mock("@/app/hooks/useWorkspace", () => ({ useWorkspace: () => ({ activeWorkspaceId: 1, switching: false }) }));
vi.mock("@/app/hooks/useNavTrail", () => ({ CrumbLabel: () => null }));
vi.mock("@/app/lib/api", () => ({
    ApiError: class extends Error {},
    getUsers: vi.fn(),
    getWorkflowRecipe: vi.fn(),
    getWorkflowRecipes: vi.fn(),
    installWorkflowRecipe: vi.fn(),
    previewWorkflowRecipe: vi.fn(),
}));

let root: Root | null = null;
afterEach(async () => {
    await act(async () => root?.unmount());
    root = null;
    document.body.replaceChildren();
    vi.clearAllMocks();
});

const recipe: WorkflowRecipe = {
    recipeKey: "cooling-company-review",
    recipeVersion: 1,
    schemaVersion: 1,
    titleKey: "cooling-company-review",
    descriptionKey: "cooling-company-review",
    sourceEvent: "schedule.daily",
    actorModel: "user",
    dataRead: ["company"],
    dataWritten: ["task"],
    requiredParameters: [],
    requiredPermissions: ["TASK_CREATE"],
    lockedFields: [],
    editableFields: [],
    sideEffects: ["create_task"],
    actions: [{ actionType: "create_task", retrySafety: "transactional" }],
    disableBehavior: "disable",
    removeBehavior: "archive",
};

const preview: WorkflowRecipePreview = {
    recipe,
    previewHash: "preview",
    definition: { schemaVersion: 1, entryNodeId: "trigger", nodes: [], edges: [] },
    canvas: { positions: {}, viewport: { x: 0, y: 0, zoom: 1 } },
    unresolvedParameters: [],
    validation: {
        draftRevision: 0,
        valid: false,
        canPublish: false,
        systemAuthoringAllowed: true,
        requiredPermissions: ["TASK_CREATE"],
        missingPermissions: [],
        errors: [{ code: "action_record_type_unsupported", nodeId: "task", edgeId: null, fieldPath: null, params: {} }],
    },
    plannedActions: [{ nodeId: "task", actionType: "create_task", retrySafety: "transactional" }],
    exampleResult: null,
    writesCreated: false,
};

describe("unavailable recipe installation", () => {
    for (const [locale, operations, workspace] of [
        ["en", enOperations, enWorkspace],
        ["ja", jaOperations, jaWorkspace],
    ] as const) {
        it(`${locale}: explains the unsupported company task and refuses installation`, async () => {
            vi.mocked(getUsers).mockResolvedValue([]);
            vi.mocked(getWorkflowRecipe).mockResolvedValue(recipe);
            vi.mocked(previewWorkflowRecipe).mockResolvedValue(preview);
            const container = document.createElement("div");
            document.body.appendChild(container);
            root = createRoot(container);
            await act(async () => {
                root?.render(
                    <NextIntlClientProvider locale={locale} messages={{ ...operations, ...workspace }} timeZone="UTC">
                        <WorkflowRecipeDetail recipeKey={recipe.recipeKey} />
                    </NextIntlClientProvider>,
                );
            });
            const previewButton = [...document.querySelectorAll("button")]
                .find((button) => button.textContent === operations.WorkflowOperations.recipes.preview);
            expect(previewButton).toBeDefined();
            await act(async () => previewButton?.click());
            expect(document.body.textContent).toContain(operations.WorkflowOperations.recipes.coolingUnavailable);
            const installButton = [...document.querySelectorAll("button")]
                .find((button) => button.textContent === operations.WorkflowOperations.recipes.install);
            expect(installButton?.disabled).toBe(true);
            await act(async () => installButton?.click());
            expect(installWorkflowRecipe).not.toHaveBeenCalled();
        });
    }
});
