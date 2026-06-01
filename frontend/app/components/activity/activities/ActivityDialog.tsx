'use client';

import { useEffect, useState, type WheelEvent } from 'react';
import { useRouter } from 'next/navigation';
import { useTranslations } from 'next-intl';
import { toastError, toastSuccess } from '@/app/lib/toast';
import { Loader2Icon } from 'lucide-react';

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
import { toMysqlDateTime } from '@/app/lib/utils';
import type { Contact, Deal } from '@/app/lib/types';

const ACTIVITY_TYPES = ['Call', 'Email', 'Meeting', 'Note', 'Other'] as const;

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

    const [type, setType] = useState<string>(ACTIVITY_TYPES[0]);
    const [subject, setSubject] = useState('');
    const [notes, setNotes] = useState('');
    const [when, setWhen] = useState('');
    const [selectedPerson, setSelectedPerson] = useState<Contact | null>(null);
    const [selectedDeal, setSelectedDeal] = useState<Deal | null>(null);
    const [submitting, setSubmitting] = useState(false);

    useEffect(() => {
        if (!open) return;
        // eslint-disable-next-line react-hooks/set-state-in-effect
        setType(ACTIVITY_TYPES[0]);
        setSubject('');
        setNotes('');
        setWhen(nowLocalValue());
        setSelectedPerson(defaultPerson ?? null);
        setSelectedDeal(defaultDeal ?? null);
    }, [open, defaultPerson, defaultDeal]);

    const handleListWheel = (e: WheelEvent<HTMLDivElement>) => {
        const lineHeightPx = 16;
        const delta = e.deltaMode === 1 ? e.deltaY * lineHeightPx : e.deltaY;
        e.currentTarget.scrollTop += delta;
    };

    const handleSubmit = async (e: React.FormEvent<HTMLFormElement>) => {
        e.preventDefault();
        const trimmed = subject.trim();
        if (!trimmed) {
            toastError(t('toastSubjectRequired'));
            return;
        }
        setSubmitting(true);
        try {
            await createActivity({
                type,
                subject: trimmed,
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
            const message =
                err instanceof ApiError
                    ? err.message
                    : err instanceof Error
                      ? err.message
                      : t('toastFailedCreate');
            toastError(message);
        } finally {
            setSubmitting(false);
        }
    };

    return (
        <Dialog open={open} onOpenChange={onOpenChange}>
            <DialogContent>
                <DialogHeader>
                    <DialogTitle>{t('titleCreate')}</DialogTitle>
                    <DialogDescription>{t('description')}</DialogDescription>
                </DialogHeader>

                <form onSubmit={handleSubmit} className="grid gap-4">
                    <div className="grid grid-cols-1 gap-3 md:grid-cols-2">
                        <div className="grid gap-1.5">
                            <Label htmlFor="activity-type">{t('typeLabel')}</Label>
                            <Select value={type} onValueChange={setType}>
                                <SelectTrigger id="activity-type" className={inputClass}>
                                    <SelectValue />
                                </SelectTrigger>
                                <SelectContent>
                                    {ACTIVITY_TYPES.map((value) => (
                                        <SelectItem key={value} value={value}>
                                            {t(`type${value}` as 'typeCall')}
                                        </SelectItem>
                                    ))}
                                </SelectContent>
                            </Select>
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
                    </div>

                    <div className="grid gap-1.5">
                        <Label htmlFor="activity-subject">{t('subjectLabel')}</Label>
                        <input
                            id="activity-subject"
                            type="text"
                            value={subject}
                            onChange={(e) => setSubject(e.target.value)}
                            placeholder={t('subjectPlaceholder')}
                            className={inputClass}
                            autoFocus
                            required
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
                            disabled={submitting || !subject.trim()}
                            className="bg-brand text-white hover:bg-brand-dark"
                        >
                            {submitting ? <Loader2Icon className="size-4 animate-spin" /> : t('create')}
                        </Button>
                    </DialogFooter>
                </form>
            </DialogContent>
        </Dialog>
    );
}