'use client';

import { useEffect, useLayoutEffect, useMemo, useRef, useState, type PointerEvent, type WheelEvent } from 'react';
import { useRouter } from 'next/navigation';
import { useTranslations } from 'next-intl';
import { toastError, toastSuccess } from '@/app/lib/toast';
import { Loader2Icon } from 'lucide-react';
import { BriefcaseIcon, LockClosedIcon, UserIcon, UsersIcon, XMarkIcon } from '@heroicons/react/24/outline';

import { useUnsavedChangesGuard } from '@/app/hooks/useUnsavedChangesGuard';
import { useFormDraft } from '@/app/hooks/useFormDraft';
import { useWorkspace } from '@/app/hooks/useWorkspace';
import ConfirmDiscardDialog from '@/app/components/ConfirmDiscardDialog';

import {
    Drawer,
    DrawerContent,
    DrawerDescription,
    DrawerTitle,
} from '@/components/ui/drawer';
import {
    Combobox,
    ComboboxContent,
    ComboboxEmpty,
    ComboboxInput,
    ComboboxItem,
    ComboboxList,
} from '@/components/ui/combobox';
import { Button } from '@/components/ui/button';
import { Label } from '@/components/ui/label';
import { SegmentedToggle } from '@/app/components/filters';
import RichNoteEditor from './RichNoteEditor';
import { InputGroupAddon } from '@/components/ui/input-group';
import { DialogStatusCover, resolveDialogStatus } from '@/components/ui/dialog-status-cover';
import { cn } from '@/lib/utils';

import { ApiError, createNote, updateNote, isFieldError } from '@/app/lib/api';
import { isSubmitShortcut } from '@/app/lib/submitShortcut';
import { useFieldErrors } from '@/app/hooks/useFieldErrors';
import { DRAFT_VERSIONS } from '@/app/lib/formDrafts';
import { noteContentToVisibleText } from '@/app/lib/references';
import type { Contact, Deal, Note, NoteVisibility } from '@/app/lib/types';

const NOTE_DRAWER_PULL_THRESHOLD = 36;

function handleListWheel(event: WheelEvent<HTMLDivElement>) {
    const lineHeightPx = 16;
    const delta = event.deltaMode === 1 ? event.deltaY * lineHeightPx : event.deltaY;
    event.currentTarget.scrollTop += delta;
}

/**
 * Grab bar at the top of the note composer drawer. Drag up or tap to expand to full screen; drag
 * down or tap while expanded to return to the sheet; drag down from the sheet dismisses. Pointer
 * events are captured so the bar does not fight the drawer's swipe-to-dismiss.
 */
function NoteDrawerPullBar({
    expanded,
    expandLabel,
    collapseLabel,
    onExpand,
    onCollapse,
    onDismiss,
}: {
    expanded: boolean;
    expandLabel: string;
    collapseLabel: string;
    onExpand: () => void;
    onCollapse: () => void;
    onDismiss: () => void;
}) {
    const startY = useRef<number | null>(null);
    const handledByDragRef = useRef(false);

    const handlePointerDown = (event: PointerEvent<HTMLButtonElement>) => {
        event.stopPropagation();
        handledByDragRef.current = false;
        startY.current = event.clientY;
        event.currentTarget.setPointerCapture(event.pointerId);
    };

    const handlePointerUp = (event: PointerEvent<HTMLButtonElement>) => {
        if (startY.current == null) return;
        const dy = event.clientY - startY.current;
        startY.current = null;
        if (dy < -NOTE_DRAWER_PULL_THRESHOLD) {
            handledByDragRef.current = true;
            onExpand();
        } else if (dy > NOTE_DRAWER_PULL_THRESHOLD) {
            handledByDragRef.current = true;
            if (expanded) onCollapse();
            else onDismiss();
        }
    };

    const handleClick = () => {
        if (handledByDragRef.current) {
            handledByDragRef.current = false;
            return;
        }
        if (expanded) onCollapse();
        else onExpand();
    };

    return (
        <button
            type="button"
            aria-label={expanded ? collapseLabel : expandLabel}
            onPointerDown={handlePointerDown}
            onPointerUp={handlePointerUp}
            onClick={handleClick}
            className="flex min-h-11 shrink-0 touch-none cursor-grab justify-center rounded-md py-2.5 outline-none focus-visible:ring-2 focus-visible:ring-brand/60 active:cursor-grabbing"
        >
            <span aria-hidden className="h-1.5 w-12 rounded-full bg-border transition-colors" />
        </button>
    );
}

