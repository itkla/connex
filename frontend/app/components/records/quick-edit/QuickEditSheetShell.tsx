'use client';

import { type ReactNode, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { motion, useReducedMotion } from 'motion/react';
import { Loader2Icon } from 'lucide-react';
import { CameraIcon } from '@heroicons/react/24/outline';

import {
    Drawer,
    DrawerClose,
    DrawerContent,
    DrawerDescription,
    DrawerFooter,
    DrawerHeader,
    DrawerTitle,
} from '@/components/ui/drawer';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Label } from '@/components/ui/label';
import ConfirmDiscardDialog from '@/app/components/ConfirmDiscardDialog';
import { useUnsavedChangesGuard } from '@/app/hooks/useUnsavedChangesGuard';
import { isManagedImageFile, MANAGED_IMAGE_ACCEPT } from '@/app/lib/managed-image';
import ProtectedMediaImage from '@/app/components/ProtectedMediaImage';
import { cn } from '@/lib/utils';

export const EASE_OUT: [number, number, number, number] = [0.23, 1, 0.32, 1];

type QuickEditSheetShellProps = {
    open: boolean;
    onOpenChange: (open: boolean) => void;
    icon: ReactNode;
    title: string;
    description?: string;
    count: number;
    isSaving: boolean;
    interactionPending?: boolean;
    onSave: () => void;
    saveLabel: string;
    cancelLabel: string;
    /**
     * Serializable snapshot of the drafts this sheet is editing. The shell remembers it when the
     * sheet opens and treats any later difference as unsaved work, so a dismissal asks before
     * discarding instead of destroying the edits.
     */
    dirtySnapshot?: unknown;
    children: ReactNode;
};

function serializeSnapshot(snapshot: unknown): string | null {
    if (snapshot === undefined) return null;
    return JSON.stringify(snapshot) ?? null;
}

/**
 * Shared chrome for the record quick-edit sheets: a header that names what is being
 * edited, a scrollable body, and a sticky cancel/save footer. Keeps every quick-edit
 * surface (contacts, companies, deals, pipelines, notes) visually identical.
 *
 * Dismissing a sheet that has accumulated input asks before discarding it. Two signals feed
 * that: `dirtySnapshot` catches value changes made through controls that never emit an input
 * event (comboboxes, selects, outcome pickers), and typing or picking a file anywhere in the
 * body catches edits owned by embedded sections the caller cannot snapshot.
 */
export function QuickEditSheetShell({
    open,
    onOpenChange,
    icon,
    title,
    description,
    count,
    isSaving,
    interactionPending = false,
    onSave,
    saveLabel,
    cancelLabel,
    dirtySnapshot,
    children,
}: QuickEditSheetShellProps) {
    const snapshot = useMemo(() => serializeSnapshot(dirtySnapshot), [dirtySnapshot]);
    const [openedSnapshot, setOpenedSnapshot] = useState<string | null>(() => (open ? snapshot : null));
    const [touched, setTouched] = useState(false);
    const [prevOpen, setPrevOpen] = useState(open);
    if (open !== prevOpen) {
        setPrevOpen(open);
        setOpenedSnapshot(open ? snapshot : null);
        setTouched(false);
    }

    const dirty = !isSaving
        && (touched || (openedSnapshot !== null && snapshot !== null && snapshot !== openedSnapshot));
    const guard = useUnsavedChangesGuard({
        isDirty: dirty,
        onClose: () => onOpenChange(false),
    });

    const handleOpenChange = (next: boolean) => {
        if (!next && interactionPending) return;
        guard.onOpenChange(next);
    };

    return (
        <>
            <Drawer open={open} onOpenChange={handleOpenChange} swipeDirection="right">
                <DrawerContent className="flex w-full flex-col gap-0 sm:max-w-lg">
                    <DrawerHeader className="border-b pr-12">
                        <div className="flex items-center gap-3">
                            <span className="flex size-9 shrink-0 items-center justify-center rounded-xl bg-muted text-muted-foreground ring-1 ring-border [&_svg]:size-5">
                                {icon}
                            </span>
                            <DrawerTitle className="min-w-0 flex-1 truncate text-base">{title}</DrawerTitle>
                            {count > 1 ? (
                                <Badge variant="secondary" className="tabular-nums">
                                    {count}
                                </Badge>
                            ) : null}
                        </div>
                        {description ? <DrawerDescription>{description}</DrawerDescription> : null}
                    </DrawerHeader>

                    <div
                        className="flex flex-1 flex-col gap-4 overflow-y-auto p-4"
                        onInput={() => setTouched(true)}
                        onChange={() => setTouched(true)}
                    >
                        {children}
                    </div>

                    <DrawerFooter className="flex-row justify-end gap-2 border-t">
                        <DrawerClose render={<Button variant="outline" disabled={isSaving || interactionPending} />}>
                            {cancelLabel}
                        </DrawerClose>
                        <Button
                            onClick={onSave}
                            variant="brand"
                            disabled={isSaving || interactionPending}
                            className="min-w-24"
                        >
                            {isSaving ? <Loader2Icon className="size-4 animate-spin" /> : null}
                            {saveLabel}
                        </Button>
                    </DrawerFooter>
                </DrawerContent>
            </Drawer>
            <ConfirmDiscardDialog
                open={guard.confirm.open}
                onKeepEditing={guard.confirm.onKeepEditing}
                onDiscard={guard.confirm.onDiscard}
            />
        </>
    );
}

