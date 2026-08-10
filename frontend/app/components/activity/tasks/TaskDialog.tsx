'use client';

import { useEffect, useLayoutEffect, useRef, useState, type WheelEvent } from 'react';
import { useRouter } from 'next/navigation';
import { useTranslations } from 'next-intl';
import { toastError, toastSuccess } from '@/app/lib/toast';
import { Loader2Icon } from 'lucide-react';
import { ClipboardDocumentCheckIcon, Bars3BottomLeftIcon, CalendarIcon, UserCircleIcon, UserIcon, BriefcaseIcon } from '@heroicons/react/24/outline';

import { useUnsavedChangesGuard } from '@/app/hooks/useUnsavedChangesGuard';
import { useFormDraft } from '@/app/hooks/useFormDraft';
import { useWorkspace } from '@/app/hooks/useWorkspace';
import ConfirmDiscardDialog from '@/app/components/ConfirmDiscardDialog';

import {
    ResponsiveDialog,
    ResponsiveDialogContent,
    ResponsiveDialogTitle,
    ResponsiveDialogDescription,
} from '@/components/ui/responsive-dialog';
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
import MentionEditor from '@/app/components/activity/notes/MentionEditor';
import { ENTITY_COMMANDS } from '@/app/components/activity/notes/commands/slashCommandRegistry';
import { DialogStatusCover, resolveDialogStatus, fieldInputClass, fieldLeadIconClass } from '@/components/ui/dialog-status-cover';
import { InputGroupAddon } from '@/components/ui/input-group';
import { cn } from '@/lib/utils';

import { ApiError, createTask, isFieldError } from '@/app/lib/api';
import { isSubmitShortcut } from '@/app/lib/submitShortcut';
import { useFieldErrors } from '@/app/hooks/useFieldErrors';
import { DRAFT_VERSIONS } from '@/app/lib/formDrafts';
import type { Contact, CreateTaskPayload, Deal, User } from '@/app/lib/types';

/** The serializable task-composer fields persisted and restored as one workspace-scoped draft. */
export type TaskDraftData = {
    description: string;
    dueDate: string;
    assigneeId: number | null;
    personId: number | null;
    dealId: number | null;
};

type Props = {
    open: boolean;
    onOpenChange: (open: boolean) => void;
    persons: Contact[];
    deals: Deal[];
    users: User[];
    currentUserId: number;
    defaultAssignee?: User | null;
    defaultPerson?: Contact | null;
    defaultDeal?: Deal | null;
    /** Prefills the due date (a `YYYY-MM-DD` value), e.g. the day tapped in the calendar. */
    defaultDueDate?: string;
    /** Prefills the description, e.g. text carried over from the Quick Create panel. */
    defaultDescription?: string;
    initialDraftGeneration?: number;
    onDraftMounted?: () => void;
    requestInit?: RequestInit;
    createRequest?: (payload: CreateTaskPayload, init?: RequestInit) => Promise<unknown>;
    compact?: boolean;
    hideLinks?: boolean;
    failureMessage?: string;
    draftPersistence?: boolean;
};

export default function TaskDialog(props: Props) {
    const { activeWorkspaceId } = useWorkspace();
    return (
        <ScopedTaskDialog
            key={`${props.currentUserId}:${activeWorkspaceId ?? 'none'}`}
            {...props}
            activeWorkspaceId={activeWorkspaceId}
        />
    );
}