/** The serializable note-composer fields persisted and restored as one workspace-scoped draft. */
export type NoteDraftData = {
    content: string;
    title?: string;
    visibility?: NoteVisibility;
    personId: number | null;
    dealId: number | null;
};

type Props = {
    open: boolean;
    onOpenChange: (open: boolean) => void;
    note: Note | null;
    persons: Contact[];
    deals: Deal[];
    currentUserId: number;
    defaultPerson?: Contact | null;
    defaultDeal?: Deal | null;
    /** Prefills the note content, e.g. text carried over from the Quick Create panel. */
    defaultContent?: string;
    defaultTitle?: string;
    defaultVisibility?: NoteVisibility;
    initialDraftGeneration?: number;
    onDraftMounted?: () => void;
    requestInit?: RequestInit;
    onPersonQueryChange?: (query: string) => void;
    onDealQueryChange?: (query: string) => void;
    personOptionsLoading?: boolean;
    dealOptionsLoading?: boolean;
};

export default function NoteDialog(props: Props) {
    const { activeWorkspaceId } = useWorkspace();
    return (
        <ScopedNoteDialog
            key={`${props.currentUserId}:${activeWorkspaceId ?? 'none'}`}
            {...props}
            activeWorkspaceId={activeWorkspaceId}
        />
    );
}

