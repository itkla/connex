import { expect, test, type Locator, type Page } from "@playwright/test";

import { csrfBootstrap } from "./support/api";
import { runFixture } from "./support/fixtures";

function isRecord(value: unknown): value is Record<string, unknown> {
    return typeof value === "object" && value !== null;
}

function workflowId(value: unknown): number {
    if (!isRecord(value) || typeof value.id !== "number") {
        throw new Error("Workflow response is missing a numeric id");
    }
    return value.id;
}

function workflowRevision(value: unknown): number {
    if (!isRecord(value) || typeof value.draftRevision !== "number") {
        throw new Error("Workflow response is missing a numeric draft revision");
    }
    return value.draftRevision;
}

function editableWorkflowDocument(name: string) {
    return {
        name,
        description: null,
        recordType: "deal",
        executionMode: "user",
        definition: {
            schemaVersion: 1,
            entryNodeId: "trigger",
            nodes: [
                { id: "trigger", type: "TRIGGER", config: { type: "entity_change", events: ["deal.updated"] } },
                { id: "end", type: "END" },
            ],
            edges: [{ id: "edge", sourceNodeId: "trigger", targetNodeId: "end", outcome: "next" }],
        },
        canvas: {
            positions: { trigger: { x: 80, y: 40 }, end: { x: 80, y: 280 } },
            viewport: { x: 120, y: 20, zoom: 0.75 },
        },
    };
}

async function createEditableWorkflow(
    page: Page,
    workspaceId: number,
    csrf: { headerName: string; token: string },
    name: string,
): Promise<{ created: unknown; document: ReturnType<typeof editableWorkflowDocument> }> {
    const document = editableWorkflowDocument(name);
    const response = await page.request.post("/api/workflows", {
        headers: {
            "X-Workspace-Id": String(workspaceId),
            [csrf.headerName]: csrf.token,
        },
        data: document,
    });
    expect(response.status(), await response.text()).toBe(201);
    return { created: await response.json(), document };
}

function workflowDefinition(value: unknown): Record<string, unknown> {
    if (!isRecord(value) || !isRecord(value.definition)) {
        throw new Error("Workflow response is missing its definition");
    }
    return value.definition;
}

function edgeTarget(value: unknown, sourceNodeId: string, outcome: string): string {
    const edges = workflowDefinition(value).edges;
    if (!Array.isArray(edges)) {
        throw new Error("Workflow definition is missing edges");
    }
    const edge = edges.find((candidate) => isRecord(candidate)
        && candidate.sourceNodeId === sourceNodeId
        && candidate.outcome === outcome);
    if (!isRecord(edge) || typeof edge.targetNodeId !== "string") {
        throw new Error(`Workflow branch ${sourceNodeId}.${outcome} is missing`);
    }
    return edge.targetNodeId;
}

function nodeIdByType(value: unknown, type: string): string {
    const nodes = workflowDefinition(value).nodes;
    if (!Array.isArray(nodes)) {
        throw new Error("Workflow definition is missing nodes");
    }
    const node = nodes.find((candidate) => isRecord(candidate) && candidate.type === type);
    if (!isRecord(node) || typeof node.id !== "string") {
        throw new Error(`Workflow definition is missing a ${type} node`);
    }
    return node.id;
}

function canvasPosition(value: unknown, nodeId: string): { x: number; y: number } {
    if (!isRecord(value) || !isRecord(value.canvas) || !isRecord(value.canvas.positions)) {
        throw new Error("Workflow response is missing canvas positions");
    }
    const position = value.canvas.positions[nodeId];
    if (!isRecord(position) || typeof position.x !== "number" || typeof position.y !== "number") {
        throw new Error(`Workflow canvas is missing a position for ${nodeId}`);
    }
    return { x: position.x, y: position.y };
}

