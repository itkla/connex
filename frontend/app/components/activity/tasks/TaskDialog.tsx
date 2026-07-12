'use client';

import { useRef, useState, type WheelEvent } from 'react';
import { useRouter } from 'next/navigation';
import { useTranslations } from 'next-intl';
import { toastError, toastSuccess } from '@/app/lib/toast';
import { Loader2Icon } from 'lucide-react';
import { ClipboardDocumentCheckIcon, Bars3BottomLeftIcon, CalendarIcon, UserCircleIcon, UserIcon, BriefcaseIcon } from '@heroicons/react/24/outline';

import {
    Dialog,
    DialogContent,
    DialogHeader,
    DialogTitle,
    DialogDescription,
    DialogFooter,
    DialogClose,
} from '@/components/ui/dialog';
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
import { DialogStatusCover, resolveDialogStatus, fieldInputClass, fieldLeadIconClass } from '@/components/ui/dialog-status-cover';
import { InputGroupAddon } from '@/components/ui/input-group';
import { cn } from '@/lib/utils';

import { ApiError, createTask, isFieldError } from '@/app/lib/api';
import { useFieldErrors } from '@/app/hooks/useFieldErrors';
import type { Contact, Deal, User } from '@/app/lib/types';

type Props = {
    open: boolean;
    onOpenChange: (open: boolean) => void;
    persons: Contact[];
    deals: Deal[];
    users: User[];
    currentUserId: number;
    defaultPerson?: Contact | null;
    defaultDeal?: Deal | null;
    /** Prefills the due date (a `YYYY-MM-DD` value), e.g. the day tapped in the calendar. */
    defaultDueDate?: string;
    /** Prefills the description, e.g. text carried over from the Quick Create panel. */
    defaultDescription?: string;
};

export default function TaskDialog({
    open,
    onOpenChange,
    persons,
    deals,
    users,
    currentUserId,
    defaultPerson = null,
    defaultDeal = null,
    defaultDueDate = '',
    defaultDescription = '',
}: Props) {
    const submittingRef = useRef(false);

    const handleOpenChange = (next: boolean) => {
        if (!next && submittingRef.current) return;
        onOpenChange(next);
    };

    return (
        <Dialog open={open} onOpenChange={handleOpenChange}>
            <DialogContent className="gap-0 overflow-hidden p-0 sm:max-w-lg">
                <TaskDialogForm
                    persons={persons}
                    deals={deals}
                    users={users}
                    currentUserId={currentUserId}
                    defaultPerson={defaultPerson}
                    defaultDeal={defaultDeal}
                    defaultDueDate={defaultDueDate}
                    defaultDescription={defaultDescription}
                    onSubmittingChange={(value) => {
                        submittingRef.current = value;
                    }}
                    onClose={() => onOpenChange(false)}
                />
            </DialogContent>
        </Dialog>
    );
}

type TaskDialogFormProps = {
    persons: Contact[];
    deals: Deal[];
    users: User[];
    currentUserId: number;
    defaultPerson: Contact | null;
    defaultDeal: Deal | null;
    defaultDueDate: string;
    defaultDescription: string;
    onSubmittingChange: (submitting: boolean) => void;
    onClose: () => void;
};

/**
 * The task-create form. It lives inside {@code DialogContent} — which unmounts on
 * close — so its state initializes fresh from props on each open, with no reset effect.
 */
function TaskDialogForm({
    persons,
    deals,
    users,
    currentUserId,
    defaultPerson,
    defaultDeal,
    defaultDueDate,
    defaultDescription,
    onSubmittingChange,
    onClose,
}: TaskDialogFormProps) {
    const router = useRouter();
    const t = useTranslations('ActivityTasksDialog');

    const [description, setDescription] = useState(() => defaultDescription);
    const [dueDate, setDueDate] = useState(() => defaultDueDate);
    const [assignee, setAssignee] = useState<User | null>(() => users.find((u) => u.id === currentUserId) ?? null);
    const [selectedPerson, setSelectedPerson] = useState<Contact | null>(() => defaultPerson);
    const [selectedDeal, setSelectedDeal] = useState<Deal | null>(() => defaultDeal);
    const [submitting, setSubmitting] = useState(false);
    const [succeeded, setSucceeded] = useState(false);
    const { fieldErrors, reset: resetFieldErrors, clearError, captureFieldErrors } = useFieldErrors();

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
            await createTask({
                description: description.trim(),
                dueDate: dueDate || undefined,
                assignedToId,
                personId: selectedPerson?.id ?? undefined,
                dealId: selectedDeal?.id ?? undefined,
            });
            toastSuccess(t('toastCreated'));
            setSucceeded(true);
            router.refresh();
            setTimeout(() => onClose(), 900);
        } catch (err) {
            if (captureFieldErrors(err)) {
                if (isFieldError(err)) {
                    const firstKey = Object.keys(err.fieldErrors)[0];
                    if (firstKey) {
                        requestAnimationFrame(() => document.getElementById(`task-${firstKey}`)?.focus());
                    }
                }
                return;
            }
            const message =
                err instanceof ApiError
                    ? err.message
                    : err instanceof Error
                      ? err.message
                      : t('toastFailedCreate');
            toastError(message);
        } finally {
            setSubmitting(false);
            onSubmittingChange(false);
        }
    };

    const hasErrors = Object.keys(fieldErrors).length > 0;
    const status = resolveDialogStatus({ isLoading: submitting, hasErrors, isSuccess: succeeded });

    return (
        <>
            <DialogStatusCover status={status} />

            <div className="px-6 pb-6">
                <DialogHeader className="ncd-rise -mt-12" style={{ animationDelay: '40ms' }}>
                    <div className="flex items-start gap-3">
                        <span className="flex size-9 shrink-0 items-center justify-center rounded-xl bg-brand-light text-brand-dark">
                            <ClipboardDocumentCheckIcon className="size-5" />
                        </span>
                        <div className="space-y-1">
                            <DialogTitle className="text-xl font-semibold tracking-tight">{t('titleCreate')}</DialogTitle>
                            <DialogDescription>{t('description')}</DialogDescription>
                        </div>
                    </div>
                </DialogHeader>

                <form onSubmit={handleSubmit} className="grid gap-5">
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
                                className={cn(fieldInputClass, 'pl-9 pr-3 py-2')}
                            />
                        </div>
                        {fieldErrors.description && <p id="task-description-error" className="text-sm text-destructive">{fieldErrors.description}</p>}
                    </div>

                    <div className="ncd-rise grid grid-cols-1 gap-3 md:grid-cols-2" style={{ animationDelay: '140ms' }}>
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
                    </div>

                    <div className="ncd-rise grid grid-cols-1 gap-3 md:grid-cols-2" style={{ animationDelay: '190ms' }}>
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
                    </div>

                    <DialogFooter className="ncd-rise" style={{ animationDelay: '240ms' }}>
                        <DialogClose asChild>
                            <Button type="button" variant="outline" disabled={submitting}>
                                {t('cancel')}
                            </Button>
                        </DialogClose>
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
                    </DialogFooter>
                </form>
            </div>
        </>
    );
}