function ScopedNoteDialog({
    open,
    onOpenChange,
    note,
    persons,
    deals,
    currentUserId,
    defaultPerson = null,
    defaultDeal = null,
    defaultContent = '',
    defaultTitle = '',
    defaultVisibility,
    initialDraftGeneration,
    onDraftMounted,
    requestInit,
    onPersonQueryChange,
    onDealQueryChange,
    personOptionsLoading = false,
    dealOptionsLoading = false,
    activeWorkspaceId,
}: Props & { activeWorkspaceId: number | null }) {
    const t = useTranslations('ActivityNotesDialog');
    const submittingRef = useRef(false);
    const [isDirty, setIsDirty] = useState(false);
    const guard = useUnsavedChangesGuard({ isDirty, onClose: () => onOpenChange(false) });
    const draft = useFormDraft<NoteDraftData>({
        keyParts: {
            userId: currentUserId,
            workspaceId: activeWorkspaceId,
            formType: 'note',
            scope: 'global',
        },
        version: DRAFT_VERSIONS.note,
        initialKeyGeneration: initialDraftGeneration,
    });

    useLayoutEffect(() => {
        onDraftMounted?.();
    }, [onDraftMounted]);
    const isCreate = note === null;

    const handleOpenChange = (next: boolean) => {
        if (!next && submittingRef.current) return;
        guard.onOpenChange(next);
    };

    const [prevOpen, setPrevOpen] = useState(open);
    const [openCount, setOpenCount] = useState(0);
    const [expanded, setExpanded] = useState(false);
    if (open !== prevOpen) {
        setPrevOpen(open);
        if (open) {
            setOpenCount((count) => count + 1);
            setExpanded(false);
        } else {
            setIsDirty(false);
        }
    }

    return (
        <>
            <Drawer open={open} onOpenChange={handleOpenChange} swipeDirection="down">
                <DrawerContent
                    showCloseButton={false}
                    className={cn(
                        'gap-0 p-0 pt-[env(safe-area-inset-top)] transition-[transform,height,max-height,width,max-width,border-radius] duration-300 ease-[cubic-bezier(0.32,0.72,0,1)] motion-reduce:transition-none',
                        expanded
                            ? 'h-[100dvh] max-h-[100dvh] w-full max-w-none rounded-none'
                            : 'h-[min(82dvh,48rem)] max-h-[min(82dvh,48rem)] w-full sm:max-w-6xl',
                    )}
                >
                    <DrawerTitle className="sr-only">{note ? t('titleEdit') : t('titleCreate')}</DrawerTitle>
                    <DrawerDescription className="sr-only">{t('description')}</DrawerDescription>
                    <NoteDrawerPullBar
                        expanded={expanded}
                        expandLabel={t('expand')}
                        collapseLabel={t('collapse')}
                        onExpand={() => setExpanded(true)}
                        onCollapse={() => setExpanded(false)}
                        onDismiss={guard.requestClose}
                    />
                    <Button
                        type="button"
                        variant="ghost"
                        size="icon-sm"
                        aria-label={t('close')}
                        className="absolute top-[max(0.75rem,env(safe-area-inset-top))] right-4 z-20"
                        onClick={guard.requestClose}
                    >
                        <XMarkIcon className="size-4" />
                    </Button>
                    <div className="min-h-0 flex-1 overflow-y-auto">
                        <NoteDialogForm
                            key={openCount}
                            note={note}
                            persons={persons}
                            deals={deals}
                            currentUserId={currentUserId}
                            defaultPerson={defaultPerson}
                            defaultDeal={defaultDeal}
                            defaultContent={defaultContent}
                            defaultTitle={defaultTitle}
                            defaultVisibility={defaultVisibility}
                            ownsInitialDraft={initialDraftGeneration !== undefined}
                            requestInit={requestInit}
                            onPersonQueryChange={onPersonQueryChange}
                            onDealQueryChange={onDealQueryChange}
                            personOptionsLoading={personOptionsLoading}
                            dealOptionsLoading={dealOptionsLoading}
                            onSubmittingChange={(value) => {
                                submittingRef.current = value;
                            }}
                            onDirtyChange={setIsDirty}
                            onPersistDraft={isCreate ? draft.persist : undefined}
                            onClearDraft={isCreate ? draft.clear : undefined}
                            onCancel={guard.requestClose}
                            onClose={() => onOpenChange(false)}
                            contentWidth={expanded ? 'wide' : 'default'}
                        />
                    </div>
                </DrawerContent>
            </Drawer>
            <ConfirmDiscardDialog
                open={guard.confirm.open}
                onKeepEditing={guard.confirm.onKeepEditing}
                onDiscard={() => {
                    if (isCreate) draft.clear();
                    guard.confirm.onDiscard();
                }}
            />
        </>
    );
}

type FormProps = {
    note: Note | null;
    persons: Contact[];
    deals: Deal[];
    currentUserId: number;
    defaultPerson: Contact | null;
    defaultDeal: Deal | null;
    defaultContent: string;
    defaultTitle?: string;
    defaultVisibility?: NoteVisibility;
    compact?: boolean;
    /** Widens the document column when the composer drawer is expanded to full screen. */
    contentWidth?: 'default' | 'wide';
    ownsInitialDraft?: boolean;
    requestInit?: RequestInit;
    onPersonQueryChange?: (query: string) => void;
    onDealQueryChange?: (query: string) => void;
    personOptionsLoading?: boolean;
    dealOptionsLoading?: boolean;
    onSubmittingChange: (value: boolean) => void;
    /** Reports whether the form holds unsaved edits, so a wrapper can guard against accidental discard. */
    onDirtyChange?: (dirty: boolean) => void;
    /** Persists the current note snapshot after this form owns a meaningful edit. */
    onPersistDraft?: (data: NoteDraftData) => void;
    /** Clears the note draft after confirmed creation, explicit discard, or deletion of owned content. */
    onClearDraft?: () => void;
    /** Invoked by the Cancel button — closes the dialog, or steps back to the selector in the morphing launcher. */
    onCancel: () => void;
    /** Invoked once the save succeeds (after the success beat), to dismiss the surface. */
    onClose: () => void;
};