async function dragConnection(page: Page, source: Locator, target: Locator, valid: boolean): Promise<void> {
    const sourceBox = await source.boundingBox();
    const targetBox = await target.boundingBox();
    if (!sourceBox || !targetBox) {
        throw new Error("Workflow connection handles must be visible before dragging");
    }
    await page.mouse.move(sourceBox.x + sourceBox.width / 2, sourceBox.y + sourceBox.height / 2);
    await page.mouse.down();
    await page.mouse.move(targetBox.x + targetBox.width / 2, targetBox.y + targetBox.height / 2, { steps: 8 });
    await expect(target).toHaveClass(/(?:^|\s)connectingto(?:\s|$)/);
    if (valid) {
        await expect(target).toHaveClass(/(?:^|\s)valid(?:\s|$)/);
    } else {
        await expect(target).not.toHaveClass(/(?:^|\s)valid(?:\s|$)/);
    }
    await page.mouse.up();
}

async function expectCanvasViewportLocked(page: Page): Promise<void> {
    const flow = page.locator(".react-flow");
    const pane = flow.locator(".react-flow__pane");
    const viewport = flow.locator(".react-flow__viewport");
    await expect(page.getByRole("button", { name: "Zoom in" })).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Zoom out" })).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Fit view" })).toHaveCount(0);
    const transform = await viewport.evaluate((element) => getComputedStyle(element).transform);
    const point = await pane.evaluate((element) => {
        const rect = element.getBoundingClientRect();
        return {
            x: rect.left + rect.width * 0.85,
            y: rect.top + rect.height * 0.2,
        };
    });
    await page.mouse.move(point.x, point.y);
    await page.mouse.wheel(120, -240);
    await page.mouse.dblclick(point.x, point.y);
    await page.keyboard.down("Space");
    await page.mouse.move(point.x, point.y);
    await page.mouse.down();
    await page.mouse.move(point.x - 80, point.y + 80, { steps: 4 });
    await page.mouse.up();
    await page.keyboard.up("Space");
    await expect.poll(() => viewport.evaluate((element) => getComputedStyle(element).transform)).toBe(transform);
}

async function settleBrowserTasks(page: Page): Promise<void> {
    await page.evaluate(() => new Promise<void>((resolve) => {
        requestAnimationFrame(() => requestAnimationFrame(() => resolve()));
    }));
}

async function workflowPanePoint(pane: Locator): Promise<{ x: number; y: number }> {
    return pane.evaluate((element) => {
        const rect = element.getBoundingClientRect();
        const candidates = [
            [0.85, 0.2],
            [0.75, 0.75],
            [0.5, 0.1],
            [0.15, 0.8],
        ];
        for (const [horizontal, vertical] of candidates) {
            const x = rect.left + rect.width * horizontal;
            const y = rect.top + rect.height * vertical;
            if (globalThis.document.elementFromPoint(x, y) === element) return { x, y };
        }
        throw new Error("Workflow pane has no unobstructed pan target");
    });
}

