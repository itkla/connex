'use client';

import { useRef, useState, type WheelEvent } from 'react';
import { useRouter } from 'next/navigation';
import { useTranslations } from 'next-intl';
import { toastError, toastSuccess } from '@/app/lib/toast';
import { Loader2Icon } from 'lucide-react';
import { ChatBubbleLeftRightIcon, PencilSquareIcon, CalendarIcon, Bars3BottomLeftIcon, UserIcon, BriefcaseIcon, CheckCircleIcon, ClockIcon } from '@heroicons/react/24/outline';

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
import { InputGroupAddon } from '@/components/ui/input-group';
import {
    DialogStatusCover,
    resolveDialogStatus,
    fieldInputClass,
    fieldErrorClass,
    fieldLeadIconClass,
} from '@/components/ui/dialog-status-cover';
import { cn } from '@/lib/utils';

import { ApiError, createActivity, createTask, isFieldError } from '@/app/lib/api';
import { useFieldErrors } from '@/app/hooks/useFieldErrors';
import { toMysqlDateTime } from '@/app/lib/utils';
import { addDays, startOfDay } from '@/app/lib/calendar';
import {
    ACTIVITY_TYPES,
    ActivityTypePicker,
    type ActivityType,
} from '@/app/components/activity/activities/activityTypes';
import type { Contact, Deal } from '@/app/lib/types';

type Props = {
    open: boolean;
    onOpenChange: (open: boolean) => void;
    persons: Contact[];
    deals: Deal[];
    currentUserId: number;
    defaultPerson?: Contact | null;
    defaultDeal?: Deal | null;
    /** Prefills the timestamp (a `datetime-local` value); defaults to now when omitted. */
    defaultTimestamp?: string;
    /** Prefills the activity type, e.g. carried over from the Quick Create panel. */
    defaultType?: ActivityType;
    /** Prefills the subject, e.g. carried over from the Quick Create panel. */
    defaultSubject?: string;
    /** Prefills the notes, e.g. carried over from the Quick Create panel. */
    defaultNotes?: string;
};

