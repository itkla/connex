/** @vitest-environment jsdom */

import { useState } from "react";
import { Editor } from "@tiptap/core";
import { act, cleanup, fireEvent, render, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import RichNoteEditor from "@/app/components/activity/notes/RichNoteEditor";

vi.mock("next-intl", () => ({ useTranslations: () => (key: string) => key }));
vi.mock("@/app/components/activity/notes/editor/EditorToolbar", () => ({ EditorToolbar: () => null }));
vi.mock("@/app/components/activity/notes/editor/mentionData", () => ({
    queryMentions: async () => [{ type: "person", id: 42, label: "Kenji Tanaka", sublabel: "Contact", avatarUrl: null }],
}));

beforeEach(() => {
    Range.prototype.getBoundingClientRect = () => new DOMRect();
    Range.prototype.getClientRects = () => Object.assign([], { item: () => null });
});
afterEach(cleanup);

function Harness({ initial = "" }: { initial?: string }) {
    const [value, setValue] = useState(initial);
    return <>
        <RichNoteEditor value={value} onChange={setValue} compact />
        <output>{value}</output>
        <button onClick={() => setValue("External [Kenji Tanaka](person:42)")}>Load another note</button>
    </>;
}

async function mountedEditor(container: HTMLElement): Promise<Editor> {
    let editor: Editor | null = null;
    await waitFor(() => {
        const element = container.querySelector(".tiptap");
        if (!element || !("editor" in element) || !(element.editor instanceof Editor)) throw new Error("Editor not ready");
        editor = element.editor;
    });
    if (!editor) throw new Error("Editor not ready");
    return editor;
}

describe("controlled rich note mentions", () => {
    it.each([false, true])("keeps the suggestion token and surrounding text (type after: %s)", async (typeAfter) => {
        const { container } = render(<Harness />);
        const editor = await mountedEditor(container);
        await act(async () => { editor.commands.insertContent("Owner: @Kenji"); });
        const option = await waitFor(() => {
            const element = document.querySelector('[role="option"]');
            if (!element) throw new Error("Missing mention suggestion");
            return element;
        });
        await act(async () => { fireEvent.mouseDown(option); });
        if (typeAfter) await act(async () => { editor.commands.insertContent("and more text"); });

        expect(container.querySelector("output")?.textContent).toBe(
            `Owner: [Kenji Tanaka](person:42)${typeAfter ? " and more text" : " "}`,
        );
        expect(editor.getText()).toBe(`Owner: Kenji Tanaka ${typeAfter ? "and more text" : ""}`);
        expect(editor.getJSON().content?.[0]?.content).toContainEqual(expect.objectContaining({
            type: "mention", attrs: { refType: "person", refId: 42, label: "Kenji Tanaka" },
        }));
    });

    it.each([
        "Plain text  with spacing\n\nA second paragraph.\n",
        "  Existing [Kenji Tanaka](person:42)\n\n- Existing item\n",
        "Existing **bold** and _italic_ text.\n",
    ])("does not rewrite an existing note on load: %s", async (initial) => {
        const change = vi.fn();
        const { container } = render(<RichNoteEditor value={initial} onChange={change} compact />);
        await mountedEditor(container);
        expect(change).not.toHaveBeenCalled();
    });

    it("still accepts a value replaced outside the editor", async () => {
        const { container, getByRole } = render(<Harness initial="Original" />);
        const editor = await mountedEditor(container);
        await act(async () => { fireEvent.click(getByRole("button", { name: "Load another note" })); });
        expect(editor.getText()).toBe("External Kenji Tanaka");
        expect(container.querySelector("output")?.textContent).toBe("External [Kenji Tanaka](person:42)");
    });
});
