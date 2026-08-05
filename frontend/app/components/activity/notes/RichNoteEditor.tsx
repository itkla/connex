"use client";

import { useEffect, useRef, useState } from "react";
import { EditorContent, useEditor } from "@tiptap/react";
import type { Editor } from "@tiptap/core";
import StarterKit from "@tiptap/starter-kit";
import { Placeholder } from "@tiptap/extension-placeholder";
import { TaskList } from "@tiptap/extension-task-list";
import { TaskItem } from "@tiptap/extension-task-item";
import { Markdown } from "tiptap-markdown";
import { useTranslations } from "next-intl";
import { Mention, encodeMentions, restoreMentions } from "./editor/Mention";
import { EditorToolbar } from "./editor/EditorToolbar";
import { SelectionToolbar } from "./editor/SelectionToolbar";
import { SlashCommand } from "./editor/SlashCommand";
import { buildSlashCommands } from "./editor/slashCommands";
import { Callout } from "./editor/Callout";
import { Toggle, ToggleSummary } from "./editor/Toggle";
import { NoteText, NoteUnderline } from "./editor/NoteUnderline";
import { createMarkdownTableExtensions } from "./editor/MarkdownTable";
import { createMarkdownImageExtension } from "./editor/MarkdownImage";
import { DragHandle } from "@tiptap/extension-drag-handle-react";
import { GripVertical } from "lucide-react";
import { NOTE_EDITOR_MARKDOWN_OPTIONS } from "./editor/noteEditorMarkdown";
import { FileReference } from "./editor/FileReference";

function readMarkdown(editor: Editor): string {
    const storage = editor.storage as { markdown?: { getMarkdown?: () => string } };
    return storage.markdown?.getMarkdown?.() ?? "";
}

type Props = {
    value: string;
    onChange: (markdown: string) => void;
    editable?: boolean;
    excludeUserId?: number;
    autofocus?: boolean;
    className?: string;
    /** Miniaturized layout for embedding in a dialog: shorter body, condensed toolbar, no drag handle. */
    compact?: boolean;
};

/**
 * WYSIWYG note editor built on Tiptap. Stores Markdown with inline
 * `[Label](type:id)` reference tokens so the backend ReferenceService keeps
 * resolving @/# mentions unchanged. Supported file embeds are attachment
 * references (`[Label](file:id)`), not binary uploads into the body.
 *
 * Block reorder for assistive tech uses the toolbar Move up/down controls; the
 * drag grip is decorative (`aria-hidden`) and is not the accessible path.
 */
