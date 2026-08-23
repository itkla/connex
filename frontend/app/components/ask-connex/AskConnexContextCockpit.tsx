'use client';

import type { ReactNode, RefObject } from 'react';
import {
    ArrowUturnLeftIcon,
    BookmarkIcon,
    BookmarkSlashIcon,
    DocumentTextIcon,
    ExclamationCircleIcon,
    ListBulletIcon,
    MapPinIcon,
    PhotoIcon,
    XMarkIcon,
} from '@heroicons/react/24/outline';
import { motion, useReducedMotion } from 'motion/react';

import type {
    AskConnexAttachment,
    AskConnexFileAttachment,
    AskConnexScopePreview,
    AskConnexSelectionContext,
} from '@/app/lib/askConnex';
import { durationMicro, easeOut, instant } from '@/app/lib/motion';
import { formatFileSize } from '@/app/lib/utils';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';

/**
 * Everything the context cockpit says, resolved by its owner so the strip itself stays free of
 * translation lookups and can be rendered from a test with literal copy.
 */
export type AskConnexContextLabels = {
    context: string;
    contextFile: string;
    contextLimit: string;
    contextMentioned: string;
    contextPage: string;
    contextPinned: string;
    contextReset: string;
    contextSelected: (count: number, type: AskConnexSelectionContext['type']) => string;
    contextScopeUnsupported: string;
    contextUnavailable: string;
    contextUnsupported: (type: AskConnexSelectionContext['type']) => string;
    pinContext: (label: string) => string;
    removeContext: (label: string) => string;
    removeFile: (label: string) => string;
    scopeConfirm: string;
    scopeEdit: string;
    scopeSummary: (preview: AskConnexScopePreview) => string;
    scopeTitle: string;
    unpinContext: (label: string) => string;
    uploadProgress: (progress: number) => string;
    uploadRemoving: string;
};

/**
 * One control inside a context chip. Chips commit to the badge height rather than the button
 * height scale, so their inline controls are part of the chip's own shape; `Button`/`IconButton`
 * would break the row. Factored out so every chip's remove, pin, and unpin affordance is the same
 * target with the same focus treatment.
 */
function ChipControl({
    label,
    disabled,
    pressed,
    onClick,
    children,
}: {
    label: string;
    disabled?: boolean;
    pressed?: boolean;
    onClick: () => void;
    children: ReactNode;
}) {
    return (
        <button
            type="button"
            aria-label={label}
            title={label}
            aria-pressed={pressed}
            disabled={disabled}
            onClick={onClick}
            className="rounded-full p-0.5 outline-none hover:bg-foreground/10 focus-visible:ring-2 focus-visible:ring-ring disabled:opacity-50"
        >
            {children}
        </button>
    );
}

/**
 * The context cockpit: every input the next answer will use, named and correctable before it runs.
 *
 * Each kind of context — the page you are on, records you keep across navigation, rows you
 * selected, records you mentioned, files you attached — reads as the same chip family, and each
 * carries its own way out. Context that is visible but cannot be sent stays visible and says so
 * rather than being dropped silently, and taking an inferred input out exposes a way to put it
 * back, so a correction is never something the user has to remember making.
 */