function NoteLinkFields({
    persons,
    deals,
    selectedPerson,
    selectedDeal,
    onPersonSelect,
    onDealSelect,
    onPersonQueryChange,
    onDealQueryChange,
    personOptionsLoading,
    dealOptionsLoading,
    documentStyle = false,
}: {
    persons: Contact[];
    deals: Deal[];
    selectedPerson: Contact | null;
    selectedDeal: Deal | null;
    onPersonSelect: (id: number | null) => void;
    onDealSelect: (id: number | null) => void;
    onPersonQueryChange?: (query: string) => void;
    onDealQueryChange?: (query: string) => void;
    personOptionsLoading: boolean;
    dealOptionsLoading: boolean;
    documentStyle?: boolean;
}) {
    const t = useTranslations('ActivityNotesDialog');

    return (
        <div className={cn(documentStyle ? 'flex min-w-0 flex-1 flex-wrap items-center gap-2' : 'grid grid-cols-1 gap-3 md:grid-cols-2')}>
            <div className={cn(documentStyle ? 'min-w-48 flex-1' : 'grid gap-1.5')}>
                <Label htmlFor="note-person" className={documentStyle ? 'sr-only' : undefined}>{t('personLabel')}</Label>
                <Combobox
                    items={persons}
                    filter={onPersonQueryChange ? null : undefined}
                    itemToStringLabel={(person: Contact) => person.name}
                    value={selectedPerson}
                    onInputValueChange={onPersonQueryChange}
                    onValueChange={(person) => onPersonSelect(person?.id ?? null)}
                >
                    <ComboboxInput
                        id="note-person"
                        placeholder={t('personPlaceholder')}
                        className={cn(
                            'border-0 bg-muted shadow-none dark:bg-muted has-[[data-slot=input-group-control]:focus-visible]:ring-2 has-[[data-slot=input-group-control]:focus-visible]:ring-brand',
                            documentStyle ? 'h-11 rounded-md ring-0 sm:h-8' : 'rounded-lg ring-1 ring-border',
                        )}
                    >
                        <InputGroupAddon align="inline-start">
                            <UserIcon className="size-4 text-muted-foreground transition-colors group-focus-within/input-group:text-brand" />
                        </InputGroupAddon>
                    </ComboboxInput>
                    <ComboboxContent className="pointer-events-auto">
                        <ComboboxList onWheel={handleListWheel}>
                            <ComboboxEmpty>{personOptionsLoading ? t('searching') : t('noPersonFound')}</ComboboxEmpty>
                            {persons.map((person) => (
                                <ComboboxItem key={person.id} value={person}>{person.name}</ComboboxItem>
                            ))}
                        </ComboboxList>
                    </ComboboxContent>
                </Combobox>
            </div>

            <div className={cn(documentStyle ? 'min-w-48 flex-1' : 'grid gap-1.5')}>
                <Label htmlFor="note-deal" className={documentStyle ? 'sr-only' : undefined}>{t('dealLabel')}</Label>
                <Combobox
                    items={deals}
                    filter={onDealQueryChange ? null : undefined}
                    itemToStringLabel={(deal: Deal) => deal.name}
                    value={selectedDeal}
                    onInputValueChange={onDealQueryChange}
                    onValueChange={(deal) => onDealSelect(deal?.id ?? null)}
                >
                    <ComboboxInput
                        id="note-deal"
                        placeholder={t('dealPlaceholder')}
                        className={cn(
                            'border-0 bg-muted shadow-none dark:bg-muted has-[[data-slot=input-group-control]:focus-visible]:ring-2 has-[[data-slot=input-group-control]:focus-visible]:ring-brand',
                            documentStyle ? 'h-11 rounded-md ring-0 sm:h-8' : 'rounded-lg ring-1 ring-border',
                        )}
                    >
                        <InputGroupAddon align="inline-start">
                            <BriefcaseIcon className="size-4 text-muted-foreground transition-colors group-focus-within/input-group:text-brand" />
                        </InputGroupAddon>
                    </ComboboxInput>
                    <ComboboxContent className="pointer-events-auto">
                        <ComboboxList onWheel={handleListWheel}>
                            <ComboboxEmpty>{dealOptionsLoading ? t('searching') : t('noDealFound')}</ComboboxEmpty>
                            {deals.map((deal) => (
                                <ComboboxItem key={deal.id} value={deal}>{deal.name}</ComboboxItem>
                            ))}
                        </ComboboxList>
                    </ComboboxContent>
                </Combobox>
            </div>
        </div>
    );
}

