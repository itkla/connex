'use client';

import { useEffect, useState, type WheelEvent } from 'react';
import { useRouter } from 'next/navigation';
import { useTranslations } from 'next-intl';
import { toastError, toastSuccess } from '@/app/lib/toast';
import { Loader2Icon } from 'lucide-react';
import { ChatBubbleLeftRightIcon, PencilSquareIcon, CalendarIcon, Bars3BottomLeftIcon, UserIcon, BriefcaseIcon } from '@heroicons/react/24/outline';

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
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
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

import { ApiError, createActivity, isFieldError } from '@/app/lib/api';
import { useFieldErrors } from '@/app/hooks/useFieldErrors';
import { toMysqlDateTime } from '@/app/lib/utils';
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
};

function nowLocalValue(): string {
    const d = new Date();
    const pad = (n: number) => String(n).padStart(2, '0');
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

export default function ActivityDialog({
    open,
    onOpenChange,
    persons,
    deals,
    currentUserId,
    defaultPerson = null,
    defaultDeal = null,
}: Props) {
    const router = useRouter();
    const t = useTranslations('ActivityCreateDialog');

    const [type, setType] = useState<ActivityType>(ACTIVITY_TYPES[0]);
    const [subject, setSubject] = useState('');
    const [notes, setNotes] = useState('');
    const [when, setWhen] = useState('');
    const [selectedPerson, setSelectedPerson] = useState<Contact | null>(null);
    const [selectedDeal, setSelectedDeal] = useState<Deal | null>(null);
    const [submitting, setSubmitting] = useState(false);
    const [succeeded, setSucceeded] = useState(false);
    const { fieldErrors, reset: resetFieldErrors, clearError, captureFieldErrors } = useFieldErrors();

    useEffect(() => {
        if (!open) return;
        // eslint-disable-next-line react-hooks/set-state-in-effect
        setType(ACTIVITY_TYPES[0]);
        setSubject('');
        setNotes('');
        setWhen(nowLocalValue());
        setSelectedPerson(defaultPerson ?? null);
        setSelectedDeal(defaultDeal ?? null);
        resetFieldErrors();
    }, [open, defaultPerson, defaultDeal, resetFieldErrors]);

    const handleListWheel = (e: WheelEvent<HTMLDivElement>) => {
        const lineHeightPx = 16;
        const delta = e.deltaMode === 1 ? e.deltaY * lineHeightPx : e.deltaY;
        e.currentTarget.scrollTop += delta;
    };

    const handleSubmit = async (e: React.FormEvent<HTMLFormElement>) => {
        e.preventDefault();
        resetFieldErrors();
        setSubmitting(true);
        try {
            await createActivity({
                type,
                subject: subject.trim(),
                notes: notes.trim() || undefined,
                createdById: currentUserId,
                timestamp: when ? toMysqlDateTime(when) : undefined,
                personId: selectedPerson?.id ?? undefined,
                dealId: selectedDeal?.id ?? undefined,
            });
            toastSuccess(t('toastCreated'));
            setSucceeded(true);
            router.refresh();
            setTimeout(() => onOpenChange(false), 900);
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
        }
    };

    const hasErrors = Object.keys(fieldErrors).length > 0;
    const status = resolveDialogStatus({ isLoading: submitting, hasErrors, isSuccess: succeeded });

    const handleOpenChange = (next: boolean) => {
        if (!next && submitting) return;
        if (!next) setSucceeded(false);
        onOpenChange(next);
    };

    return (
        <Dialog open={open} onOpenChange={handleOpenChange}>
            <DialogContent className="gap-0 overflow-hidden p-0 sm:max-w-lg">
                <DialogStatusCover status={status} />

                <div className="px-6 pb-6">
                    <DialogHeader className="ncd-rise -mt-12" style={{ animationDelay: '40ms' }}>
                        <div className="flex items-start gap-3">
                            <span className="flex size-9 shrink-0 items-center justify-center rounded-xl bg-brand-light text-brand-dark">
                                <ChatBubbleLeftRightIcon className="size-5" />
                            </span>
                            <div className="space-y-1">
                                <DialogTitle className="text-xl font-semibold tracking-tight">{t('titleCreate')}</DialogTitle>
                                <DialogDescription>{t('description')}</DialogDescription>
                            </div>
                        </div>
                    </DialogHeader>

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

                        <DialogFooter className="ncd-rise" style={{ animationDelay: '340ms' }}>
                            <DialogClose asChild>
                                <Button type="button" variant="outline" disabled={submitting}>
                                    {t('cancel')}
                                </Button>
                            </DialogClose>
                            <Button
                                type="submit"
                                disabled={submitting || succeeded}
                                className="min-w-24 bg-brand text-white shadow-sm transition hover:bg-brand-hover hover:shadow-md"
                            >
                                {submitting ? <Loader2Icon className="size-4 animate-spin" /> : t('create')}
                            </Button>
                        </DialogFooter>
                    </form>
                </div>
            </DialogContent>
        </Dialog>
    );
}