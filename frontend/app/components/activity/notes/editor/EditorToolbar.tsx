"use client";

import { useEffect, useState } from "react";
import type { Editor } from "@tiptap/core";
import {
    Bold,
    Code,
    Heading1,
    Heading2,
    Heading3,
    Italic,
    List,
    ListOrdered,
    ListTodo,
    SquareCode,
    Strikethrough,
    TextQuote,
} from "lucide-react";

export type ToolbarLabels = {
    bold: string;
    italic: string;
    strike: string;
    code: string;
    h1: string;
    h2: string;
    h3: string;
    bulletList: string;
    orderedList: string;
    taskList: string;
    blockquote: string;
    codeBlock: string;
};

type Props = { editor: Editor | null; labels: ToolbarLabels; compact?: boolean };

export function EditorToolbar({ editor, labels, compact = false }: Props) {
    const [, setTick] = useState(0);

    useEffect(() => {
        if (!editor) return;
        const bump = () => setTick((tick) => tick + 1);
        editor.on("selectionUpdate", bump);
        editor.on("transaction", bump);
        return () => {
            editor.off("selectionUpdate", bump);
            editor.off("transaction", bump);
        };
    }, [editor]);

    if (!editor) return null;

    const button = (
        key: string,
        label: string,
        active: boolean,
        run: () => void,
        Icon: typeof Bold,
    ) => (
        <button
            key={key}
            type="button"
            aria-label={label}
            aria-pressed={active}
            title={label}
            onMouseDown={(event) => event.preventDefault()}
            onClick={run}
            className={`flex h-8 w-8 items-center justify-center rounded-md transition-colors ${
                active
                    ? "bg-accent text-foreground"
                    : "text-muted-foreground hover:bg-accent/60 hover:text-foreground"
            }`}
        >
            <Icon className="h-4 w-4" />
        </button>
    );

    const divider = (key: string) => (
        <span key={key} className="mx-1 h-5 w-px bg-border" aria-hidden="true" />
    );

    return (
        <div
            className={
                compact
                    ? "flex flex-wrap items-center gap-0.5 border-b border-border bg-muted/40 px-1.5 py-1"
                    : "sticky top-0 z-10 mb-2 flex flex-wrap items-center gap-0.5 rounded-xl border border-border bg-card/85 p-1 backdrop-blur"
            }
        >
            {button("h1", labels.h1, editor.isActive("heading", { level: 1 }), () => editor.chain().focus().toggleHeading({ level: 1 }).run(), Heading1)}
            {button("h2", labels.h2, editor.isActive("heading", { level: 2 }), () => editor.chain().focus().toggleHeading({ level: 2 }).run(), Heading2)}
            {button("h3", labels.h3, editor.isActive("heading", { level: 3 }), () => editor.chain().focus().toggleHeading({ level: 3 }).run(), Heading3)}
            {divider("d1")}
            {button("bold", labels.bold, editor.isActive("bold"), () => editor.chain().focus().toggleBold().run(), Bold)}
            {button("italic", labels.italic, editor.isActive("italic"), () => editor.chain().focus().toggleItalic().run(), Italic)}
            {button("strike", labels.strike, editor.isActive("strike"), () => editor.chain().focus().toggleStrike().run(), Strikethrough)}
            {button("code", labels.code, editor.isActive("code"), () => editor.chain().focus().toggleCode().run(), Code)}
            {divider("d2")}
            {button("bullet", labels.bulletList, editor.isActive("bulletList"), () => editor.chain().focus().toggleBulletList().run(), List)}
            {button("ordered", labels.orderedList, editor.isActive("orderedList"), () => editor.chain().focus().toggleOrderedList().run(), ListOrdered)}
            {button("task", labels.taskList, editor.isActive("taskList"), () => editor.chain().focus().toggleTaskList().run(), ListTodo)}
            {divider("d3")}
            {button("quote", labels.blockquote, editor.isActive("blockquote"), () => editor.chain().focus().toggleBlockquote().run(), TextQuote)}
            {button("codeBlock", labels.codeBlock, editor.isActive("codeBlock"), () => editor.chain().focus().toggleCodeBlock().run(), SquareCode)}
        </div>
    );
}
