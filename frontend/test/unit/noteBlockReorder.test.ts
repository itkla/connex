import { Editor } from "@tiptap/core";
import { TextSelection } from "@tiptap/pm/state";
import StarterKit from "@tiptap/starter-kit";
import { describe, expect, it } from "vitest";

import {
    canMoveTopLevelBlock,
    moveTopLevelBlock,
} from "@/app/components/activity/notes/editor/BlockReorder";

function createEditor(): Editor {
    return new Editor({
        extensions: [StarterKit],
        content: {
            type: "doc",
            content: [
                { type: "paragraph", content: [{ type: "text", text: "First" }] },
                { type: "heading", attrs: { level: 2 }, content: [{ type: "text", text: "Second" }] },
                { type: "paragraph", content: [{ type: "text", text: "Third" }] },
            ],
        },
    });
}

function blockText(editor: Editor): string[] {
    const values: string[] = [];
    editor.state.doc.forEach((node) => values.push(node.textContent));
    return values;
}

describe("note block reordering", () => {
    it("moves a selected block up and preserves the text selection", () => {
        const editor = createEditor();
        editor.view.dispatch(editor.state.tr.setSelection(TextSelection.create(editor.state.doc, 12, 9)));
        const selectedText = editor.state.doc.textBetween(
            editor.state.selection.from,
            editor.state.selection.to,
        );

        expect(canMoveTopLevelBlock(editor, "up")).toBe(true);
        expect(moveTopLevelBlock(editor, "up")).toBe(true);
        expect(blockText(editor)).toEqual(["Second", "First", "Third"]);
        expect(editor.state.doc.textBetween(
            editor.state.selection.from,
            editor.state.selection.to,
        )).toBe(selectedText);
        expect(editor.state.selection.anchor).toBeGreaterThan(editor.state.selection.head);

        editor.destroy();
    });

    it("moves a selected block down", () => {
        const editor = createEditor();
        editor.commands.setTextSelection(2);

        expect(canMoveTopLevelBlock(editor, "down")).toBe(true);
        expect(moveTopLevelBlock(editor, "down")).toBe(true);
        expect(blockText(editor)).toEqual(["Second", "First", "Third"]);

        editor.destroy();
    });

    it("preserves a top-level node selection", () => {
        const editor = createEditor();
        editor.commands.setNodeSelection(7);

        expect(moveTopLevelBlock(editor, "down")).toBe(true);
        expect(blockText(editor)).toEqual(["First", "Third", "Second"]);
        expect(editor.state.selection.from).toBe(14);
        expect(editor.state.doc.nodeAt(editor.state.selection.from)?.textContent).toBe("Second");

        editor.destroy();
    });

    it("refuses document boundaries and cross-block selections", () => {
        const editor = createEditor();
        editor.commands.setTextSelection(2);
        expect(canMoveTopLevelBlock(editor, "up")).toBe(false);
        expect(moveTopLevelBlock(editor, "up")).toBe(false);

        editor.commands.setTextSelection({ from: 2, to: 10 });
        expect(canMoveTopLevelBlock(editor, "down")).toBe(false);
        expect(moveTopLevelBlock(editor, "down")).toBe(false);
        expect(blockText(editor)).toEqual(["First", "Second", "Third"]);

        editor.destroy();
    });
});