type QuickEditRecordCardProps = {
    index: number;
    total: number;
    media?: ReactNode;
    title: ReactNode;
    subtitle?: ReactNode;
    children: ReactNode;
};

/**
 * One editable record inside a quick-edit sheet. Renders an identity row (media, title,
 * and a position counter when several records are open) above the record's fields, and
 * staggers its entrance only when more than one record is being edited at once.
 */
export function QuickEditRecordCard({ index, total, media, title, subtitle, children }: QuickEditRecordCardProps) {
    const reduce = useReducedMotion() ?? false;
    const multiple = total > 1;
    const showIdentity = Boolean(media) || Boolean(title) || multiple;

    return (
        <motion.div
            initial={reduce || !multiple ? false : { opacity: 0, y: 8 }}
            animate={reduce || !multiple ? undefined : { opacity: 1, y: 0 }}
            transition={{ duration: 0.28, delay: index * 0.05, ease: EASE_OUT }}
            className={cn(multiple && 'rounded-xl bg-muted/40 p-4 ring-1 ring-border/70')}
        >
            {showIdentity ? (
                <div className="mb-4 flex items-center gap-3">
                    {media}
                    <div className="min-w-0 flex-1">
                        {title ? <div className="truncate text-sm font-medium text-foreground">{title}</div> : null}
                        {subtitle ? <div className="truncate text-xs text-muted-foreground">{subtitle}</div> : null}
                    </div>
                    {multiple ? (
                        <span className="shrink-0 text-xs tabular-nums text-muted-foreground">
                            {index + 1} / {total}
                        </span>
                    ) : null}
                </div>
            ) : null}
            <div className="grid gap-3">{children}</div>
        </motion.div>
    );
}

type QuickEditFieldProps = {
    label: ReactNode;
    htmlFor?: string;
    required?: boolean;
    /** Validation message for this field, shown under the control instead of as a toast. */
    error?: string;
    className?: string;
    children: ReactNode;
};

/** The id a field's inline validation message renders under, for the control's `aria-describedby`. */
export function quickEditErrorId(htmlFor: string): string {
    return `${htmlFor}-error`;
}

/** Label-over-control field wrapper that fixes the sheet's form rhythm, required marker, and error line. */
export function QuickEditField({ label, htmlFor, required, error, className, children }: QuickEditFieldProps) {
    return (
        <div className={cn('grid gap-1.5', className)}>
            <Label htmlFor={htmlFor}>
                {label}
                {required ? <span className="text-destructive">*</span> : null}
            </Label>
            {children}
            {error ? (
                <p
                    id={htmlFor ? quickEditErrorId(htmlFor) : undefined}
                    className="text-sm text-destructive"
                >
                    {error}
                </p>
            ) : null}
        </div>
    );
}

