"use client";

import { useEffect, useId, useRef, useState, type DragEvent, type FormEvent } from "react";
import type { Editor } from "@tiptap/core";
import {
    ArrowDownToLine,
    ArrowUpToLine,
    BetweenHorizontalEnd,
    BetweenVerticalEnd,
    Bold,
    BrushCleaning,
    Code,
    Columns3,
    Heading1,
    Heading2,
    Heading3,
    ImagePlus,
    Italic,
    Link2,
    Link2Off,
    List,
    ListOrdered,
    ListTodo,
    LoaderCircle,
    Redo2,
    Rows3,
    SquareCode,
    Strikethrough,
    Table2,
    TextQuote,
    Trash2,
    Underline,
    Undo2,
    type LucideIcon,
} from "lucide-react";
import { PhotoIcon } from "@heroicons/react/24/outline";

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
import { uploadAttachment } from "@/app/lib/api";
import { isManagedImageFile, MANAGED_IMAGE_ACCEPT } from "@/app/lib/managed-image";
import { toastError } from "@/app/lib/toast";
import { canMoveTopLevelBlock, moveTopLevelBlock } from "./BlockReorder";
import { normalizeEditorLinkHref } from "./editorLinks";
import {
    DEFAULT_MARKDOWN_TABLE_OPTIONS,
    canDeleteMarkdownTableRow,
} from "./MarkdownTable";
import { normalizeNoteImageSource } from "./MarkdownImage";
import { FileReferencePopover, type FileReferenceLabels } from "./FileReferencePopover";

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
    moveBlockUp: string;
    moveBlockDown: string;
    h1: string;
    h2: string;
    h3: string;
    bulletList: string;
    orderedList: string;
    taskList: string;
    blockquote: string;
    codeBlock: string;
    tableInsert: string;
    tableAddRow: string;
    tableAddColumn: string;
    tableDeleteRow: string;
    tableDeleteColumn: string;
    tableDelete: string;
    image: string;
    imageTitle: string;
    imageDescription: string;
    imageSourceLabel: string;
    imageSourcePlaceholder: string;
    imageAltLabel: string;
    imageAltPlaceholder: string;
    imageInvalid: string;
    imageAltRequired: string;
    imageApply: string;
    imageUpdate: string;
    imageRemove: string;
    imageDropHint: string;
    imageUploading: string;
    imageUploadFailed: string;
    imageUnsupportedType: string;
} & FileReferenceLabels;

type Props = {
    editor: Editor | null;
    labels: ToolbarLabels;
    compact?: boolean;
    ensureNoteId?: () => Promise<number>;
    filePickerOpen?: boolean;
    onFilePickerOpenChange?: (open: boolean) => void;
};

function defaultAltFromFileName(fileName: string): string {
    const base = fileName.replace(/\.[^.]+$/u, "").replace(/[_-]+/gu, " ").trim();
    return base || fileName;
}

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