test.describe("workflow canvas", () => {
    test("locks initial authoring until creation finishes", async ({ page }) => {
        let releaseCreate: () => void = () => undefined;
        const createReleased = new Promise<void>((resolve) => {
            releaseCreate = resolve;
        });
        let markCreatePending: () => void = () => undefined;
        const createPending = new Promise<void>((resolve) => {
            markCreatePending = resolve;
        });
        let releaseNavigation: () => void = () => undefined;
        const navigationReleased = new Promise<void>((resolve) => {
            releaseNavigation = resolve;
        });
        let markNavigationPending: () => void = () => undefined;
        const navigationPending = new Promise<void>((resolve) => {
            markNavigationPending = resolve;
        });

        await page.goto("/workflows/new");
        await page.getByLabel("Workflow name").fill("Create without lost edits");
        await page.route(/\/workflows\/\d+(?:\?.*)?$/, async (route) => {
            const pathname = new URL(route.request().url()).pathname;
            if (!/^\/workflows\/\d+$/.test(pathname)) {
                await route.continue();
                return;
            }
            markNavigationPending();
            await navigationReleased;
            await route.continue();
        });
        await page.route("**/api/workflows", async (route) => {
            if (route.request().method() !== "POST") {
                await route.continue();
                return;
            }
            markCreatePending();
            const response = await route.fetch();
            await createReleased;
            await route.fulfill({ response });
        });

        try {
            await page.getByRole("button", { name: "Save draft" }).click();
            await createPending;
            await expect(page.getByRole("button", { name: "Saving draft…" })).toBeDisabled();
            await expect(page.getByLabel("Workflow name")).toBeDisabled();
            await expect(page.locator(".react-flow__node").first()).not.toHaveClass(/(?:^|\s)draggable(?:\s|$)/);
            await expectCanvasViewportLocked(page);
        } finally {
            releaseCreate();
        }

        try {
            await navigationPending;
            await expect(page.getByRole("button", { name: "Save draft" })).toBeDisabled();
            await expect(page.getByLabel("Workflow name")).toBeDisabled();
            await expectCanvasViewportLocked(page);
        } finally {
            releaseNavigation();
        }

        await expect(page).toHaveURL(/\/workflows\/\d+$/);
        await expect(page.getByLabel("Workflow name")).toHaveValue("Create without lost edits");
    });

    test("ignores a pan that ends after creation locks", async ({ page }) => {
        let releaseCreate: () => void = () => undefined;
        const createReleased = new Promise<void>((resolve) => {
            releaseCreate = resolve;
        });
        let markCreatePending: () => void = () => undefined;
        const createPending = new Promise<void>((resolve) => {
            markCreatePending = resolve;
        });
        let releaseNavigation: () => void = () => undefined;
        const navigationReleased = new Promise<void>((resolve) => {
            releaseNavigation = resolve;
        });
        let markNavigationPending: () => void = () => undefined;
        const navigationPending = new Promise<void>((resolve) => {
            markNavigationPending = resolve;
        });

        await page.goto("/workflows/new");
        await page.getByLabel("Workflow name").fill("Create after an interrupted pan");
        await page.route(/\/workflows\/\d+(?:\?.*)?$/, async (route) => {
            const pathname = new URL(route.request().url()).pathname;
            if (!/^\/workflows\/\d+$/.test(pathname)) {
                await route.continue();
                return;
            }
            markNavigationPending();
            await navigationReleased;
            await route.continue();
        });
        await page.route("**/api/workflows", async (route) => {
            if (route.request().method() !== "POST") {
                await route.continue();
                return;
            }
            markCreatePending();
            const response = await route.fetch();
            await createReleased;
            await route.fulfill({ response });
        });

        const pane = page.locator(".react-flow__pane");
        const viewport = page.locator(".react-flow__viewport");
        const initialTransform = await viewport.evaluate((element) => getComputedStyle(element).transform);
        const point = await workflowPanePoint(pane);
        await page.mouse.move(point.x, point.y);
        await page.mouse.down();
        await page.mouse.move(point.x - 120, point.y + 80, { steps: 4 });
        await expect.poll(() => viewport.evaluate((element) => getComputedStyle(element).transform)).not.toBe(initialTransform);

        try {
            await page.getByRole("button", { name: "Save draft" }).evaluate((button) => {
                if (!(button instanceof HTMLElement)) throw new Error("Save draft must be an HTML button");
                button.click();
            });
            await createPending;
            await expect(page.getByRole("button", { name: "Saving draft…" })).toBeDisabled();
            await page.mouse.up();
        } finally {
            releaseCreate();
        }

        try {
            await navigationPending;
            await expect(page.getByText("Draft saved", { exact: true })).toBeVisible();
            await expect(page.getByText("Unpublished changes", { exact: true })).toHaveCount(0);
        } finally {
            releaseNavigation();
        }

        await expect(page).toHaveURL(/\/workflows\/\d+$/);
    });

    test("keeps Back navigation when creation finishes in the background", async ({ page }) => {
        let releaseCreate: () => void = () => undefined;
        const createReleased = new Promise<void>((resolve) => {
            releaseCreate = resolve;
        });
        let markCreatePending: () => void = () => undefined;
        const createPending = new Promise<void>((resolve) => {
            markCreatePending = resolve;
        });
        let createdNavigationRequested = false;

        await page.route(/\/workflows\/\d+(?:\?.*)?$/, async (route) => {
            const pathname = new URL(route.request().url()).pathname;
            if (/^\/workflows\/\d+$/.test(pathname)) createdNavigationRequested = true;
            await route.continue();
        });
        await page.route("**/api/workflows", async (route) => {
            if (route.request().method() !== "POST") {
                await route.continue();
                return;
            }
            markCreatePending();
            const response = await route.fetch();
            await createReleased;
            await route.fulfill({ response });
        });

        await page.goto("/workflows/new");
        await page.getByLabel("Workflow name").fill("Leave during creation");
        const createResponse = page.waitForResponse((response) => (
            response.request().method() === "POST" && new URL(response.url()).pathname === "/api/workflows"
        ));
        await page.getByRole("button", { name: "Save draft" }).click();
        await createPending;
        await page.getByRole("button", { name: "Back to workflows" }).click();
        await expect(page).toHaveURL(/\/workflows$/);

        releaseCreate();
        const response = await createResponse;
        await response.finished();
        await settleBrowserTasks(page);

        expect(createdNavigationRequested).toBe(false);
        await expect(page).toHaveURL(/\/workflows$/);
    });

    test("keeps edits made while recovering from a stale revision", async ({ page }, testInfo) => {
        const fixture = runFixture(testInfo.project.name);
        const csrf = await csrfBootstrap(page.request);
        const { created, document: initialDocument } = await createEditableWorkflow(
            page,
            fixture.workspaceId,
            csrf,
            `Conflict recovery ${testInfo.retry}`,
        );
        const id = workflowId(created);
        let releaseSave: () => void = () => undefined;
        const saveReleased = new Promise<void>((resolve) => {
            releaseSave = resolve;
        });
        let markSavePending: () => void = () => undefined;
        const savePending = new Promise<void>((resolve) => {
            markSavePending = resolve;
        });

        await page.goto(`/workflows/${id}`);
        await page.route(`**/api/workflows/${id}/draft`, async (route) => {
            markSavePending();
            await saveReleased;
            await route.continue();
        });
        const name = page.getByLabel("Workflow name");
        await name.fill("Submitted name");
        await name.blur();
        await page.getByRole("button", { name: "Save draft" }).click();
        await savePending;
        try {
            await name.fill("Edited during conflict recovery");
            await name.blur();
            const competingResponse = await page.request.put(`/api/workflows/${id}/draft`, {
                headers: {
                    "X-Workspace-Id": String(fixture.workspaceId),
                    [csrf.headerName]: csrf.token,
                },
                data: {
                    ...initialDocument,
                    description: "Changed by another editor",
                    expectedRevision: workflowRevision(created),
                },
            });
            expect(competingResponse.status(), await competingResponse.text()).toBe(200);
        } finally {
            releaseSave();
        }

        await expect(name).toHaveValue("Edited during conflict recovery");
        await expect(page.getByRole("button", { name: "Save draft" })).toBeEnabled();
    });

    test("ignores an older conflict recovery that finishes after a newer one", async ({ page }, testInfo) => {
        const fixture = runFixture(testInfo.project.name);
        const csrf = await csrfBootstrap(page.request);
        const { created, document } = await createEditableWorkflow(
            page,
            fixture.workspaceId,
            csrf,
            `Ordered conflict recovery ${testInfo.retry}`,
        );
        const id = workflowId(created);
        await page.goto(`/workflows/${id}`);
        const name = page.getByLabel("Workflow name");
        const save = page.getByRole("button", { name: "Save draft" });
        await expect(name).toHaveValue(document.name);
        await expect(save).toBeDisabled();

        const olderServerResponse = await page.request.put(`/api/workflows/${id}/draft`, {
            headers: {
                "X-Workspace-Id": String(fixture.workspaceId),
                [csrf.headerName]: csrf.token,
            },
            data: {
                ...document,
                name: "Older server name",
                expectedRevision: workflowRevision(created),
            },
        });
        expect(olderServerResponse.status(), await olderServerResponse.text()).toBe(200);
        const olderServerWorkflow: unknown = await olderServerResponse.json();
        await name.fill("Local conflict name");
        await name.blur();
        await save.click();
        await expect(page.getByText("Older server name", { exact: true })).toBeVisible();
        await page.getByRole("button", { name: "Keep editing locally" }).click();

        let recoveryCount = 0;
        let releaseOlderRecovery: () => void = () => undefined;
        const olderRecoveryReleased = new Promise<void>((resolve) => {
            releaseOlderRecovery = resolve;
        });
        let markOlderRecoveryPending: () => void = () => undefined;
        const olderRecoveryPending = new Promise<void>((resolve) => {
            markOlderRecoveryPending = resolve;
        });
        let markOlderRecoveryCompleted: () => void = () => undefined;
        const olderRecoveryCompleted = new Promise<void>((resolve) => {
            markOlderRecoveryCompleted = resolve;
        });
        await page.route(`**/api/workflows/${id}`, async (route) => {
            if (route.request().method() !== "GET") {
                await route.continue();
                return;
            }
            const recoveryIndex = ++recoveryCount;
            const response = await route.fetch();
            if (recoveryIndex === 1) {
                markOlderRecoveryPending();
                await olderRecoveryReleased;
            }
            await route.fulfill({ response });
            if (recoveryIndex === 1) markOlderRecoveryCompleted();
        });

        await save.click();
        await olderRecoveryPending;
        try {
            const newerServerResponse = await page.request.put(`/api/workflows/${id}/draft`, {
                headers: {
                    "X-Workspace-Id": String(fixture.workspaceId),
                    [csrf.headerName]: csrf.token,
                },
                data: {
                    ...document,
                    name: "Newer server name",
                    expectedRevision: workflowRevision(olderServerWorkflow),
                },
            });
            expect(newerServerResponse.status(), await newerServerResponse.text()).toBe(200);
            await save.click();
            await expect(page.getByText("Newer server name", { exact: true })).toBeVisible();
        } finally {
            releaseOlderRecovery();
        }
        await olderRecoveryCompleted;
        await settleBrowserTasks(page);
        await expect(page.getByText("Newer server name", { exact: true })).toBeVisible();
        await expect(page.getByText("Older server name", { exact: true })).toHaveCount(0);
    });

    test("discards validation that finishes after an edit", async ({ page }, testInfo) => {
        const fixture = runFixture(testInfo.project.name);
        const csrf = await csrfBootstrap(page.request);
        const { created } = await createEditableWorkflow(
            page,
            fixture.workspaceId,
            csrf,
            `Stale validation ${testInfo.retry}`,
        );
        const id = workflowId(created);
        await page.goto(`/workflows/${id}`);

        let releaseValidation: () => void = () => undefined;
        const validationReleased = new Promise<void>((resolve) => {
            releaseValidation = resolve;
        });
        let markValidationPending: () => void = () => undefined;
        const validationPending = new Promise<void>((resolve) => {
            markValidationPending = resolve;
        });
        let markValidationCompleted: () => void = () => undefined;
        const validationCompleted = new Promise<void>((resolve) => {
            markValidationCompleted = resolve;
        });
        let validationValid: boolean | null = null;
        await page.route(`**/api/workflows/${id}/validate`, async (route) => {
            const response = await route.fetch();
            const result: unknown = await response.json();
            if (!isRecord(result) || typeof result.valid !== "boolean") {
                throw new Error("Workflow validation response is missing its valid flag");
            }
            validationValid = result.valid;
            markValidationPending();
            await validationReleased;
            await route.fulfill({ response });
            markValidationCompleted();
        });

        await page.getByRole("button", { name: "Validate" }).click();
        await validationPending;
        try {
            const name = page.getByLabel("Workflow name");
            await name.fill("Edited after validation started");
            await name.blur();
        } finally {
            releaseValidation();
        }
        await validationCompleted;
        await settleBrowserTasks(page);

        if (validationValid === null) throw new Error("Workflow validation did not complete");
        if (validationValid) {
            await expect(page.getByText("Validation passed. This saved revision is ready to publish.", { exact: true })).toHaveCount(0);
        } else {
            await expect(page.locator("#workflow-validation-title")).toHaveCount(0);
        }
        await expect(page.getByRole("button", { name: "Save draft" })).toBeEnabled();
    });

    test("discards simulation that finishes after an edit is saved", async ({ page }, testInfo) => {
        const fixture = runFixture(testInfo.project.name);
        const csrf = await csrfBootstrap(page.request);
        const { created } = await createEditableWorkflow(
            page,
            fixture.workspaceId,
            csrf,
            `Stale simulation ${testInfo.retry}`,
        );
        const id = workflowId(created);
        await page.goto(`/workflows/${id}`);

        let releaseSimulation: () => void = () => undefined;
        const simulationReleased = new Promise<void>((resolve) => {
            releaseSimulation = resolve;
        });
        let markSimulationPending: () => void = () => undefined;
        const simulationPending = new Promise<void>((resolve) => {
            markSimulationPending = resolve;
        });
        let markSimulationCompleted: () => void = () => undefined;
        const simulationCompleted = new Promise<void>((resolve) => {
            markSimulationCompleted = resolve;
        });
        await page.route(`**/api/workflows/${id}/simulate`, async (route) => {
            const response = await route.fetch();
            markSimulationPending();
            await simulationReleased;
            await route.fulfill({ response });
            markSimulationCompleted();
        });

        const preview = page.getByRole("button", { name: "Preview" });
        await expect(preview).toBeEnabled();
        await preview.click();
        const record = page.getByLabel("Record to preview");
        await record.fill(fixture.deals.primary.name);
        await page.getByRole("option", { name: fixture.deals.primary.name, exact: true }).click();
        await page.getByRole("button", { name: "Preview path" }).click();
        await simulationPending;
        try {
            await page.getByRole("dialog", { name: "Preview a workflow path" })
                .getByRole("button", { name: "Close", exact: true })
                .first()
                .click();
            const name = page.getByLabel("Workflow name");
            await name.fill("Edited and saved after simulation started");
            await name.blur();
            await page.getByRole("button", { name: "Save draft" }).click();
            await expect(page.getByText("Unpublished changes", { exact: true })).toHaveCount(0);
        } finally {
            releaseSimulation();
        }
        await simulationCompleted;
        await settleBrowserTasks(page);

        await preview.click();
        const dialog = page.getByRole("dialog", { name: "Preview a workflow path" });
        for (const result of ["Would complete", "Would not enroll", "Would wait here", "Blocked"]) {
            await expect(dialog.getByText(result, { exact: true })).toHaveCount(0);
        }
    });

    test("creates deterministic connections and inserts at the invoked canvas position", async ({ page }, testInfo) => {
        const fixture = runFixture(testInfo.project.name);
        const csrf = await csrfBootstrap(page.request);
        const createdResponse = await page.request.post("/api/workflows", {
            headers: {
                "X-Workspace-Id": String(fixture.workspaceId),
                [csrf.headerName]: csrf.token,
            },
            data: {
                name: `Canvas interaction ${testInfo.retry}`,
                description: null,
                recordType: "deal",
                executionMode: "user",
                definition: {
                    schemaVersion: 1,
                    entryNodeId: "trigger",
                    nodes: [
                        { id: "trigger", type: "TRIGGER", config: { type: "entity_change", events: ["deal.updated"] } },
                        { id: "condition", type: "CONDITION", config: { match: "all", conditions: [] } },
                        { id: "end-yes", type: "END" },
                        { id: "end-no", type: "END" },
                    ],
                    edges: [
                        { id: "edge-trigger", sourceNodeId: "trigger", targetNodeId: "condition", outcome: "next" },
                        { id: "edge-yes", sourceNodeId: "condition", targetNodeId: "end-yes", outcome: "yes" },
                        { id: "edge-no", sourceNodeId: "condition", targetNodeId: "end-no", outcome: "no" },
                    ],
                },
                canvas: {
                    positions: {
                        trigger: { x: 80, y: 20 },
                        condition: { x: 80, y: 190 },
                        "end-yes": { x: 0, y: 400 },
                        "end-no": { x: 360, y: 400 },
                    },
                    viewport: { x: 120, y: 20, zoom: 0.75 },
                },
            },
        });
        expect(createdResponse.status(), await createdResponse.text()).toBe(201);
        const created: unknown = await createdResponse.json();

        await page.goto(`/workflows/${workflowId(created)}`);
        const flow = page.locator(".react-flow");
        await expect(flow).toBeVisible();
        await expect(flow.locator(".react-flow__attribution")).toHaveCount(0);
        await expect(flow.locator(".react-flow__background pattern")).toHaveCount(1);

        const condition = flow.locator('.react-flow__node[data-id="condition"]');
        const yesHandle = condition.locator('.react-flow__handle.source[data-handleid="yes"]');
        const conditionInput = condition.locator('.react-flow__handle.target[data-handleid="in"]');
        const noEndInput = flow.locator('.react-flow__node[data-id="end-no"] .react-flow__handle.target[data-handleid="in"]');
        const handleBox = await yesHandle.boundingBox();
        if (!handleBox) {
            throw new Error("Workflow source handle must be visible");
        }
        expect(handleBox.width).toBeGreaterThanOrEqual(24);
        expect(handleBox.height).toBeGreaterThanOrEqual(24);

        const save = page.getByRole("button", { name: "Save draft" });
        await expect(save).toBeDisabled();
        await dragConnection(page, yesHandle, conditionInput, false);
        await expect(page.getByText("That branch cannot connect to this node.")).toBeVisible();
        await expect(save).toBeDisabled();

        await dragConnection(page, yesHandle, noEndInput, true);
        await expect(save).toBeEnabled();
        await save.click();
        await expect(save).toBeDisabled();

        await condition.click();
        const pane = flow.locator(".react-flow__pane");
        const invoked = await pane.evaluate((element) => {
            const rect = element.getBoundingClientRect();
            const viewport = element.querySelector<HTMLElement>(".react-flow__viewport");
            if (!viewport) {
                throw new Error("Workflow viewport is missing");
            }
            const matrix = new DOMMatrixReadOnly(getComputedStyle(viewport).transform);
            const clientX = rect.left + rect.width * 0.78;
            const clientY = rect.top + rect.height * 0.56;
            return {
                clientX,
                clientY,
                flowX: (clientX - rect.left - matrix.e) / matrix.a,
                flowY: (clientY - rect.top - matrix.f) / matrix.d,
            };
        });
        await page.mouse.click(invoked.clientX, invoked.clientY, { button: "right" });
        const menu = page.getByRole("menu");
        await expect(menu).toBeVisible();
        await expect(flow.locator(".react-flow__node")).toHaveCount(4);
        const yesGroup = menu.getByText("Yes", { exact: true }).locator("..");
        await yesGroup.getByRole("menuitem", { name: "Delay" }).click();
        await expect(flow.locator(".react-flow__node")).toHaveCount(5);

        await save.click();
        await expect(save).toBeDisabled();
        const savedResponse = await page.request.get(`/api/workflows/${workflowId(created)}`, {
            headers: { "X-Workspace-Id": String(fixture.workspaceId) },
        });
        expect(savedResponse.status(), await savedResponse.text()).toBe(200);
        const saved: unknown = await savedResponse.json();
        const delayId = nodeIdByType(saved, "DELAY");
        const delayPosition = canvasPosition(saved, delayId);
        expect(edgeTarget(saved, "condition", "yes")).toBe(delayId);
        expect(edgeTarget(saved, delayId, "next")).toBe("end-no");
        expect(Math.abs(delayPosition.x - invoked.flowX)).toBeLessThan(2);
        expect(Math.abs(delayPosition.y - invoked.flowY)).toBeLessThan(2);

        await page.getByRole("button", { name: "Zoom in" }).click({ button: "right" });
        await expect(menu).toHaveCount(0);
        await condition.click({ button: "right" });
        await expect(menu).toHaveCount(0);

        await testInfo.attach("workflow-canvas", {
            body: await flow.screenshot(),
            contentType: "image/png",
        });
        const zoomOut = page.getByRole("button", { name: "Zoom out" });
        await zoomOut.click();
        await zoomOut.click();
        await zoomOut.click();
        const minimumZoom = await flow.locator(".react-flow__viewport").evaluate((element) => (
            new DOMMatrixReadOnly(getComputedStyle(element).transform).a
        ));
        expect(minimumZoom).toBeCloseTo(0.5, 2);
        const minimumZoomHandleBox = await yesHandle.boundingBox();
        if (!minimumZoomHandleBox) {
            throw new Error("Workflow source handle must remain visible at minimum zoom");
        }
        const minimumHandleSize = 24;
        const subpixelTolerance = 0.001;
        expect(minimumZoomHandleBox.width).toBeGreaterThanOrEqual(minimumHandleSize - subpixelTolerance);
        expect(minimumZoomHandleBox.height).toBeGreaterThanOrEqual(minimumHandleSize - subpixelTolerance);
        await page.getByRole("button", { name: "Outline" }).click();
        await expect(page.getByRole("list", { name: "Workflow steps" })).toBeVisible();
        await expect(page.getByRole("button", { name: "Insert" }).first()).toBeVisible();
    });
});