function ScopedTaskDialog({
    open,
    onOpenChange,
    persons,
    deals,
    users,
    currentUserId,
    defaultAssignee = null,
    defaultPerson = null,
    defaultDeal = null,
    defaultDueDate = '',
    defaultDescription = '',
    initialDraftGeneration,
    onDraftMounted,
    requestInit,
    createRequest,
    compact = false,
    hideLinks = false,
    failureMessage,
    draftPersistence = true,
    activeWorkspaceId,
}: Props & { activeWorkspaceId: number | null }) {
    const t = useTranslations('ActivityTasksDialog');
    const submittingRef = useRef(false);
    const [isDirty, setIsDirty] = useState(false);
    const guard = useUnsavedChangesGuard({ isDirty, onClose: () => onOpenChange(false) });
    const draft = useFormDraft<TaskDraftData>({
        keyParts: {
            userId: currentUserId,
            workspaceId: activeWorkspaceId,
            formType: 'task',
            scope: 'global',
        },
        version: DRAFT_VERSIONS.task,
        initialKeyGeneration: initialDraftGeneration,
    });

    useLayoutEffect(() => {
        onDraftMounted?.();
    }, [onDraftMounted]);

    const handleOpenChange = (next: boolean) => {
        if (!next && submittingRef.current) return;
        guard.onOpenChange(next);
    };

    const [prevOpen, setPrevOpen] = useState(open);
    const [openCount, setOpenCount] = useState(0);
    if (open !== prevOpen) {
        setPrevOpen(open);
        if (open) setOpenCount((count) => count + 1);
        else setIsDirty(false);
    }

    return (
        <>
            <ResponsiveDialog open={open} onOpenChange={handleOpenChange}>
                <ResponsiveDialogContent className="gap-0 overflow-hidden p-0 sm:max-w-lg">
                    <ResponsiveDialogTitle className="sr-only">{t('titleCreate')}</ResponsiveDialogTitle>
                    <ResponsiveDialogDescription className="sr-only">{t('description')}</ResponsiveDialogDescription>
                    <TaskDialogForm
                        key={openCount}
                        persons={persons}
                        deals={deals}
                        users={users}
                        currentUserId={currentUserId}
                        defaultAssignee={defaultAssignee}
                        defaultPerson={defaultPerson}
                        defaultDeal={defaultDeal}
                        defaultDueDate={defaultDueDate}
                        defaultDescription={defaultDescription}
                        ownsInitialDraft={draftPersistence && initialDraftGeneration !== undefined}
                        requestInit={requestInit}
                        createRequest={createRequest}
                        compact={compact}
                        hideLinks={hideLinks}
                        failureMessage={failureMessage}
                        onSubmittingChange={(value) => {
                            submittingRef.current = value;
                        }}
                        onDirtyChange={setIsDirty}
                        onPersistDraft={draftPersistence ? draft.persist : undefined}
                        onClearDraft={draftPersistence ? draft.clear : undefined}
                        onCancel={guard.requestClose}
                        onClose={() => onOpenChange(false)}
                    />
                </ResponsiveDialogContent>
            </ResponsiveDialog>
            <ConfirmDiscardDialog
                open={guard.confirm.open}
                onKeepEditing={guard.confirm.onKeepEditing}
                onDiscard={() => {
                    if (draftPersistence) draft.clear();
                    guard.confirm.onDiscard();
                }}
            />
        </>
    );
}

type TaskDialogFormProps = {
    persons: Contact[];
    deals: Deal[];
    users: User[];
    currentUserId: number;
    defaultAssignee?: User | null;
    defaultPerson: Contact | null;
    defaultDeal: Deal | null;
    defaultDueDate: string;
    defaultDescription: string;
    ownsInitialDraft?: boolean;
    requestInit?: RequestInit;
    createRequest?: (payload: CreateTaskPayload, init?: RequestInit) => Promise<unknown>;
    compact?: boolean;
    hideLinks?: boolean;
    failureMessage?: string;
    onSubmittingChange: (submitting: boolean) => void;
    /** Reports whether the form holds unsaved edits, so a wrapper can guard against accidental discard. */
    onDirtyChange?: (dirty: boolean) => void;
    /** Persists the current task snapshot after this form owns a meaningful edit. */
    onPersistDraft?: (data: TaskDraftData) => void;
    /** Clears the task draft after confirmed creation, explicit discard, or deletion of owned content. */
    onClearDraft?: () => void;
    /** Invoked by the Cancel button — closes the dialog, or steps back to the selector in the morphing launcher. */
    onCancel: () => void;
    /** Invoked once the create succeeds (after the success beat), to dismiss the surface. */
    onClose: () => void;
};

