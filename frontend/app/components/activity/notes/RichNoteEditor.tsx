"use client";

import { useEffect, useRef } from "react";
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
import { SlashCommand } from "./editor/SlashCommand";
import { buildSlashCommands } from "./editor/slashCommands";

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
};

/**
 * WYSIWYG note editor built on Tiptap. Stores Markdown with inline
 * `[Label](type:id)` reference tokens so the backend ReferenceService keeps
 * resolving @/# mentions unchanged.
 */
export default function RichNoteEditor({
    value,
    onChange,
    editable = true,
    excludeUserId,
    autofocus = false,
    className,
}: Props) {
    const t = useTranslations("ActivityNotesEditor");
    const onChangeRef = useRef(onChange);
    const loadingRef = useRef(false);

    useEffect(() => {
        onChangeRef.current = onChange;
    }, [onChange]);

    const editor = useEditor({
        editable,
        immediatelyRender: false,
        autofocus: autofocus ? "end" : false,
        extensions: [
            StarterKit.configure({ heading: { levels: [1, 2, 3] } }),
            Placeholder.configure({ placeholder: t("placeholder") }),
            TaskList,
            TaskItem.configure({ nested: true }),
            Markdown.configure({
                html: false,
                breaks: true,
                linkify: false,
                transformPastedText: true,
                transformCopiedText: true,
            }),
            Mention.configure({ excludeUserId }),
            SlashCommand.configure({ commands: buildSlashCommands(t) }),
        ],
        editorProps: {
            attributes: {
                class: "note-prose min-h-[15rem] max-w-none focus:outline-none",
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

    const labels = {
        bold: t("bold"),
        italic: t("italic"),
        strike: t("strike"),
        code: t("code"),
        h1: t("heading1"),
        h2: t("heading2"),
        h3: t("heading3"),
        bulletList: t("bulletList"),
        orderedList: t("orderedList"),
        taskList: t("taskList"),
        blockquote: t("blockquote"),
        codeBlock: t("codeBlock"),
    };

    return (
        <div className={className}>
            {editable ? <EditorToolbar editor={editor} labels={labels} /> : null}
            <EditorContent editor={editor} />
        </div>
    );
}
