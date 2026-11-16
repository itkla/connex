/** @vitest-environment jsdom */
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { useState } from "react";
import { NextIntlClientProvider } from "next-intl";
import { afterEach, beforeAll, describe, expect, it, vi } from "vitest";

import WorkflowInspector from "@/app/components/settings/workflows/WorkflowInspector";
import type { WorkflowEditorDocument } from "@/app/components/settings/workflows/workflowEditorReducer";
import type { RuleBuilderOptions, WorkflowExecutionMode, WorkflowNode } from "@/app/lib/types";
import en from "@/messages/en/workspace.json";
import ja from "@/messages/ja/workspace.json";

beforeAll(() => {
    Object.defineProperty(HTMLElement.prototype, "scrollIntoView", { configurable: true, value: vi.fn() });
});
afterEach(cleanup);

const options: RuleBuilderOptions = { owners: [{ id: 23, name: "Chosen member" }], stages: [], companies: [] };

function inspector(type: string, locale: "en" | "ja", executionMode: WorkflowExecutionMode = "user") {
    const action: WorkflowNode = { id: "action", type: "ACTION", config: { type, title: "Follow up", dueInDays: 0 } };
    const document: WorkflowEditorDocument = {
        name: "Follow up", description: null, recordType: "person", executionMode,
        definition: { schemaVersion: 1, entryNodeId: "action", nodes: [action], edges: [] },
        canvas: { positions: {}, viewport: { x: 0, y: 0, zoom: 1 } },
    };
    const onNodeChange = vi.fn();
    const messages = locale === "en" ? en : ja;
    function Harness() {
        const [current, setCurrent] = useState(document);
        return <NextIntlClientProvider locale={locale} messages={messages}>
            <WorkflowInspector
                document={current} selectedNodeId="action" fields={null} options={options}
                diagnostics={[]} readOnly={false} canRunAsSystem={false} triggeredSendEnabled={false}
                canConfigureTriggeredSend={false} campaignMessageOptions={null} focusFieldPath={null}
                focusRequestId={0} diagnosticMessage={() => null} onNodeChange={(node, mode) => {
                    onNodeChange(node, mode);
                    setCurrent({ ...current, definition: { ...current.definition, nodes: [node] } });
                }}
                onMetadataChange={vi.fn()} onCommitTransient={vi.fn()}
            />
        </NextIntlClientProvider>;
    }
    render(<Harness />);
    return { onNodeChange, copy: messages.WorkflowAuthoring };
}

describe.each(["en", "ja"] as const)("workflow recipients in %s", (locale) => {
    it.each(["create_task", "notify"])("offers a workspace member for %s", async (type) => {
        const { copy, onNodeChange } = inspector(type, locale);
        const picker = screen.getByRole("combobox", { name: type === "create_task" ? copy.taskAssignee : copy.notificationRecipient });
        fireEvent.click(picker);
        fireEvent.click(await screen.findByRole("option", { name: "Chosen member" }));
        expect(onNodeChange).toHaveBeenLastCalledWith(expect.objectContaining({
            config: expect.objectContaining({ targetUserId: 23 }),
        }), "commit");
        fireEvent.click(picker);
        fireEvent.click(await screen.findByRole("option", { name: copy.workflowMember }));
        expect(onNodeChange).toHaveBeenLastCalledWith(expect.objectContaining({
            config: expect.objectContaining({ targetUserId: undefined }),
        }), "commit");
    });

    it("names the creator as the default member for workspace-run workflows", async () => {
        const { copy } = inspector("notify", locale, "system");
        fireEvent.click(screen.getByRole("combobox", { name: copy.notificationRecipient }));
        expect(await screen.findByRole("option", { name: copy.workflowCreator })).toBeTruthy();
    });

    it("keeps zero as due today and exposes the supported range", () => {
        const { copy, onNodeChange } = inspector("create_task", locale);
        const due = screen.getByRole("spinbutton", { name: copy.dueIn });
        expect(due.getAttribute("min")).toBe("0");
        expect(due.getAttribute("max")).toBe("365");
        expect(due.getAttribute("value")).toBe("0");
        fireEvent.change(due, { target: { value: "1" } });
        fireEvent.change(due, { target: { value: "0" } });
        expect(onNodeChange).toHaveBeenLastCalledWith(expect.objectContaining({
            config: expect.objectContaining({ dueInDays: 0 }),
        }), "transient");
    });
});