export function AskConnexContextStrip({
    groupRef,
    implicitContext,
    pinnedContext,
    pageContextPinned,
    selectionContext,
    unsupportedPageContext,
    attachments,
    fileAttachments,
    canRemoveFiles,
    fileOperationPending,
    overflow,
    corrected,
    labels,
    onRemove,
    onRemoveFile,
    onTogglePagePin,
    onUnpin,
    onRemovePage,
    onRemoveSelection,
    onReset,
}: {
    groupRef: RefObject<HTMLDivElement | null>;
    implicitContext: AskConnexAttachment | null;
    pinnedContext: readonly AskConnexAttachment[];
    pageContextPinned: boolean;
    selectionContext: AskConnexSelectionContext | null;
    unsupportedPageContext: { type: AskConnexSelectionContext['type']; label: string } | null;
    attachments: AskConnexAttachment[];
    fileAttachments: AskConnexFileAttachment[];
    canRemoveFiles: boolean;
    fileOperationPending: boolean;
    overflow: boolean;
    corrected: boolean;
    labels: AskConnexContextLabels;
    onRemove: (attachment: AskConnexAttachment) => void;
    onRemoveFile: (attachment: AskConnexFileAttachment) => void;
    onTogglePagePin: () => void;
    onUnpin: (attachment: AskConnexAttachment) => void;
    onRemovePage: () => void;
    onRemoveSelection: () => void;
    onReset: () => void;
}) {
    const reduceMotion = useReducedMotion() ?? false;
    if (!implicitContext
            && pinnedContext.length === 0
            && !selectionContext
            && !unsupportedPageContext
            && attachments.length === 0
            && fileAttachments.length === 0
            && !overflow
            && !corrected) return null;
    const unavailableExplanation = selectionContext?.unavailableReason === 'scope'
        ? labels.contextScopeUnsupported
        : unsupportedPageContext !== null
            ? labels.contextUnsupported(unsupportedPageContext.type)
            : selectionContext?.available === false
                ? labels.contextUnsupported(selectionContext.type)
                : null;
    const selectionLabel = selectionContext
        ? labels.contextSelected(selectionContext.count, selectionContext.type)
        : '';

    return (
        <div
            ref={groupRef}
            role="group"
            tabIndex={-1}
            className="mb-2 outline-none"
            aria-label={labels.context}
        >
            <div className="flex min-w-0 items-center gap-1.5 overflow-x-auto">
                {implicitContext ? (
                    <Badge variant="outline" className="max-w-56 shrink-0 pr-1 text-muted-foreground">
                        <MapPinIcon />
                        <span className="shrink-0 font-medium">{labels.contextPage}</span>
                        <span aria-hidden>·</span>
                        <span className="truncate">{implicitContext.label}</span>
                        <ChipControl
                            label={pageContextPinned
                                ? labels.unpinContext(implicitContext.label)
                                : labels.pinContext(implicitContext.label)}
                            pressed={pageContextPinned}
                            onClick={onTogglePagePin}
                        >
                            {pageContextPinned
                                ? <BookmarkSlashIcon className="size-3" />
                                : <BookmarkIcon className="size-3" />}
                        </ChipControl>
                        <ChipControl
                            label={labels.removeContext(implicitContext.label)}
                            onClick={onRemovePage}
                        >
                            <XMarkIcon className="size-3" />
                        </ChipControl>
                    </Badge>
                ) : null}
                {pinnedContext.map((pin) => (
                    <Badge key={`pinned:${pin.kind}:${pin.id}`} variant="secondary" className="max-w-56 shrink-0 pr-1">
                        <BookmarkIcon />
                        <span className="shrink-0 font-medium">{labels.contextPinned}</span>
                        <span aria-hidden>·</span>
                        <span className="truncate">{pin.label}</span>
                        <ChipControl label={labels.unpinContext(pin.label)} onClick={() => onUnpin(pin)}>
                            <XMarkIcon className="size-3" />
                        </ChipControl>
                    </Badge>
                ))}
                {unsupportedPageContext ? (
                    <Badge variant="outline" className="max-w-56 shrink-0 pr-1 text-muted-foreground">
                        <ExclamationCircleIcon />
                        <span className="shrink-0 font-medium">{labels.contextUnavailable}</span>
                        <span aria-hidden>·</span>
                        <span className="truncate">{unsupportedPageContext.label}</span>
                        <ChipControl
                            label={labels.removeContext(unsupportedPageContext.label)}
                            onClick={onRemovePage}
                        >
                            <XMarkIcon className="size-3" />
                        </ChipControl>
                    </Badge>
                ) : null}
                {selectionContext ? (
                    <Badge
                        variant={selectionContext.available ? 'secondary' : 'outline'}
                        className="max-w-56 shrink-0 pr-1 text-muted-foreground"
                    >
                        {selectionContext.available
                            ? <ListBulletIcon />
                            : <ExclamationCircleIcon />}
                        {!selectionContext.available ? (
                            <>
                                <span className="shrink-0 font-medium">{labels.contextUnavailable}</span>
                                <span aria-hidden>·</span>
                            </>
                        ) : null}
                        <span className="truncate">{selectionLabel}</span>
                        <ChipControl
                            label={labels.removeContext(selectionLabel)}
                            onClick={onRemoveSelection}
                        >
                            <XMarkIcon className="size-3" />
                        </ChipControl>
                    </Badge>
                ) : null}
                {attachments.map((attachment) => (
                    <Badge key={`${attachment.kind}:${attachment.id}`} variant="secondary" className="max-w-44 shrink-0 pr-1">
                        <span className="shrink-0 font-medium">{labels.contextMentioned}</span>
                        <span aria-hidden>·</span>
                        <span className="truncate">{attachment.label}</span>
                        <ChipControl
                            label={labels.removeContext(attachment.label)}
                            onClick={() => onRemove(attachment)}
                        >
                            <XMarkIcon className="size-3" />
                        </ChipControl>
                    </Badge>
                ))}
                {fileAttachments.map((attachment) => {
                    const FileIcon = attachment.kind === 'image' ? PhotoIcon : DocumentTextIcon;
                    const detail = attachment.status === 'uploading'
                        ? labels.uploadProgress(attachment.progress)
                        : attachment.status === 'removing'
                            ? labels.uploadRemoving
                        : attachment.status === 'failed'
                            ? attachment.error
                            : formatFileSize(attachment.size);
                    return (
                        <Badge
                            key={attachment.clientId}
                            variant={attachment.status === 'failed' ? 'destructive' : 'outline'}
                            aria-invalid={attachment.status === 'failed' || undefined}
                            className="h-auto max-w-56 shrink-0 py-1 pr-1"
                        >
                            <FileIcon />
                            <span className="min-w-0">
                                <span className="block truncate">
                                    <span className="font-medium">{labels.contextFile}</span>
                                    <span aria-hidden> · </span>
                                    {attachment.fileName}
                                </span>
                                <span
                                    role={attachment.status === 'failed'
                                        ? 'alert'
                                        : attachment.status === 'uploading' || attachment.status === 'removing'
                                            ? 'status'
                                            : undefined}
                                    className="block truncate text-[10px] font-normal opacity-70"
                                >
                                    {detail}
                                </span>
                            </span>
                            <ChipControl
                                label={labels.removeFile(attachment.fileName)}
                                disabled={!canRemoveFiles
                                    || fileOperationPending
                                    || attachment.status === 'uploading'
                                    || attachment.status === 'removing'}
                                onClick={() => onRemoveFile(attachment)}
                            >
                                <XMarkIcon className="size-3" />
                            </ChipControl>
                        </Badge>
                    );
                })}
            </div>
            {unavailableExplanation ? (
                <p className="mt-1.5 text-xs text-muted-foreground">{unavailableExplanation}</p>
            ) : null}
            {overflow ? <p role="alert" className="mt-1.5 text-xs text-destructive">{labels.contextLimit}</p> : null}
            {corrected ? (
                <motion.div
                    initial={reduceMotion ? { opacity: 0 } : { opacity: 0, transform: 'translateY(-0.125rem)' }}
                    animate={{ opacity: 1, transform: 'translateY(0rem)' }}
                    transition={reduceMotion ? instant : { duration: durationMicro, ease: easeOut }}
                    className="mt-1.5"
                >
                    <Button type="button" variant="ghost" size="inline" onClick={onReset}>
                        <ArrowUturnLeftIcon />
                        {labels.contextReset}
                    </Button>
                </motion.div>
            ) : null}
        </div>
    );
}