/**
 * The note form body — free of any dialog/drawer shell so it can render inside the standalone
 * {@link NoteDialog} or embedded in the morphing Quick Create drawer.
 * It initializes state fresh from props on mount (callers remount it per open), with no reset effect.
 */
export function NoteDialogForm({
    note,
    persons,
    deals,
    currentUserId,
    defaultPerson,
    defaultDeal,
    defaultContent,
    defaultTitle = '',
    defaultVisibility,
    compact = false,
    contentWidth = 'default',
    ownsInitialDraft = false,
    requestInit,
    onPersonQueryChange,
    onDealQueryChange,
    personOptionsLoading = false,
    dealOptionsLoading = false,
    onSubmittingChange,
    onDirtyChange,
    onPersistDraft,
    onClearDraft,
    onCancel,
    onClose,
}: FormProps) {
    const router = useRouter();
    const t = useTranslations('ActivityNotesDialog');
    const isEdit = note !== null;

    const [title, setTitle] = useState(() => note?.title ?? defaultTitle);
    const [content, setContent] = useState(() => note?.content ?? defaultContent);
    const [selectedPersonId, setSelectedPersonId] = useState<number | null>(
        () => note?.person ?? defaultPerson?.id ?? null,
    );
    const [selectedDealId, setSelectedDealId] = useState<number | null>(
        () => note?.deal ?? defaultDeal?.id ?? null,
    );
    const [visibility, setVisibility] = useState<NoteVisibility>(
        () => note?.visibility ?? defaultVisibility ?? (defaultPerson || defaultDeal ? 'workspace' : 'private'),
    );
    const selectedPerson = useMemo(
        () => persons.find((person) => person.id === selectedPersonId)
            ?? (defaultPerson?.id === selectedPersonId ? defaultPerson : null),
        [defaultPerson, persons, selectedPersonId],
    );
    const selectedDeal = useMemo(
        () => deals.find((deal) => deal.id === selectedDealId)
            ?? (defaultDeal?.id === selectedDealId ? defaultDeal : null),
        [deals, defaultDeal, selectedDealId],
    );
    const [submitting, setSubmitting] = useState(false);
    const [succeeded, setSucceeded] = useState(false);
    const ownsDraftRef = useRef(ownsInitialDraft);
    const hasChangedRef = useRef(false);
    const { fieldErrors, reset: resetFieldErrors, clearError, captureFieldErrors } = useFieldErrors();

    const [initial] = useState(() => ({
        title,
        content,
        visibility,
        personId: selectedPersonId,
        dealId: selectedDealId,
    }));
    const formChanged =
        title !== initial.title ||
        content !== initial.content ||
        visibility !== initial.visibility ||
        selectedPersonId !== initial.personId ||
        selectedDealId !== initial.dealId;
    const dirty = !submitting && !succeeded && formChanged;
    useEffect(() => {
        onDirtyChange?.(dirty);
    }, [dirty, onDirtyChange]);

    useEffect(() => {
        if (isEdit) return;
        const meaningful = noteContentToVisibleText(content).length > 0;
        if (formChanged) hasChangedRef.current = true;
        if (!hasChangedRef.current || succeeded) return;
        if (meaningful) {
            ownsDraftRef.current = true;
        } else if (!ownsDraftRef.current) {
            return;
        }
        if (!meaningful) {
            onClearDraft?.();
            return;
        }
        onPersistDraft?.({
            title,
            content,
            visibility,
            personId: selectedPersonId,
            dealId: selectedDealId,
        });
    }, [content, formChanged, isEdit, onClearDraft, onPersistDraft, selectedDealId, selectedPersonId, succeeded, title, visibility]);

    const handleSubmit = async (e: React.FormEvent<HTMLFormElement>) => {
        e.preventDefault();
        resetFieldErrors();
        const trimmed = content.trim();
        const normalizedTitle = title.trim() || null;
        setSubmitting(true);
        onSubmittingChange(true);
        try {
            if (isEdit && note) {
                await updateNote(
                    note.id,
                    {
                        content: trimmed,
                        title: normalizedTitle,
                        visibility,
                        author: note.author,
                        person: selectedPersonId,
                        deal: selectedDealId,
                    },
                    requestInit,
                );
                if (requestInit?.signal?.aborted) return;
                toastSuccess(t('toastUpdated'));
            } else {
                await createNote(
                    {
                        content: trimmed,
                        title: normalizedTitle,
                        visibility,
                        author: currentUserId,
                        person: selectedPersonId,
                        deal: selectedDealId,
                    },
                    requestInit,
                );
                if (requestInit?.signal?.aborted) return;
                onClearDraft?.();
                toastSuccess(t('toastCreated'));
            }
            setSucceeded(true);
            router.refresh();
            setTimeout(() => onClose(), 900);
        } catch (err) {
            if (requestInit?.signal?.aborted) return;
            if (captureFieldErrors(err)) {
                if (isFieldError(err)) {
                    const firstKey = Object.keys(err.fieldErrors)[0];
                    if (firstKey) {
                        requestAnimationFrame(() => document.getElementById(`note-${firstKey}`)?.focus());
                    }
                }
                return;
            }
            const message =
                err instanceof ApiError ? err.message :
                err instanceof Error ? err.message :
                isEdit ? t('toastFailedSave') : t('toastFailedCreate');
            toastError(message);
        } finally {
            if (!requestInit?.signal?.aborted) {
                setSubmitting(false);
                onSubmittingChange(false);
            }
        }
    };

    const hasErrors = Object.keys(fieldErrors).length > 0;
    const status = resolveDialogStatus({ isLoading: submitting, hasErrors, isSuccess: succeeded });

    return (
        <>
            <DialogStatusCover status={status} />

            <div className={cn('px-6', compact ? 'pb-6' : 'pt-10 sm:px-10')}>
                {compact ? (
                    <div className="-mt-12 flex flex-col gap-2">
                        <h2 className="font-heading text-xl font-semibold leading-none tracking-tight">{isEdit ? t('titleEdit') : t('titleCreate')}</h2>
                        <p className="text-sm text-muted-foreground">{t('description')}</p>
                    </div>
                ) : null}

                <form
                    onSubmit={handleSubmit}
                    onKeyDown={(e) => {
                        if (isSubmitShortcut(e) && !submitting && !succeeded && content.trim()) {
                            e.preventDefault();
                            e.currentTarget.requestSubmit();
                        }
                    }}
                    className={cn(
                        'grid',
                        compact
                            ? 'gap-5'
                            : cn(
                                'mx-auto w-full gap-6 transition-[max-width] duration-300 ease-[cubic-bezier(0.32,0.72,0,1)] motion-reduce:transition-none',
                                contentWidth === 'wide' ? 'max-w-5xl' : 'max-w-3xl',
                            ),
                    )}
                >
                    {!compact ? (
                        <div>
                            <input
                                id="note-title"
                                value={title}
                                onChange={(event) => {
                                    setTitle(event.target.value);
                                    clearError('title');
                                }}
                                maxLength={255}
                                placeholder={t('titlePlaceholder')}
                                aria-label={t('titleLabel')}
                                aria-invalid={fieldErrors.title ? true : undefined}
                                aria-describedby={fieldErrors.title ? 'note-title-error' : undefined}
                                className="w-full rounded-sm border-0 bg-transparent p-0 text-4xl font-extrabold tracking-tight text-foreground outline-none placeholder:text-muted-foreground/50 focus-visible:ring-2 focus-visible:ring-brand/60"
                            />
                            {fieldErrors.title ? <p id="note-title-error" className="mt-2 text-sm text-destructive">{fieldErrors.title}</p> : null}
                        </div>
                    ) : null}

                    {!compact ? (
                        <div className="flex flex-wrap items-center gap-3 text-sm text-muted-foreground">
                            <NoteLinkFields
                                persons={persons}
                                deals={deals}
                                selectedPerson={selectedPerson}
                                selectedDeal={selectedDeal}
                                onPersonSelect={setSelectedPersonId}
                                onDealSelect={setSelectedDealId}
                                onPersonQueryChange={onPersonQueryChange}
                                onDealQueryChange={onDealQueryChange}
                                personOptionsLoading={personOptionsLoading}
                                dealOptionsLoading={dealOptionsLoading}
                                documentStyle
                            />
                            <SegmentedToggle<NoteVisibility>
                                value={visibility}
                                onChange={setVisibility}
                                ariaLabel={t('visibilityAria')}
                                options={[
                                    {
                                        value: 'private',
                                        label: t('visibilityPrivate'),
                                        icon: <LockClosedIcon className="size-3.5" />,
                                    },
                                    {
                                        value: 'workspace',
                                        label: t('visibilityWorkspace'),
                                        icon: <UsersIcon className="size-3.5" />,
                                    },
                                ]}
                            />
                        </div>
                    ) : null}

                    <div className="grid gap-1.5">
                        <Label htmlFor="note-content" className={compact ? undefined : 'sr-only'}>{t('contentLabel')}</Label>
                        <div
                            id="note-content"
                            tabIndex={-1}
                            aria-invalid={fieldErrors.content ? true : undefined}
                            aria-describedby={fieldErrors.content ? 'note-content-error' : undefined}
                            className={cn(
                                compact
                                    ? 'overflow-hidden rounded-xl border bg-background transition-[box-shadow,border-color] focus-within:ring-2 focus-within:ring-brand/60'
                                    : 'min-h-80 rounded-xl',
                                fieldErrors.content
                                    ? 'border-destructive ring-2 ring-destructive/50 focus-within:ring-destructive/50'
                                    : compact && 'border-border focus-within:border-brand',
                            )}
                        >
                            <RichNoteEditor
                                compact={compact}
                                value={content}
                                onChange={(next) => {
                                    setContent(next);
                                    clearError('content');
                                }}
                                excludeUserId={currentUserId}
                                autofocus
                            />
                        </div>
                        {fieldErrors.content && <p id="note-content-error" className="text-sm text-destructive">{fieldErrors.content}</p>}
                    </div>

                    {compact ? (
                        <NoteLinkFields
                            persons={persons}
                            deals={deals}
                            selectedPerson={selectedPerson}
                            selectedDeal={selectedDeal}
                            onPersonSelect={setSelectedPersonId}
                            onDealSelect={setSelectedDealId}
                            onPersonQueryChange={onPersonQueryChange}
                            onDealQueryChange={onDealQueryChange}
                            personOptionsLoading={personOptionsLoading}
                            dealOptionsLoading={dealOptionsLoading}
                        />
                    ) : null}

                    <div
                        className={cn(
                            'flex flex-col-reverse gap-2 sm:flex-row sm:justify-end',
                            !compact && 'sticky bottom-0 -mx-6 border-t border-border bg-popover px-6 pt-4 pb-[max(1rem,env(safe-area-inset-bottom))] sm:-mx-10 sm:px-10',
                        )}
                    >
                        <Button type="button" variant="outline" disabled={submitting} onClick={onCancel}>
                            {t('cancel')}
                        </Button>
                        <Button
                            type="submit"
                            variant="brand"
                            disabled={submitting || succeeded || !content.trim()}
                            className="min-w-24 shadow-sm transition hover:shadow-md"
                        >
                            {submitting ? (
                                <Loader2Icon className="size-4 animate-spin" />
                            ) : isEdit ? (
                                t('save')
                            ) : (
                                t('create')
                            )}
                        </Button>
                    </div>
                </form>
            </div>
        </>
    );
}