type QuickEditMediaUploadProps = {
    id: string;
    label: string;
    shape: 'round' | 'squircle';
    file: File | null;
    existingUrl: string | null;
    fallback: ReactNode;
    onSelect: (file: File | null) => void;
    onInvalidSelect?: () => void;
    onPendingChange?: (pending: boolean) => void;
    active?: boolean;
    disabled?: boolean;
};

/**
 * Square (logo) or round (avatar) media picker with a camera overlay revealed on hover
 * and keyboard focus. Previews the pending file (managing the object URL's lifecycle so it
 * is revoked, not leaked) and otherwise falls back to the existing image.
 */
export function QuickEditMediaUpload({
    id,
    label,
    shape,
    file,
    existingUrl,
    fallback,
    onSelect,
    onInvalidSelect,
    onPendingChange,
    active = true,
    disabled = false,
}: QuickEditMediaUploadProps) {
    const previewSrc = useMemo(() => (file ? URL.createObjectURL(file) : existingUrl), [file, existingUrl]);
    const [selectionPending, setSelectionPending] = useState(false);
    const [previousActive, setPreviousActive] = useState(active);
    const activeRef = useRef(active);
    const sequenceRef = useRef(0);
    const onSelectRef = useRef(onSelect);
    const onInvalidSelectRef = useRef(onInvalidSelect);
    const onPendingChangeRef = useRef(onPendingChange);

    useLayoutEffect(() => {
        onSelectRef.current = onSelect;
        onInvalidSelectRef.current = onInvalidSelect;
        onPendingChangeRef.current = onPendingChange;
    });

    useLayoutEffect(() => {
        activeRef.current = active;
        if (!active) {
            sequenceRef.current += 1;
            onPendingChangeRef.current?.(false);
        }
    }, [active]);

    if (active !== previousActive) {
        setPreviousActive(active);
        if (!active) setSelectionPending(false);
    }

    useEffect(() => () => {
        activeRef.current = false;
        sequenceRef.current += 1;
        onPendingChangeRef.current?.(false);
    }, []);

    useEffect(() => {
        if (!file || !previewSrc) return;
        return () => URL.revokeObjectURL(previewSrc);
    }, [file, previewSrc]);

    return (
        <label
            htmlFor={id}
            className={cn(
                'group relative flex size-16 shrink-0 cursor-pointer items-center justify-center overflow-hidden bg-muted ring-1 ring-border transition focus-within:ring-2 focus-within:ring-brand hover:ring-2 hover:ring-brand',
                shape === 'round' ? 'rounded-full' : 'rounded-2xl',
                (disabled || selectionPending) && 'pointer-events-none opacity-60',
            )}
        >
            <ProtectedMediaImage
                src={previewSrc}
                alt=""
                className="h-full w-full object-cover"
                fallback={fallback}
            />
            <div className="absolute inset-0 flex items-center justify-center bg-black/40 opacity-0 transition group-hover:opacity-100 group-focus-within:opacity-100">
                <CameraIcon className="size-5 text-white" />
            </div>
            <input
                id={id}
                type="file"
                accept={MANAGED_IMAGE_ACCEPT}
                aria-label={label}
                disabled={disabled || selectionPending}
                onChange={async (event) => {
                    const sequence = sequenceRef.current + 1;
                    sequenceRef.current = sequence;
                    const selectedFile = event.currentTarget.files?.[0];
                    event.currentTarget.value = '';
                    if (!selectedFile) return;
                    setSelectionPending(true);
                    onPendingChangeRef.current?.(true);
                    try {
                        const supported = await isManagedImageFile(selectedFile);
                        if (!activeRef.current || sequence !== sequenceRef.current) return;
                        if (supported) {
                            onSelectRef.current(selectedFile);
                        } else {
                            onInvalidSelectRef.current?.();
                        }
                    } finally {
                        if (activeRef.current && sequence === sequenceRef.current) {
                            setSelectionPending(false);
                            onPendingChangeRef.current?.(false);
                        }
                    }
                }}
                className="sr-only"
            />
        </label>
    );
}
