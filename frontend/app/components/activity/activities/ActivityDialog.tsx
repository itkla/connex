'use client';

import { useEffect, useState, type WheelEvent } from 'react';
import { useRouter } from 'next/navigation';
import { useTranslations } from 'next-intl';
import { toastError, toastSuccess } from '@/app/lib/toast';
import { Loader2Icon } from 'lucide-react';
import { ChatBubbleLeftRightIcon } from '@heroicons/react/24/outline';

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
import { Textarea } from '@/components/ui/textarea';

import { ApiError, createActivity } from '@/app/lib/api';
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

const inputClass =
    'w-full rounded-lg bg-neutral-100 px-3 py-2 text-sm text-black placeholder-neutral-500 outline-none ring-1 ring-black/5 transition focus:ring-2 focus:ring-brand';

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
            onOpenChange(false);
            router.refresh();
        } catch (err) {
            if (captureFieldErrors(err)) {
                return;
            }
            const message =
                err instanceof ApiError ? err.message : err instanceof Error ? err.message : t('toastFailedCreate');
            toastError(message);
        } finally {
            setSubmitting(false);
        }
    };

    return (
        <Dialog open={open} onOpenChange={onOpenChange}>
            <DialogContent>
                <DialogHeader>
                    <div className="flex items-start gap-3">
                        <span className="flex size-9 shrink-0 items-center justify-center rounded-xl bg-brand-light text-brand-dark">
                            <ChatBubbleLeftRightIcon className="size-5" />
                        </span>
                        <div className="space-y-1">
                            <DialogTitle>{t('titleCreate')}</DialogTitle>
                            <DialogDescription>{t('description')}</DialogDescription>
                        </div>
                    </div>
                </DialogHeader>

                <form onSubmit={handleSubmit} className="grid gap-4">
                    <div className="grid gap-1.5">
                        <Label>{t('typeLabel')}</Label>
                        <ActivityTypePicker
                            value={type}
                            onChange={setType}
                            getLabel={(ty) => t(`type${ty}` as 'typeCall')}
                        />
                    </div>

                    <div className="grid gap-1.5">
                        <Label htmlFor="activity-subject">{t('subjectLabel')}</Label>
                        <input
                            id="activity-subject"
                            type="text"
                            value={subject}
                            onChange={(e) => {
                                setSubject(e.target.value);
                                clearError('subject');
                            }}
                            placeholder={t('subjectPlaceholder')}
                            className={`${inputClass} ${fieldErrors.subject ? 'ring-2 ring-red-400 focus:ring-red-500' : ''}`}
                            aria-invalid={Boolean(fieldErrors.subject)}
                            autoFocus
                            required
                        />
                        {fieldErrors.subject && (
                            <p className="px-1 text-sm text-red-600">{fieldErrors.subject}</p>
                        )}
                    </div>

                    <div className="grid gap-1.5">
                        <Label htmlFor="activity-when">{t('timestampLabel')}</Label>
                        <input
                            id="activity-when"
                            type="datetime-local"
                            value={when}
                            onChange={(e) => setWhen(e.target.value)}
                            className={inputClass}
                        />
                    </div>

                    <div className="grid gap-1.5">
                        <Label htmlFor="activity-notes">{t('notesLabel')}</Label>
                        <Textarea
                            id="activity-notes"
                            value={notes}
                            onChange={(e) => setNotes(e.target.value)}
                            placeholder={t('notesPlaceholder')}
                            rows={3}
                        />
                    </div>

                    <div className="grid grid-cols-1 gap-3 md:grid-cols-2">
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
                                    className="ring-1 ring-black/5"
                                />
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
                                    className="ring-1 ring-black/5"
                                />
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

                    <DialogFooter>
                        <DialogClose asChild>
                            <Button type="button" variant="outline" disabled={submitting}>
                                {t('cancel')}
                            </Button>
                        </DialogClose>
                        <Button
                            type="submit"
                            disabled={submitting}
                            className="bg-brand text-white transition-transform hover:bg-brand-dark active:scale-[0.98]"
                        >
                            {submitting ? <Loader2Icon className="size-4 animate-spin" /> : t('create')}
                        </Button>
                    </DialogFooter>
                </form>
            </DialogContent>
        </Dialog>
    );
}