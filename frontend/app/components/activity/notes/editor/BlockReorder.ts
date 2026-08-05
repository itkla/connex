import type { Editor } from "@tiptap/core";
import type { Node as ProseMirrorNode } from "@tiptap/pm/model";
import { NodeSelection, TextSelection } from "@tiptap/pm/state";

export type BlockMoveDirection = "up" | "down";

type SelectedBlock = {
    node: ProseMirrorNode;
    position: number;
    selection: NodeSelection | TextSelection;
};

function selectedTopLevelBlock(editor: Editor): SelectedBlock | null {
    const { doc, selection } = editor.state;
    if (selection instanceof NodeSelection) {
        if (selection.$from.depth !== 0) return null;
        return { node: selection.node, position: selection.from, selection };
    }
    if (!(selection instanceof TextSelection) || selection.$from.depth < 1 || selection.$to.depth < 1) {
        return null;
    }
    const position = selection.$from.before(1);
    if (selection.$to.before(1) !== position) return null;
    const node = doc.nodeAt(position);
    return node ? { node, position, selection } : null;
}

function adjacentBlock(editor: Editor, selected: SelectedBlock, direction: BlockMoveDirection) {
    const { doc } = editor.state;
    if (direction === "up") {
        const node = doc.resolve(selected.position).nodeBefore;
        return node ? { node, position: selected.position - node.nodeSize } : null;
    }
    const position = selected.position + selected.node.nodeSize;
    const node = doc.resolve(position).nodeAfter;
    return node ? { node, position } : null;
}

/** Reports whether the selected top-level block can move in the requested direction. */
export function canMoveTopLevelBlock(editor: Editor, direction: BlockMoveDirection): boolean {
    const selected = selectedTopLevelBlock(editor);
    return selected !== null && adjacentBlock(editor, selected, direction) !== null;
}

/** Moves the selected top-level block while preserving its text or node selection. */
export function moveTopLevelBlock(editor: Editor, direction: BlockMoveDirection): boolean {
    const selected = selectedTopLevelBlock(editor);
    if (!selected) return false;
    const adjacent = adjacentBlock(editor, selected, direction);
    if (!adjacent) return false;

    const { state, view } = editor;
    const targetPosition = direction === "up"
        ? adjacent.position
        : selected.position + adjacent.node.nodeSize;
    const transaction = state.tr
        .delete(selected.position, selected.position + selected.node.nodeSize)
        .insert(targetPosition, selected.node);

    if (selected.selection instanceof NodeSelection) {
        transaction.setSelection(NodeSelection.create(transaction.doc, targetPosition));
    } else {
        const relativeAnchor = selected.selection.anchor - selected.position;
        const relativeHead = selected.selection.head - selected.position;
        transaction.setSelection(TextSelection.create(
            transaction.doc,
            targetPosition + relativeAnchor,
            targetPosition + relativeHead,
        ));
    }

    view.dispatch(transaction.scrollIntoView());
    return true;
}
