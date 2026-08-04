"use client";

import { useEffect, useState } from "react";
import type { Editor } from "@tiptap/core";
import { TextSelection } from "@tiptap/pm/state";
import { BubbleMenu, type BubbleMenuProps } from "@tiptap/react/menus";

import { InlineFormattingControls, type ToolbarLabels } from "./EditorToolbar";
import { canShowSelectionToolbar } from "./selectionToolbarVisibility";

const MENU_OPTIONS: NonNullable<BubbleMenuProps["options"]> = {
    placement: "top",
    offset: 8,
    flip: true,
    shift: true,
};

/** Contextual inline-format controls for non-empty text selections. */
export function SelectionToolbar({ editor, labels }: { editor: Editor; labels: ToolbarLabels }) {
    const [, setRevision] = useState(0);

    useEffect(() => {
        const update = () => setRevision((revision) => revision + 1);
        editor.on("selectionUpdate", update);
        editor.on("transaction", update);
        return () => {
            editor.off("selectionUpdate", update);
            editor.off("transaction", update);
        };
    }, [editor]);

    return (
        <BubbleMenu
            editor={editor}
            pluginKey="note-selection-toolbar"
            updateDelay={0}
            options={MENU_OPTIONS}
            shouldShow={({ editor: activeEditor, state, from, to }) => canShowSelectionToolbar({
                editable: activeEditor.isEditable,
                textSelection: state.selection instanceof TextSelection,
                from,
                to,
                codeBlock: activeEditor.isActive("codeBlock"),
            })}
            role="toolbar"
            aria-label={labels.selectionToolbar}
            aria-orientation="horizontal"
            className="flex items-center gap-0.5 rounded-lg bg-popover p-1 shadow-md ring-1 ring-foreground/10"
        >
            <InlineFormattingControls editor={editor} labels={labels} showClear />
        </BubbleMenu>
    );
}
