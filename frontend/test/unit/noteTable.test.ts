import { Editor, type JSONContent } from "@tiptap/core";
import StarterKit from "@tiptap/starter-kit";
import { TableMap } from "@tiptap/pm/tables";
import MarkdownIt from "markdown-it";
import { describe, expect, it } from "vitest";
import { Markdown } from "tiptap-markdown";

import {
    canDeleteMarkdownTableRow,
    createMarkdownTableExtensions,
} from "@/app/components/activity/notes/editor/MarkdownTable";
import { NoteText, NoteUnderline } from "@/app/components/activity/notes/editor/NoteUnderline";

function createEditor(content?: JSONContent): Editor {
    return new Editor({
        extensions: [
            StarterKit.configure({ text: false, underline: false }),
            ...createMarkdownTableExtensions(),
            NoteText,
            NoteUnderline,
            Markdown.configure({ html: false }),
        ],
        content: content ?? { type: "doc", content: [{ type: "paragraph" }] },
    });
}

function readMarkdown(editor: Editor): string {
    const storage = editor.storage as { markdown?: { getMarkdown?: () => string } };
    return storage.markdown?.getMarkdown?.() ?? "";
}

const markdownParser = new MarkdownIt({ html: false });

function parseTableCells(value: string) {
    return markdownParser.parse(value, {}).filter((token) => token.type === "inline");
}

describe("Markdown note tables", () => {
    it("serializes a GFM table without letting cell pipes create columns", () => {
        const editor = createEditor({
            type: "doc",
            content: [{
                type: "table",
                content: [
                    {
                        type: "tableRow",
                        content: [
                            { type: "tableHeader", content: [{ type: "paragraph", content: [{ type: "text", text: "A | B" }] }] },
                            { type: "tableHeader", content: [{ type: "paragraph", content: [{ type: "text", text: "Status" }] }] },
                        ],
                    },
                    {
                        type: "tableRow",
                        content: [
                            { type: "tableCell", content: [{ type: "paragraph", content: [{ type: "text", text: "Acme" }] }] },
                            { type: "tableCell", content: [{ type: "paragraph", content: [{ type: "text", text: "Active" }] }] },
                        ],
                    },
                ],
            }],
        });

        const serialized = readMarkdown(editor);
        expect(serialized).toBe(
            "| A \\| B | Status |\n| --- | --- |\n| Acme | Active |\n",
        );
        const cells = parseTableCells(serialized);
        expect(cells).toHaveLength(4);
        expect(cells[0]?.content).toBe("A | B");
        editor.destroy();
    });

    it("escapes pipes emitted by inline-code serialization", () => {
        const editor = createEditor({
            type: "doc",
            content: [{
                type: "table",
                content: [
                    {
                        type: "tableRow",
                        content: [
                            { type: "tableHeader", content: [{ type: "paragraph", content: [{ type: "text", marks: [{ type: "code" }], text: "A | B" }] }] },
                            { type: "tableHeader", content: [{ type: "paragraph", content: [{ type: "text", text: "Status" }] }] },
                        ],
                    },
                    {
                        type: "tableRow",
                        content: [
                            { type: "tableCell", content: [{ type: "paragraph", content: [{ type: "text", text: "Acme" }] }] },
                            { type: "tableCell", content: [{ type: "paragraph", content: [{ type: "text", text: "Active" }] }] },
                        ],
                    },
                ],
            }],
        });

        const serialized = readMarkdown(editor);
        expect(serialized).toContain("| `A \\| B` | Status |");
        const cells = parseTableCells(serialized);
        expect(cells).toHaveLength(4);
        expect(cells[0]?.children).toEqual([
            expect.objectContaining({ content: "A | B", type: "code_inline" }),
        ]);
        editor.destroy();
    });

    it("inserts a header-first table and preserves it when adding a row", () => {
        const editor = createEditor();

        expect(editor.commands.insertTable({ rows: 3, cols: 2, withHeaderRow: true })).toBe(true);
        expect(canDeleteMarkdownTableRow(editor)).toBe(false);
        expect(editor.commands.goToNextCell()).toBe(true);
        expect(canDeleteMarkdownTableRow(editor)).toBe(false);
        expect(editor.commands.goToNextCell()).toBe(true);
        expect(canDeleteMarkdownTableRow(editor)).toBe(true);
        expect(editor.commands.addRowAfter()).toBe(true);

        const table = editor.state.doc.firstChild;
        expect(table?.type.name).toBe("table");
        expect(table?.childCount).toBe(4);

        let headerCellsOnly = true;
        table?.child(0).forEach((cell) => {
            headerCellsOnly &&= cell.type.name === "tableHeader";
        });
        expect(headerCellsOnly).toBe(true);

        let bodyCellsOnly = true;
        table?.forEach((row, _offset, rowIndex) => {
            if (rowIndex === 0) return;
            row.forEach((cell) => {
                bodyCellsOnly &&= cell.type.name === "tableCell";
            });
        });
        expect(bodyCellsOnly).toBe(true);
        editor.destroy();
    });

    it("keeps each Markdown cell to one paragraph", () => {
        const editor = createEditor();

        expect(editor.schema.nodes.tableCell.spec.content).toBe("paragraph");
        expect(editor.schema.nodes.tableHeader.spec.content).toBe("paragraph");
        editor.destroy();
    });

    it("protects the header when a cell selection spans header and body rows", () => {
        const editor = createEditor();
        editor.commands.insertTable({ rows: 3, cols: 2, withHeaderRow: true });
        const table = editor.state.doc.firstChild;
        expect(table?.type.name).toBe("table");
        if (!table) throw new Error("Expected an inserted table");

        const map = TableMap.get(table);
        expect(editor.commands.setCellSelection({
            anchorCell: map.map[0] + 1,
            headCell: map.map[map.width] + 1,
        })).toBe(true);
        expect(canDeleteMarkdownTableRow(editor)).toBe(false);
        editor.destroy();
    });
});
