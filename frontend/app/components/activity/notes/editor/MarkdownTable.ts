import { Extension, type Editor } from "@tiptap/core";
import { Table, TableCell, TableHeader, TableRow } from "@tiptap/extension-table";
import type { Node as PMNode } from "@tiptap/pm/model";
import { CellSelection } from "@tiptap/pm/tables";

type MarkdownTableSerializeState = {
    out: string;
    inTable: boolean;
    write: (content: string) => void;
    ensureNewLine: () => void;
    closeBlock: (node: PMNode) => void;
    renderInline: (node: PMNode) => void;
};

function renderMarkdownTableCell(state: MarkdownTableSerializeState, content: PMNode): void {
    const start = state.out.length;
    state.renderInline(content);
    state.out = state.out.slice(0, start) + state.out.slice(start).replaceAll("|", "\\|");
}

function serializeMarkdownTable(state: MarkdownTableSerializeState, node: PMNode): void {
    state.inTable = true;
    node.forEach((row, _rowOffset, rowIndex) => {
        state.write("| ");
        row.forEach((cell, _cellOffset, cellIndex) => {
            if (cellIndex > 0) state.write(" | ");
            const content = cell.firstChild;
            if (content?.textContent.trim()) renderMarkdownTableCell(state, content);
        });
        state.write(" |");
        state.ensureNewLine();
        if (rowIndex === 0) {
            state.write(`| ${Array.from({ length: row.childCount }, () => "---").join(" | ")} |`);
            state.ensureNewLine();
        }
    });
    state.closeBlock(node);
    state.inTable = false;
}

const MarkdownTable = Table.extend({
    addStorage() {
        return {
            markdown: {
                serialize: serializeMarkdownTable,
            },
        };
    },
});

const MarkdownTableCell = TableCell.extend({
    content: "paragraph",
});

const MarkdownTableHeader = TableHeader.extend({
    content: "paragraph",
});

const MarkdownTableKeyboardGuard = Extension.create({
    name: "markdownTableKeyboardGuard",
    priority: 1_000,

    addKeyboardShortcuts() {
        const preserveSingleParagraph = () => this.editor.isActive("table");
        return {
            "Mod-Enter": preserveSingleParagraph,
            "Shift-Enter": preserveSingleParagraph,
        };
    },
});

/** Default GFM-compatible table inserted by editor controls. */
export const DEFAULT_MARKDOWN_TABLE_OPTIONS = {
    rows: 3,
    cols: 3,
    withHeaderRow: true,
} as const;

/** Creates the Markdown-compatible table extension set used by note editors. */
export function createMarkdownTableExtensions() {
    return [
        MarkdownTable.configure({ renderWrapper: true }),
        TableRow,
        MarkdownTableHeader,
        MarkdownTableCell,
        MarkdownTableKeyboardGuard,
    ];
}

/** Prevents deleting the required GFM header row. */
export function canDeleteMarkdownTableRow(editor: Editor): boolean {
    const { selection } = editor.state;
    if (selection instanceof CellSelection) {
        let includesHeader = false;
        selection.forEachCell((cell) => {
            includesHeader ||= cell.type.name === "tableHeader";
        });
        return !includesHeader && editor.can().deleteRow();
    }
    return !editor.isActive("tableHeader") && editor.can().deleteRow();
}