/**
 * The confirmation a broad request gets before it runs: what it will read, stated from the records
 * the request actually carries, with a way back to the context strip that decides them.
 *
 * Pressing Send holds the request here instead of running it, so the summary is announced rather
 * than merely rendered; the confirm and change controls stay outside that announcement, inside the
 * named group, so what a screen reader hears is the held scope and not a re-read of two buttons.
 */
export function AskConnexScopeNotice({
    preview,
    labels,
    onConfirm,
    onEdit,
}: {
    preview: AskConnexScopePreview;
    labels: AskConnexContextLabels;
    onConfirm: () => void;
    onEdit: () => void;
}) {
    const reduceMotion = useReducedMotion() ?? false;

    return (
        <motion.div
            role="group"
            aria-label={labels.scopeTitle}
            initial={reduceMotion ? { opacity: 0 } : { opacity: 0, transform: 'translateY(0.25rem)' }}
            animate={{ opacity: 1, transform: 'translateY(0rem)' }}
            transition={reduceMotion ? instant : { duration: durationMicro, ease: easeOut }}
            className="mb-2 border-y border-border py-2"
        >
            <p role="status" className="text-xs leading-relaxed text-foreground">
                {labels.scopeSummary(preview)}
            </p>
            <div className="mt-2 flex flex-wrap items-center gap-2">
                <Button type="button" size="inline" onClick={onConfirm}>
                    {labels.scopeConfirm}
                </Button>
                <Button type="button" variant="ghost" size="inline" onClick={onEdit}>
                    {labels.scopeEdit}
                </Button>
            </div>
        </motion.div>
    );
}