/**
 * The task-create form body — free of any dialog/drawer shell so it can render inside the standalone
 * {@link TaskDialog} (desktop dialog / mobile drawer) or embedded in the morphing Quick Create drawer.
 * It initializes state fresh from props on mount (callers remount it per open), with no reset effect.
 */
export function TaskDialogForm({
    persons,
    deals,
    users,
    currentUserId,
    defaultAssignee = null,
    defaultPerson,
    defaultDeal,
    defaultDueDate,
    defaultDescription,
    ownsInitialDraft = false,
    requestInit,
    createRequest = createTask,
    compact = false,
    hideLinks = false,
    failureMessage,
    onSubmittingChange,
    onDirtyChange,
    onPersistDraft,
    onClearDraft,
    onCancel,
    onClose,
}: TaskDialogFormProps) {
    const router = useRouter();
    const t = useTranslations('ActivityTasksDialog');

    const [description, setDescription] = useState(() => defaultDescription);
    const [dueDate, setDueDate] = useState(() => defaultDueDate);
    const [assignee, setAssignee] = useState<User | null>(() =>
        defaultAssignee ?? users.find((u) => u.id === currentUserId) ?? null,
    );
    const [selectedPerson, setSelectedPerson] = useState<Contact | null>(() => defaultPerson);
    const [selectedDeal, setSelectedDeal] = useState<Deal | null>(() => defaultDeal);
    const [submitting, setSubmitting] = useState(false);
    const [succeeded, setSucceeded] = useState(false);
    const ownsDraftRef = useRef(ownsInitialDraft);
    const hasChangedRef = useRef(false);
    const { fieldErrors, reset: resetFieldErrors, clearError, captureFieldErrors } = useFieldErrors();

    const [initial] = useState(() => ({
        description,
        dueDate,
        assigneeId: assignee?.id ?? null,
        personId: selectedPerson?.id ?? null,
        dealId: selectedDeal?.id ?? null,
    }));
    const formChanged =
        description !== initial.description ||
        dueDate !== initial.dueDate ||
        (assignee?.id ?? null) !== initial.assigneeId ||
        (selectedPerson?.id ?? null) !== initial.personId ||
        (selectedDeal?.id ?? null) !== initial.dealId;
    const dirty = !submitting && !succeeded && formChanged;
    useEffect(() => {
        onDirtyChange?.(dirty);
    }, [dirty, onDirtyChange]);

    useEffect(() => {
        const meaningful = description.trim().length > 0;
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
            description,
            dueDate,
            assigneeId: assignee?.id ?? currentUserId,
            personId: selectedPerson?.id ?? null,
            dealId: selectedDeal?.id ?? null,
        });
    }, [
        assignee,
        currentUserId,
        description,
        dueDate,
        formChanged,
        onClearDraft,
        onPersistDraft,
        selectedDeal,
        selectedPerson,
        succeeded,
    ]);

    const handleListWheel = (e: WheelEvent<HTMLDivElement>) => {
        const lineHeightPx = 16;
        const delta = e.deltaMode === 1 ? e.deltaY * lineHeightPx : e.deltaY;
        e.currentTarget.scrollTop += delta;
    };

    const handleSubmit = async (e: React.SubmitEvent<HTMLFormElement>) => {
        e.preventDefault();
        resetFieldErrors();
        const assignedToId = assignee?.id ?? currentUserId;
        setSubmitting(true);
        onSubmittingChange(true);
        try {
            await createRequest(
                {
                    description: description.trim(),
                    dueDate: dueDate || undefined,
                    assignedToId,
                    personId: selectedPerson?.id ?? undefined,
                    dealId: selectedDeal?.id ?? undefined,
                },
                requestInit,
            );
            if (requestInit?.signal?.aborted) return;
            onClearDraft?.();
            toastSuccess(t('toastCreated'));
            setSucceeded(true);
            router.refresh();
            setTimeout(() => onClose(), 900);
        } catch (err) {
            if (requestInit?.signal?.aborted) return;
            if (captureFieldErrors(err)) {
                if (isFieldError(err)) {
                    const firstKey = Object.keys(err.fieldErrors)[0];
                    if (firstKey) {
                        requestAnimationFrame(() => document.getElementById(`task-${firstKey}`)?.focus());
                    }
                }
                return;
            }
            const message = failureMessage ?? (
                err instanceof ApiError
                    ? err.message
                    : err instanceof Error
                      ? err.message
                      : t('toastFailedCreate'));
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

            <div className="px-6 pb-6">
                <div className="ncd-rise -mt-12 flex flex-col gap-2" style={{ animationDelay: '40ms' }}>
                    <div className="flex items-start gap-3">
                        <span className="flex size-9 shrink-0 items-center justify-center rounded-xl bg-brand-light text-brand-dark">
                            <ClipboardDocumentCheckIcon className="size-5" />
                        </span>
                        <div className="space-y-1">
                            <h2 className="font-heading text-xl font-semibold leading-none tracking-tight">{t('titleCreate')}</h2>
                            <p className="text-sm text-muted-foreground">{t('description')}</p>
                        </div>
                    </div>
                </div>

                <form
                    onSubmit={handleSubmit}
                    onKeyDown={(e) => {
                        if (isSubmitShortcut(e) && !submitting && !succeeded && description.trim()) {
                            e.preventDefault();
                            e.currentTarget.requestSubmit();
                        }
                    }}
                    className="grid gap-5"
                >
                    <div className="ncd-rise grid gap-1.5" style={{ animationDelay: '90ms' }}>
                        <Label htmlFor="task-description">{t('descriptionLabel')}</Label>
                        <div className="group relative">
                            <Bars3BottomLeftIcon className="pointer-events-none absolute left-3 top-3 size-4 text-muted-foreground transition-colors group-focus-within:text-brand" />
                            <MentionEditor
                                id="task-description"
                                value={description}
                                onChange={(next) => {
                                    setDescription(next);
                                    clearError('description');
                                }}
                                placeholder={t('descriptionPlaceholder')}
                                ariaInvalid={Boolean(fieldErrors.description)}
                                ariaDescribedby={fieldErrors.description ? 'task-description-error' : undefined}
                                autoFocus
                                commands={ENTITY_COMMANDS}
                                className={cn(fieldInputClass, 'pl-9 pr-3 py-2')}
                            />
                        </div>
                        {fieldErrors.description && <p id="task-description-error" className="text-sm text-destructive">{fieldErrors.description}</p>}
                    </div>

                    {!compact ? <div className="ncd-rise grid grid-cols-1 gap-3 md:grid-cols-2" style={{ animationDelay: '140ms' }}>
                        <div className="grid gap-1.5">
                            <Label htmlFor="task-due">{t('dueDateLabel')}</Label>
                            <div className="group relative">
                                <CalendarIcon className={fieldLeadIconClass} />
                                <input
                                    id="task-due"
                                    type="date"
                                    value={dueDate}
                                    onChange={(e) => setDueDate(e.target.value)}
                                    className={cn(fieldInputClass, 'pl-9 pr-3')}
                                />
                            </div>
                        </div>

                        <div className="grid gap-1.5">
                            <Label htmlFor="task-assignee">{t('assignedToLabel')}</Label>
                            <Combobox
                                items={users}
                                itemToStringLabel={(u: User) => u.displayName || u.username}
                                value={assignee}
                                onValueChange={(u) => setAssignee(u as User | null)}
                            >
                                <ComboboxInput
                                    id="task-assignee"
                                    placeholder={t('assignedToPlaceholder')}
                                    className="rounded-lg border-0 bg-muted shadow-none ring-1 ring-border dark:bg-muted has-[[data-slot=input-group-control]:focus-visible]:ring-2 has-[[data-slot=input-group-control]:focus-visible]:ring-brand"
                                >
                                    <InputGroupAddon align="inline-start">
                                        <UserCircleIcon className="size-4 text-muted-foreground transition-colors group-focus-within/input-group:text-brand" />
                                    </InputGroupAddon>
                                </ComboboxInput>
                                <ComboboxContent className="pointer-events-auto">
                                    <ComboboxList onWheel={handleListWheel}>
                                        <ComboboxEmpty>{t('noUserFound')}</ComboboxEmpty>
                                        {users.map((u) => (
                                            <ComboboxItem key={u.id} value={u}>
                                                {u.displayName || u.username}
                                            </ComboboxItem>
                                        ))}
                                    </ComboboxList>
                                </ComboboxContent>
                            </Combobox>
                        </div>
                    </div> : null}

                    {!compact && !hideLinks ? <div className="ncd-rise grid grid-cols-1 gap-3 md:grid-cols-2" style={{ animationDelay: '190ms' }}>
                        <div className="grid gap-1.5">
                            <Label htmlFor="task-person">{t('personLabel')}</Label>
                            <Combobox
                                items={persons}
                                itemToStringLabel={(p: Contact) => p.name}
                                value={selectedPerson}
                                onValueChange={(p) => setSelectedPerson(p as Contact | null)}
                            >
                                <ComboboxInput
                                    id="task-person"
                                    placeholder={t('personPlaceholder')}
                                    className="rounded-lg border-0 bg-muted shadow-none ring-1 ring-border dark:bg-muted has-[[data-slot=input-group-control]:focus-visible]:ring-2 has-[[data-slot=input-group-control]:focus-visible]:ring-brand"
                                >
                                    <InputGroupAddon align="inline-start">
                                        <UserIcon className="size-4 text-muted-foreground transition-colors group-focus-within/input-group:text-brand" />
                                    </InputGroupAddon>
                                </ComboboxInput>
                                <ComboboxContent className="pointer-events-auto">
                                    <ComboboxList onWheel={handleListWheel}>
                                        <ComboboxEmpty>{t('noPersonFound')}</ComboboxEmpty>
                                        {persons.map((p) => (
                                            <ComboboxItem key={p.id} value={p}>
                                                {p.name}
                                            </ComboboxItem>
                                        ))}
                                    </ComboboxList>
                                </ComboboxContent>
                            </Combobox>
                        </div>

                        <div className="grid gap-1.5">
                            <Label htmlFor="task-deal">{t('dealLabel')}</Label>
                            <Combobox
                                items={deals}
                                itemToStringLabel={(d: Deal) => d.name}
                                value={selectedDeal}
                                onValueChange={(d) => setSelectedDeal(d as Deal | null)}
                            >
                                <ComboboxInput
                                    id="task-deal"
                                    placeholder={t('dealPlaceholder')}
                                    className="rounded-lg border-0 bg-muted shadow-none ring-1 ring-border dark:bg-muted has-[[data-slot=input-group-control]:focus-visible]:ring-2 has-[[data-slot=input-group-control]:focus-visible]:ring-brand"
                                >
                                    <InputGroupAddon align="inline-start">
                                        <BriefcaseIcon className="size-4 text-muted-foreground transition-colors group-focus-within/input-group:text-brand" />
                                    </InputGroupAddon>
                                </ComboboxInput>
                                <ComboboxContent className="pointer-events-auto">
                                    <ComboboxList onWheel={handleListWheel}>
                                        <ComboboxEmpty>{t('noDealFound')}</ComboboxEmpty>
                                        {deals.map((d) => (
                                            <ComboboxItem key={d.id} value={d}>
                                                {d.name}
                                            </ComboboxItem>
                                        ))}
                                    </ComboboxList>
                                </ComboboxContent>
                            </Combobox>
                        </div>
                    </div> : null}

                    <div className="ncd-rise flex flex-col-reverse gap-2 sm:flex-row sm:justify-end" style={{ animationDelay: '240ms' }}>
                        <Button type="button" variant="outline" disabled={submitting} onClick={onCancel}>
                            {t('cancel')}
                        </Button>
                        <Button
                            type="submit"
                            variant="brand"
                            disabled={submitting || succeeded || !description.trim()}
                            className="min-w-24 shadow-sm transition hover:shadow-md"
                        >
                            {submitting ? (
                                <Loader2Icon className="size-4 animate-spin" />
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