function nowLocalValue(): string {
    const d = new Date();
    const pad = (n: number) => String(n).padStart(2, '0');
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

/** Formats a Date as the `YYYY-MM-DD` local calendar value an `<input type="date">` expects. */
function toDateInputValue(d: Date): string {
    const pad = (n: number) => String(n).padStart(2, '0');
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

/** Relative-due-date presets for the follow-up task, in days from today. */
const FOLLOW_UP_PRESETS = [
    { key: 'today', days: 0 },
    { key: 'tomorrow', days: 1 },
    { key: 'inThreeDays', days: 3 },
    { key: 'nextWeek', days: 7 },
] as const;

export default function ActivityDialog({
    open,
    onOpenChange,
    persons,
    deals,
    currentUserId,
    defaultPerson = null,
    defaultDeal = null,
    defaultTimestamp,
    defaultType,
    defaultSubject = '',
    defaultNotes = '',
}: Props) {
    const t = useTranslations('ActivityCreateDialog');
    const submittingRef = useRef(false);

    const handleOpenChange = (next: boolean) => {
        if (!next && submittingRef.current) return;
        onOpenChange(next);
    };

    const [prevOpen, setPrevOpen] = useState(open);
    const [openCount, setOpenCount] = useState(0);
    if (open !== prevOpen) {
        setPrevOpen(open);
        if (open) setOpenCount((count) => count + 1);
    }

    return (
        <ResponsiveDialog open={open} onOpenChange={handleOpenChange}>
            <ResponsiveDialogContent className="gap-0 overflow-hidden p-0 sm:max-w-lg">
                <ResponsiveDialogTitle className="sr-only">{t('titleCreate')}</ResponsiveDialogTitle>
                <ResponsiveDialogDescription className="sr-only">{t('description')}</ResponsiveDialogDescription>
                <ActivityDialogForm
                    key={openCount}
                    persons={persons}
                    deals={deals}
                    currentUserId={currentUserId}
                    defaultPerson={defaultPerson}
                    defaultDeal={defaultDeal}
                    defaultTimestamp={defaultTimestamp}
                    defaultType={defaultType}
                    defaultSubject={defaultSubject}
                    defaultNotes={defaultNotes}
                    onSubmittingChange={(value) => {
                        submittingRef.current = value;
                    }}
                    onCancel={() => onOpenChange(false)}
                    onClose={() => onOpenChange(false)}
                />
            </ResponsiveDialogContent>
        </ResponsiveDialog>
    );
}

type ActivityDialogFormProps = {
    persons: Contact[];
    deals: Deal[];
    currentUserId: number;
    defaultPerson?: Contact | null;
    defaultDeal?: Deal | null;
    defaultTimestamp?: string;
    defaultType?: ActivityType;
    defaultSubject?: string;
    defaultNotes?: string;
    onSubmittingChange: (submitting: boolean) => void;
    /** Invoked by the Cancel button — closes the dialog, or steps back to the selector in the morphing launcher. */
    onCancel: () => void;
    /** Invoked once the create succeeds (after the success beat), to dismiss the surface. */
    onClose: () => void;
};

/**
 * The activity-log form body — free of any dialog/drawer shell so it can render inside the standalone
 * {@link ActivityDialog} (desktop dialog / mobile drawer) or embedded in the morphing Quick Create drawer.
 * It initializes state fresh from props on mount (callers remount it per open), with no reset effect.
 */
export function ActivityDialogForm({
    persons,
    deals,
    currentUserId,
    defaultPerson = null,
    defaultDeal = null,
    defaultTimestamp,
    defaultType,
    defaultSubject = '',
    defaultNotes = '',
    onSubmittingChange,
    onCancel,
    onClose,
}: ActivityDialogFormProps) {
    const router = useRouter();
    const t = useTranslations('ActivityCreateDialog');

    const [type, setType] = useState<ActivityType>(() => defaultType ?? ACTIVITY_TYPES[0]);
    const [subject, setSubject] = useState(() => defaultSubject);
    const [notes, setNotes] = useState(() => defaultNotes);
    const [when, setWhen] = useState(() => defaultTimestamp ?? nowLocalValue());
    const [selectedPerson, setSelectedPerson] = useState<Contact | null>(() => defaultPerson);
    const [selectedDeal, setSelectedDeal] = useState<Deal | null>(() => defaultDeal);
    const [submitting, setSubmitting] = useState(false);
    const [succeeded, setSucceeded] = useState(false);
    const [followUpEnabled, setFollowUpEnabled] = useState(false);
    const [followUpDescription, setFollowUpDescription] = useState('');
    const [followUpDueDate, setFollowUpDueDate] = useState('');
    const [followUpFailed, setFollowUpFailed] = useState(false);
    const activityCreatedRef = useRef(false);
    const { fieldErrors, reset: resetFieldErrors, clearError, captureFieldErrors } = useFieldErrors();

    const enableFollowUp = () => {
        setFollowUpEnabled(true);
        if (!followUpDescription) setFollowUpDescription(subject);
    };

    const handleListWheel = (e: WheelEvent<HTMLDivElement>) => {
        const lineHeightPx = 16;
        const delta = e.deltaMode === 1 ? e.deltaY * lineHeightPx : e.deltaY;
        e.currentTarget.scrollTop += delta;
    };

    const handleSubmit = async (e: React.FormEvent<HTMLFormElement>) => {
        e.preventDefault();
        resetFieldErrors();
        setSubmitting(true);
        onSubmittingChange(true);
        try {
            if (!activityCreatedRef.current) {
                await createActivity({
                    type,
                    subject: subject.trim(),
                    notes: notes.trim() || undefined,
                    createdById: currentUserId,
                    timestamp: when ? toMysqlDateTime(when) : undefined,
                    personId: selectedPerson?.id ?? undefined,
                    dealId: selectedDeal?.id ?? undefined,
                });
                activityCreatedRef.current = true;
            }
            if (followUpEnabled) {
                try {
                    await createTask({
                        description: (followUpDescription.trim() || subject.trim()),
                        dueDate: followUpDueDate || undefined,
                        assignedToId: currentUserId,
                        personId: selectedPerson?.id ?? undefined,
                        dealId: selectedDeal?.id ?? undefined,
                    });
                } catch (taskErr) {
                    setFollowUpFailed(true);
                    const message = taskErr instanceof ApiError ? taskErr.message : t('toastFollowUpFailed');
                    toastError(message);
                    router.refresh();
                    return;
                }
            }
            setFollowUpFailed(false);
            toastSuccess(followUpEnabled ? t('toastCreatedWithTask') : t('toastCreated'));
            setSucceeded(true);
            router.refresh();
            setTimeout(() => onClose(), 900);
        } catch (err) {
            if (captureFieldErrors(err)) {
                if (isFieldError(err)) {
                    const firstKey = Object.keys(err.fieldErrors)[0];
                    if (firstKey) {
                        requestAnimationFrame(() => document.getElementById(`activity-${firstKey}`)?.focus());
                    }
                }
                return;
            }
            const message =
                err instanceof ApiError ? err.message : err instanceof Error ? err.message : t('toastFailedCreate');
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
                <div className="ncd-rise -mt-12 flex flex-col gap-2" style={{ animationDelay: '40ms' }}>
                    <div className="flex items-start gap-3">
                        <span className="flex size-9 shrink-0 items-center justify-center rounded-xl bg-brand-light text-brand-dark">
                            <ChatBubbleLeftRightIcon className="size-5" />
                        </span>
                        <div className="space-y-1">
                            <h2 className="font-heading text-xl font-semibold leading-none tracking-tight">{t('titleCreate')}</h2>
                            <p className="text-sm text-muted-foreground">{t('description')}</p>
                        </div>
                    </div>
                </div>

                <form onSubmit={handleSubmit} className="grid gap-5">
                    <div className="ncd-rise grid gap-1.5" style={{ animationDelay: '90ms' }}>
                        <Label>{t('typeLabel')}</Label>
                        <ActivityTypePicker
                            value={type}
                            onChange={setType}
                            getLabel={(ty) => t(`type${ty}` as 'typeCall')}
                        />
                    </div>

                    <div className="ncd-rise grid gap-1.5" style={{ animationDelay: '140ms' }}>
                        <Label htmlFor="activity-subject">{t('subjectLabel')}</Label>
                        <div className="group relative">
                            <PencilSquareIcon className={fieldLeadIconClass} />
                            <input
                                id="activity-subject"
                                type="text"
                                value={subject}
                                onChange={(e) => {
                                    setSubject(e.target.value);
                                    clearError('subject');
                                }}
                                placeholder={t('subjectPlaceholder')}
                                className={cn(fieldInputClass, 'pl-9 pr-3', fieldErrors.subject && fieldErrorClass)}
                                aria-invalid={Boolean(fieldErrors.subject)}
                                aria-describedby={fieldErrors.subject ? 'activity-subject-error' : undefined}
                                autoFocus
                                required
                            />
                        </div>
                        {fieldErrors.subject && <p id="activity-subject-error" className="text-sm text-destructive">{fieldErrors.subject}</p>}
                    </div>

                    <div className="ncd-rise grid gap-1.5" style={{ animationDelay: '190ms' }}>
                        <Label htmlFor="activity-when">{t('timestampLabel')}</Label>
                        <div className="group relative">
                            <CalendarIcon className={fieldLeadIconClass} />
                            <input
                                id="activity-when"
                                type="datetime-local"
                                value={when}
                                onChange={(e) => setWhen(e.target.value)}
                                className={cn(fieldInputClass, 'pl-9 pr-3')}
                            />
                        </div>
                    </div>

                    <div className="ncd-rise grid gap-1.5" style={{ animationDelay: '240ms' }}>
                        <Label htmlFor="activity-notes">{t('notesLabel')}</Label>
                        <div className="group relative">
                            <Bars3BottomLeftIcon className="pointer-events-none absolute left-3 top-3 size-4 text-muted-foreground transition-colors group-focus-within:text-brand" />
                            <MentionEditor
                                id="activity-notes"
                                value={notes}
                                onChange={setNotes}
                                placeholder={t('notesPlaceholder')}
                                className={cn(fieldInputClass, 'min-h-24 pl-9 pr-3 py-2')}
                            />
                        </div>
                    </div>

                    <div className="ncd-rise grid grid-cols-1 gap-3 md:grid-cols-2" style={{ animationDelay: '290ms' }}>
                        <div className="grid gap-1.5">
                            <Label htmlFor="activity-person">{t('personLabel')}</Label>
                            <Combobox
                                items={persons}
                                itemToStringLabel={(p: Contact) => p.name}
                                value={selectedPerson}
                                onValueChange={(p) => setSelectedPerson(p as Contact | null)}
                            >
                                <ComboboxInput
                                    id="activity-person"
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
                            <Label htmlFor="activity-deal">{t('dealLabel')}</Label>
                            <Combobox
                                items={deals}
                                itemToStringLabel={(d: Deal) => d.name}
                                value={selectedDeal}
                                onValueChange={(d) => setSelectedDeal(d as Deal | null)}
                            >
                                <ComboboxInput
                                    id="activity-deal"
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

                    <div className="ncd-rise grid gap-2" style={{ animationDelay: '315ms' }}>
                        {!followUpEnabled ? (
                            <button
                                type="button"
                                onClick={enableFollowUp}
                                className="flex w-full items-center gap-2 rounded-lg border border-dashed border-border px-3 py-2.5 text-sm text-muted-foreground transition-colors hover:border-brand hover:text-brand focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand"
                            >
                                <CheckCircleIcon className="size-4" />
                                {t('followUpAdd')}
                            </button>
                        ) : (
                            <div className="grid gap-3 rounded-xl border border-border bg-muted/40 p-3">
                                <div className="flex items-center justify-between">
                                    <span className="flex items-center gap-2 text-sm font-medium text-foreground">
                                        <CheckCircleIcon className="size-4 text-brand" />
                                        {t('followUpTitle')}
                                    </span>
                                    <button
                                        type="button"
                                        onClick={() => {
                                            setFollowUpEnabled(false);
                                            setFollowUpFailed(false);
                                        }}
                                        className="text-xs text-muted-foreground transition-colors hover:text-foreground focus-visible:underline focus-visible:outline-none"
                                    >
                                        {t('followUpRemove')}
                                    </button>
                                </div>
                                {followUpFailed && (
                                    <p className="rounded-lg bg-destructive/10 px-2.5 py-1.5 text-xs text-destructive">{t('followUpFailedNote')}</p>
                                )}
                                <input
                                    type="text"
                                    value={followUpDescription}
                                    onChange={(e) => setFollowUpDescription(e.target.value)}
                                    placeholder={t('followUpDescriptionPlaceholder')}
                                    className={cn(fieldInputClass, 'px-3')}
                                    aria-label={t('followUpDescriptionLabel')}
                                />
                                <div className="flex flex-col gap-2">
                                    <span className="text-xs text-muted-foreground">{t('followUpDueLabel')}</span>
                                    <div className="flex flex-wrap items-center gap-1.5">
                                        {FOLLOW_UP_PRESETS.map((preset) => {
                                            const value = toDateInputValue(addDays(startOfDay(new Date()), preset.days));
                                            const active = followUpDueDate === value;
                                            return (
                                                <button
                                                    key={preset.key}
                                                    type="button"
                                                    onClick={() => setFollowUpDueDate(active ? '' : value)}
                                                    aria-pressed={active}
                                                    className={cn(
                                                        'rounded-full px-3 py-1 text-xs font-medium ring-1 ring-inset transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand',
                                                        active ? 'bg-brand-light text-brand-dark ring-brand-dark/20' : 'bg-card text-muted-foreground ring-border hover:text-foreground',
                                                    )}
                                                >
                                                    {t(`followUp_${preset.key}` as 'followUp_today')}
                                                </button>
                                            );
                                        })}
                                        <div className="group relative">
                                            <ClockIcon className={fieldLeadIconClass} />
                                            <input
                                                type="date"
                                                value={followUpDueDate}
                                                onChange={(e) => setFollowUpDueDate(e.target.value)}
                                                aria-label={t('followUpDueLabel')}
                                                className={cn(fieldInputClass, 'h-8 w-40 pl-8 pr-2 text-xs')}
                                            />
                                        </div>
                                    </div>
                                </div>
                            </div>
                        )}
                    </div>

                    <div className="ncd-rise flex flex-col-reverse gap-2 sm:flex-row sm:justify-end" style={{ animationDelay: '340ms' }}>
                        <Button type="button" variant="outline" disabled={submitting} onClick={onCancel}>
                            {t('cancel')}
                        </Button>
                        <Button
                            type="submit"
                            variant="brand"
                            disabled={submitting || succeeded}
                            className="min-w-24 shadow-sm transition hover:shadow-md"
                        >
                            {submitting ? (
                                <Loader2Icon className="size-4 animate-spin" />
                            ) : followUpFailed ? (
                                t('retryFollowUp')
                            ) : followUpEnabled ? (
                                t('createWithTask')
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
