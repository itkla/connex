"use client";

import { useEffect, useId, useState, type FormEvent } from "react";
import type { Editor } from "@tiptap/core";
import {
    Bold,
    BrushCleaning,
    Code,
    Heading1,
    Heading2,
    Heading3,
    Italic,
    Link2,
    Link2Off,
    List,
    ListOrdered,
    ListTodo,
    Redo2,
    SquareCode,
    Strikethrough,
    TextQuote,
    Underline,
    Undo2,
    type LucideIcon,
} from "lucide-react";

import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
    Popover,
    PopoverContent,
    PopoverDescription,
    PopoverTitle,
    PopoverTrigger,
} from "@/components/ui/popover";
import { cn } from "@/lib/utils";
import { normalizeEditorLinkHref } from "./editorLinks";

export type ToolbarLabels = {
    bold: string;
    italic: string;
    underline: string;
    strike: string;
    code: string;
    link: string;
    linkTitle: string;
    linkDescription: string;
    linkLabel: string;
    linkPlaceholder: string;
    linkInvalid: string;
    linkApply: string;
    unlink: string;
    clearFormatting: string;
    selectionToolbar: string;
    undo: string;
    redo: string;
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

function toolbarButtonClass(active = false) {
    return cn(
        "flex size-8 items-center justify-center rounded-md transition-colors",
        active
            ? "bg-accent text-foreground"
            : "text-muted-foreground hover:bg-accent/60 hover:text-foreground",
    );
}

function ToolbarButton({
    label,
    active,
    disabled = false,
    onClick,
    icon: Icon,
}: {
    label: string;
    active?: boolean;
    disabled?: boolean;
    onClick: () => void;
    icon: LucideIcon;
}) {
    return (
        <button
            type="button"
            aria-label={label}
            aria-pressed={active}
            title={label}
            disabled={disabled}
            onMouseDown={(event) => event.preventDefault()}
            onClick={onClick}
            className={cn(toolbarButtonClass(active), "disabled:pointer-events-none disabled:opacity-40")}
        >
            <Icon className="size-4" />
        </button>
    );
}

function LinkPopover({ editor, labels }: { editor: Editor; labels: ToolbarLabels }) {
    const inputId = useId();
    const errorId = `${inputId}-error`;
    const [open, setOpen] = useState(false);
    const [href, setHref] = useState("");
    const active = editor.isActive("link");
    const canEdit = active || !editor.state.selection.empty;
    const normalizedHref = normalizeEditorLinkHref(href);
    const invalid = href.trim().length > 0 && normalizedHref === null;

    const handleOpenChange = (nextOpen: boolean) => {
        if (nextOpen) {
            const currentHref: unknown = editor.getAttributes("link").href;
            setHref(typeof currentHref === "string" ? currentHref : "");
        }
        setOpen(nextOpen);
    };

    const applyLink = (event?: FormEvent) => {
        event?.preventDefault();
        if (!normalizedHref) return;
        editor.chain().focus().extendMarkRange("link").setLink({ href: normalizedHref }).run();
        setOpen(false);
    };

    const removeLink = () => {
        editor.chain().focus().extendMarkRange("link").unsetLink().run();
        setOpen(false);
    };

    return (
        <Popover open={open} onOpenChange={handleOpenChange}>
            <PopoverTrigger
                type="button"
                aria-label={labels.link}
                aria-pressed={active}
                title={labels.link}
                disabled={!canEdit}
                onMouseDown={(event) => event.preventDefault()}
                className={cn(toolbarButtonClass(active), "disabled:pointer-events-none disabled:opacity-40")}
            >
                <Link2 className="size-4" />
            </PopoverTrigger>
            <PopoverContent align="start" className="w-80">
                <PopoverTitle className="text-sm font-semibold text-foreground">{labels.linkTitle}</PopoverTitle>
                <PopoverDescription className="mt-1 text-xs leading-relaxed text-muted-foreground">
                    {labels.linkDescription}
                </PopoverDescription>
                <form className="mt-4 grid gap-3" onSubmit={applyLink}>
                    <div className="grid gap-1.5">
                        <Label htmlFor={inputId}>{labels.linkLabel}</Label>
                        <Input
                            id={inputId}
                            value={href}
                            onChange={(event) => setHref(event.target.value)}
                            placeholder={labels.linkPlaceholder}
                            autoCapitalize="none"
                            autoCorrect="off"
                            spellCheck={false}
                            aria-invalid={invalid || undefined}
                            aria-describedby={invalid ? errorId : undefined}
                            autoFocus
                        />
                        {invalid ? (
                            <p id={errorId} className="text-xs text-destructive" role="alert">
                                {labels.linkInvalid}
                            </p>
                        ) : null}
                    </div>
                    <div className="flex justify-end gap-2">
                        {active ? (
                            <Button type="button" variant="ghost" size="sm" onClick={removeLink}>
                                <Link2Off className="size-4" />
                                {labels.unlink}
                            </Button>
                        ) : null}
                        <Button type="submit" variant="brand" size="sm" disabled={!normalizedHref}>
                            {labels.linkApply}
                        </Button>
                    </div>
                </form>
            </PopoverContent>
        </Popover>
    );
}

export function InlineFormattingControls({
    editor,
    labels,
    showLink = false,
    showClear = false,
}: {
    editor: Editor;
    labels: ToolbarLabels;
    showLink?: boolean;
    showClear?: boolean;
}) {
    return (
        <>
            <ToolbarButton label={labels.bold} active={editor.isActive("bold")} onClick={() => editor.chain().focus().toggleBold().run()} icon={Bold} />
            <ToolbarButton label={labels.italic} active={editor.isActive("italic")} onClick={() => editor.chain().focus().toggleItalic().run()} icon={Italic} />
            <ToolbarButton label={labels.underline} active={editor.isActive("noteUnderline")} onClick={() => editor.chain().focus().toggleMark("noteUnderline").run()} icon={Underline} />
            <ToolbarButton label={labels.strike} active={editor.isActive("strike")} onClick={() => editor.chain().focus().toggleStrike().run()} icon={Strikethrough} />
            <ToolbarButton label={labels.code} active={editor.isActive("code")} onClick={() => editor.chain().focus().toggleCode().run()} icon={Code} />
            {showLink ? <LinkPopover editor={editor} labels={labels} /> : null}
            {showClear ? (
                <ToolbarButton label={labels.clearFormatting} onClick={() => editor.chain().focus().unsetAllMarks().run()} icon={BrushCleaning} />
            ) : null}
        </>
    );
}

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
            <ToolbarButton label={labels.undo} disabled={!editor.can().undo()} onClick={() => editor.chain().focus().undo().run()} icon={Undo2} />
            <ToolbarButton label={labels.redo} disabled={!editor.can().redo()} onClick={() => editor.chain().focus().redo().run()} icon={Redo2} />
            {divider("d1")}
            <ToolbarButton label={labels.h1} active={editor.isActive("heading", { level: 1 })} onClick={() => editor.chain().focus().toggleHeading({ level: 1 }).run()} icon={Heading1} />
            <ToolbarButton label={labels.h2} active={editor.isActive("heading", { level: 2 })} onClick={() => editor.chain().focus().toggleHeading({ level: 2 }).run()} icon={Heading2} />
            <ToolbarButton label={labels.h3} active={editor.isActive("heading", { level: 3 })} onClick={() => editor.chain().focus().toggleHeading({ level: 3 }).run()} icon={Heading3} />
            {divider("d2")}
            <InlineFormattingControls editor={editor} labels={labels} showLink />
            {divider("d3")}
            <ToolbarButton label={labels.bulletList} active={editor.isActive("bulletList")} onClick={() => editor.chain().focus().toggleBulletList().run()} icon={List} />
            <ToolbarButton label={labels.orderedList} active={editor.isActive("orderedList")} onClick={() => editor.chain().focus().toggleOrderedList().run()} icon={ListOrdered} />
            <ToolbarButton label={labels.taskList} active={editor.isActive("taskList")} onClick={() => editor.chain().focus().toggleTaskList().run()} icon={ListTodo} />
            {divider("d4")}
            <ToolbarButton label={labels.blockquote} active={editor.isActive("blockquote")} onClick={() => editor.chain().focus().toggleBlockquote().run()} icon={TextQuote} />
            <ToolbarButton label={labels.codeBlock} active={editor.isActive("codeBlock")} onClick={() => editor.chain().focus().toggleCodeBlock().run()} icon={SquareCode} />
            <ToolbarButton label={labels.clearFormatting} onClick={() => editor.chain().focus().unsetAllMarks().clearNodes().run()} icon={BrushCleaning} />
        </div>
    );
}