function ImagePopover({
    editor,
    labels,
    ensureNoteId,
}: {
    editor: Editor;
    labels: ToolbarLabels;
    ensureNoteId?: () => Promise<number>;
}) {
    const sourceId = useId();
    const altId = useId();
    const sourceErrorId = `${sourceId}-error`;
    const altErrorId = `${altId}-error`;
    const fileInputRef = useRef<HTMLInputElement>(null);
    const [open, setOpen] = useState(false);
    const [source, setSource] = useState("");
    const [alt, setAlt] = useState("");
    const [altTouched, setAltTouched] = useState(false);
    const [dragActive, setDragActive] = useState(false);
    const [uploading, setUploading] = useState(false);
    const active = editor.isActive("image");
    const normalizedSource = normalizeNoteImageSource(source);
    const sourceInvalid = source.trim().length > 0 && normalizedSource === null;
    const altMissing = alt.trim().length === 0;
    const showAltError = altTouched && altMissing;
    const canInsert = active || editor.can().setImage({ src: "https://connex.invalid/image", alt: "Image" });

    const handleOpenChange = (nextOpen: boolean) => {
        if (uploading) return;
        if (nextOpen) {
            const attributes = editor.getAttributes("image");
            setSource(typeof attributes.src === "string" ? attributes.src : "");
            setAlt(typeof attributes.alt === "string" ? attributes.alt : "");
            setAltTouched(false);
            setDragActive(false);
        }
        setOpen(nextOpen);
    };

    const applyImageAttrs = (nextSource: string, nextAlt: string) => {
        if (active) {
            editor.chain().focus().updateAttributes("image", { src: nextSource, alt: nextAlt }).run();
        } else {
            editor.chain().focus().setImage({ src: nextSource, alt: nextAlt }).run();
        }
    };

    const applyImage = (event?: FormEvent) => {
        event?.preventDefault();
        const description = alt.trim();
        if (!normalizedSource || !description) return;
        applyImageAttrs(normalizedSource, description);
        setOpen(false);
    };

    const removeImage = () => {
        editor.chain().focus().deleteSelection().run();
        setOpen(false);
    };

    const uploadImageFile = async (file: File) => {
        if (!ensureNoteId) {
            toastError(labels.imageUploadFailed);
            return;
        }
        if (!(await isManagedImageFile(file))) {
            toastError(labels.imageUnsupportedType);
            return;
        }
        setUploading(true);
        try {
            const noteId = await ensureNoteId();
            const attachment = await uploadAttachment("note", noteId, file);
            const nextSource = normalizeNoteImageSource(attachment.url);
            if (!nextSource) {
                toastError(labels.imageUploadFailed);
                return;
            }
            const nextAlt = alt.trim() || defaultAltFromFileName(file.name || attachment.fileName);
            setSource(nextSource);
            setAlt(nextAlt);
            applyImageAttrs(nextSource, nextAlt);
            setOpen(false);
        } catch {
            toastError(labels.imageUploadFailed);
        } finally {
            setUploading(false);
            setDragActive(false);
        }
    };

    const onDropZoneDragOver = (event: DragEvent<HTMLButtonElement>) => {
        event.preventDefault();
        if (!dragActive) setDragActive(true);
    };
    const onDropZoneDragLeave = (event: DragEvent<HTMLButtonElement>) => {
        event.preventDefault();
        setDragActive(false);
    };
    const onDropZoneDrop = (event: DragEvent<HTMLButtonElement>) => {
        event.preventDefault();
        setDragActive(false);
        const file = event.dataTransfer.files?.[0];
        if (file) void uploadImageFile(file);
    };

    return (
        <Popover open={open} onOpenChange={handleOpenChange}>
            <PopoverTrigger
                type="button"
                aria-label={labels.image}
                aria-pressed={active}
                title={labels.image}
                disabled={!canInsert}
                onMouseDown={(event) => event.preventDefault()}
                className={cn(toolbarButtonClass(active), "disabled:pointer-events-none disabled:opacity-40")}
            >
                <ImagePlus className="size-4" />
            </PopoverTrigger>
            <PopoverContent align="start" className="w-80">
                <PopoverTitle className="text-sm font-semibold text-foreground">{labels.imageTitle}</PopoverTitle>
                <PopoverDescription className="mt-1 text-xs leading-relaxed text-muted-foreground">
                    {labels.imageDescription}
                </PopoverDescription>
                <div className="mt-4 grid gap-3">
                    {ensureNoteId ? (
                        <>
                            <input
                                ref={fileInputRef}
                                type="file"
                                accept={MANAGED_IMAGE_ACCEPT}
                                className="hidden"
                                onChange={(event) => {
                                    const file = event.target.files?.[0];
                                    event.target.value = "";
                                    if (file) void uploadImageFile(file);
                                }}
                            />
                            <button
                                type="button"
                                onClick={() => fileInputRef.current?.click()}
                                onDragOver={onDropZoneDragOver}
                                onDragLeave={onDropZoneDragLeave}
                                onDrop={onDropZoneDrop}
                                disabled={uploading}
                                className={cn(
                                    "flex w-full cursor-pointer items-center justify-center gap-2 rounded-lg border-2 border-dashed px-3 py-3 text-xs transition-colors",
                                    dragActive
                                        ? "border-brand bg-brand/5 text-brand"
                                        : "border-border text-muted-foreground hover:border-muted-foreground/40 hover:bg-muted/60",
                                    "disabled:pointer-events-none disabled:opacity-60",
                                )}
                            >
                                {uploading ? (
                                    <LoaderCircle className="size-4 animate-spin" />
                                ) : (
                                    <PhotoIcon className="size-4" />
                                )}
                                <span>{uploading ? labels.imageUploading : labels.imageDropHint}</span>
                            </button>
                        </>
                    ) : null}
                    <form className="grid gap-3" onSubmit={applyImage}>
                        <div className="grid gap-1.5">
                            <Label htmlFor={sourceId}>{labels.imageSourceLabel}</Label>
                            <Input
                                id={sourceId}
                                value={source}
                                onChange={(event) => setSource(event.target.value)}
                                placeholder={labels.imageSourcePlaceholder}
                                autoCapitalize="none"
                                autoCorrect="off"
                                spellCheck={false}
                                disabled={uploading}
                                aria-invalid={sourceInvalid || undefined}
                                aria-describedby={sourceInvalid ? sourceErrorId : undefined}
                                autoFocus
                            />
                            {sourceInvalid ? (
                                <p id={sourceErrorId} className="text-xs text-destructive" role="alert">
                                    {labels.imageInvalid}
                                </p>
                            ) : null}
                        </div>
                        <div className="grid gap-1.5">
                            <Label htmlFor={altId}>{labels.imageAltLabel}</Label>
                            <Input
                                id={altId}
                                value={alt}
                                onChange={(event) => setAlt(event.target.value)}
                                onBlur={() => setAltTouched(true)}
                                placeholder={labels.imageAltPlaceholder}
                                required
                                disabled={uploading}
                                aria-invalid={showAltError || undefined}
                                aria-describedby={showAltError ? altErrorId : undefined}
                            />
                            {showAltError ? (
                                <p id={altErrorId} className="text-xs text-destructive" role="alert">
                                    {labels.imageAltRequired}
                                </p>
                            ) : null}
                        </div>
                        <div className="flex justify-end gap-2">
                            {active ? (
                                <Button type="button" variant="ghost" size="sm" onClick={removeImage} disabled={uploading}>
                                    <Trash2 className="size-4" />
                                    {labels.imageRemove}
                                </Button>
                            ) : null}
                            <Button
                                type="submit"
                                variant="brand"
                                size="sm"
                                disabled={uploading || !normalizedSource || altMissing}
                            >
                                {active ? labels.imageUpdate : labels.imageApply}
                            </Button>
                        </div>
                    </form>
                </div>
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

export function EditorToolbar({
    editor,
    labels,
    compact = false,
    ensureNoteId,
    filePickerOpen = false,
    onFilePickerOpenChange,
}: Props) {
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

    const inTable = editor.isActive("table");
    const canInsertTable = !inTable && editor.can().insertTable(DEFAULT_MARKDOWN_TABLE_OPTIONS);

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
            {!compact ? (
                <>
                    <ToolbarButton label={labels.moveBlockUp} disabled={!canMoveTopLevelBlock(editor, "up")} onClick={() => moveTopLevelBlock(editor, "up")} icon={ArrowUpToLine} />
                    <ToolbarButton label={labels.moveBlockDown} disabled={!canMoveTopLevelBlock(editor, "down")} onClick={() => moveTopLevelBlock(editor, "down")} icon={ArrowDownToLine} />
                </>
            ) : null}
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
            {divider("d5")}
            <ToolbarButton label={labels.tableInsert} disabled={!canInsertTable} onClick={() => editor.chain().focus().insertTable(DEFAULT_MARKDOWN_TABLE_OPTIONS).run()} icon={Table2} />
            {inTable ? (
                <>
                    <ToolbarButton label={labels.tableAddRow} disabled={!editor.can().addRowAfter()} onClick={() => editor.chain().focus().addRowAfter().run()} icon={BetweenHorizontalEnd} />
                    <ToolbarButton label={labels.tableAddColumn} disabled={!editor.can().addColumnAfter()} onClick={() => editor.chain().focus().addColumnAfter().run()} icon={BetweenVerticalEnd} />
                    <ToolbarButton label={labels.tableDeleteRow} disabled={!canDeleteMarkdownTableRow(editor)} onClick={() => editor.chain().focus().deleteRow().run()} icon={Rows3} />
                    <ToolbarButton label={labels.tableDeleteColumn} disabled={!editor.can().deleteColumn()} onClick={() => editor.chain().focus().deleteColumn().run()} icon={Columns3} />
                    <ToolbarButton label={labels.tableDelete} disabled={!editor.can().deleteTable()} onClick={() => editor.chain().focus().deleteTable().run()} icon={Trash2} />
                </>
            ) : null}
            <ImagePopover editor={editor} labels={labels} ensureNoteId={ensureNoteId} />
            {onFilePickerOpenChange ? (
                <FileReferencePopover
                    editor={editor}
                    labels={labels}
                    open={filePickerOpen}
                    onOpenChange={onFilePickerOpenChange}
                />
            ) : null}
        </div>
    );
}