export default function RichNoteEditor({
    value,
    onChange,
    editable = true,
    excludeUserId,
    autofocus = false,
    className,
    compact = false,
}: Props) {
    const t = useTranslations("ActivityNotesEditor");
    const onChangeRef = useRef(onChange);
    const loadingRef = useRef(false);
    const [filePickerOpen, setFilePickerOpen] = useState(false);
    const lastFileOpenRequest = useRef(0);

    useEffect(() => {
        onChangeRef.current = onChange;
    }, [onChange]);

    const editor = useEditor({
        editable,
        immediatelyRender: false,
        autofocus: autofocus ? "end" : false,
        extensions: [
            StarterKit.configure({
                heading: { levels: [1, 2, 3] },
                text: false,
                underline: false,
                link: {
                    openOnClick: false,
                    enableClickSelection: true,
                    defaultProtocol: "https",
                },
            }),
            Placeholder.configure({ placeholder: t("placeholder") }),
            TaskList,
            TaskItem.configure({ nested: true }),
            ...createMarkdownTableExtensions(),
            createMarkdownImageExtension(t("imageLoadError")),
            Markdown.configure(NOTE_EDITOR_MARKDOWN_OPTIONS),
            Mention.configure({ excludeUserId }),
            Callout.configure({ cycleLabel: t("calloutCycleAria") }),
            ToggleSummary,
            Toggle.configure({ expandLabel: t("toggleExpand"), collapseLabel: t("toggleCollapse") }),
            NoteText,
            NoteUnderline,
            FileReference,
            SlashCommand.configure({ commands: buildSlashCommands(t) }),
        ],
        editorProps: {
            attributes: {
                class: `note-prose ${compact ? "min-h-[8.5rem]" : "min-h-[15rem]"} max-w-none focus:outline-none`,
            },
        },
        onUpdate: ({ editor: instance }) => {
            if (loadingRef.current) return;
            onChangeRef.current(readMarkdown(instance));
        },
    });

    useEffect(() => {
        if (!editor) return;
        if (readMarkdown(editor) === (value ?? "")) return;
        loadingRef.current = true;
        const { text, mentions } = encodeMentions(value ?? "");
        editor.commands.setContent(text);
        restoreMentions(editor, mentions);
        loadingRef.current = false;
    }, [editor, value]);

    useEffect(() => {
        editor?.setEditable(editable);
    }, [editor, editable]);

    useEffect(() => {
        if (!editor) return;
        const syncFilePicker = () => {
            const storage = editor.storage as { fileReference?: { openRequest?: number } };
            const request = storage.fileReference?.openRequest ?? 0;
            if (request > lastFileOpenRequest.current) {
                lastFileOpenRequest.current = request;
                setFilePickerOpen(true);
            }
        };
        editor.on("transaction", syncFilePicker);
        return () => {
            editor.off("transaction", syncFilePicker);
        };
    }, [editor]);

    const labels = {
        bold: t("bold"),
        italic: t("italic"),
        underline: t("underline"),
        strike: t("strike"),
        code: t("code"),
        link: t("link"),
        linkTitle: t("linkTitle"),
        linkDescription: t("linkDescription"),
        linkLabel: t("linkLabel"),
        linkPlaceholder: t("linkPlaceholder"),
        linkInvalid: t("linkInvalid"),
        linkApply: t("linkApply"),
        unlink: t("unlink"),
        clearFormatting: t("clearFormatting"),
        selectionToolbar: t("selectionToolbar"),
        undo: t("undo"),
        redo: t("redo"),
        moveBlockUp: t("moveBlockUp"),
        moveBlockDown: t("moveBlockDown"),
        h1: t("heading1"),
        h2: t("heading2"),
        h3: t("heading3"),
        bulletList: t("bulletList"),
        orderedList: t("orderedList"),
        taskList: t("taskList"),
        blockquote: t("blockquote"),
        codeBlock: t("codeBlock"),
        tableInsert: t("tableInsert"),
        tableAddRow: t("tableAddRow"),
        tableAddColumn: t("tableAddColumn"),
        tableDeleteRow: t("tableDeleteRow"),
        tableDeleteColumn: t("tableDeleteColumn"),
        tableDelete: t("tableDelete"),
        image: t("image"),
        imageTitle: t("imageTitle"),
        imageDescription: t("imageDescription"),
        imageSourceLabel: t("imageSourceLabel"),
        imageSourcePlaceholder: t("imageSourcePlaceholder"),
        imageAltLabel: t("imageAltLabel"),
        imageAltPlaceholder: t("imageAltPlaceholder"),
        imageInvalid: t("imageInvalid"),
        imageAltRequired: t("imageAltRequired"),
        imageApply: t("imageApply"),
        imageUpdate: t("imageUpdate"),
        imageRemove: t("imageRemove"),
        file: t("slashFileCmd"),
        fileTitle: t("fileTitle"),
        fileDescription: t("fileDescription"),
        fileSearchLabel: t("fileSearchLabel"),
        fileSearchPlaceholder: t("slashPickerPrompt"),
        fileSearching: t("slashPickerSearching"),
        fileNoResults: t("slashPickerNoResults"),
        fileSearchError: t("slashPickerSearchError"),
        fileRetry: t("slashPickerRetry"),
        filePickerAria: t("slashReferencePickerAria"),
    };

    return (
        <div className={className}>
            {editable ? (
                <EditorToolbar
                    editor={editor}
                    labels={labels}
                    compact={compact}
                    filePickerOpen={filePickerOpen}
                    onFilePickerOpenChange={setFilePickerOpen}
                />
            ) : null}
            {editable && !compact && editor ? <SelectionToolbar editor={editor} labels={labels} /> : null}
            {editable && !compact && editor ? (
                <DragHandle editor={editor} className="note-drag-handle">
                    <span className="note-drag-handle-grip" aria-hidden="true">
                        <GripVertical className="size-4" />
                    </span>
                </DragHandle>
            ) : null}
            {compact ? (
                <div className="px-3 py-2.5">
                    <EditorContent editor={editor} />
                </div>
            ) : (
                <EditorContent editor={editor} />
            )}
        </div>
    );
}
