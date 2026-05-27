'use client';

import { useState } from 'react';
import { useRouter } from 'next/navigation';
import { useTranslations } from 'next-intl';
import { toast } from 'sonner';
import { PlusIcon } from '@heroicons/react/24/solid';
import { Loader2Icon } from 'lucide-react';

import {
    Dialog,
    DialogContent,
    DialogDescription,
    DialogFooter,
    DialogHeader,
    DialogTitle,
    DialogTrigger,
    DialogClose,
} from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';

import { toMysqlDateTime } from '@/app/lib/utils';

import { ApiError, createActivity } from '@/app/lib/api';

const inputClass = 'w-full rounded-lg bg-neutral-100 px-3 py-2 text-sm text-black placeholder-neutral-500 outline-none ring-1 ring-black/5 transition focus:ring-2 focus:ring-brand';

const ACTIVITY_TYPES = ['Call', 'Email', 'Meeting', 'Note', 'Other'] as const;

export default function NewActivityDialog({
    contactId,
    contactName,
    currentUserId,
    open: openProp,
    onOpenChange,
}: {
    contactId: number;
    contactName: string;
    currentUserId: number;
    open?: boolean;
    onOpenChange?: (open: boolean) => void;
}) {
    const router = useRouter();
    const t = useTranslations('ContactsNewActivityDialog');
    const controlled = openProp !== undefined;
    const [internalOpen, setInternalOpen] = useState(false);
    const open = controlled ? openProp : internalOpen;
    const setOpen = (next: boolean) => {
        if (!controlled) setInternalOpen(next);
        onOpenChange?.(next);
    };
    const [type, setType] = useState<string>(ACTIVITY_TYPES[0]);
    const [subject, setSubject] = useState('');
    const [notes, setNotes] = useState('');
    const [timestamp, setTimestamp] = useState('');
    const [submitting, setSubmitting] = useState(false);

    const reset = () => {
        setType(ACTIVITY_TYPES[0]);
        setSubject('');
        setNotes('');
    };

    async function handleSubmit(e: React.SubmitEvent<HTMLFormElement>) {
        e.preventDefault();
        if (!subject.trim()) {
            toast.error(t('toastSubjectRequired'));
            return;
        }
        setSubmitting(true);
        try {
            await createActivity({
                type,
                subject: subject.trim(),
                notes: notes.trim() || undefined,
                personId: contactId,
                createdById: currentUserId,
                timestamp: timestamp ? toMysqlDateTime(timestamp) : toMysqlDateTime(),
            });
            toast.success(t('toastActivityLogged'), {
                style: { backgroundColor: 'var(--color-brand)', color: 'white' },
            });
            setOpen(false);
            reset();
            router.refresh();
        } catch (err) {
            const message = err instanceof ApiError ? err.message : err instanceof Error ? err.message : t('toastFailedLog');
            toast.error(message, {
                style: { backgroundColor: 'var(--color-destructive)', color: 'white' },
            });
        } finally {
            setSubmitting(false);
        }
    }

    return (
        <Dialog
            open={open}
            onOpenChange={(next) => {
                setOpen(next);
                if (!next) reset();
            }}
        >
            {controlled ? null : (
                <DialogTrigger asChild>
                    <Button
                        type="button"
                        variant="ghost"
                        size="icon-sm"
                        title={t('triggerTitle')}
                        className="text-neutral-500 hover:text-black cursor-pointer"
                    >
                        <PlusIcon className="size-4" />
                        <span className="sr-only">{t('triggerSr')}</span>
                    </Button>
                </DialogTrigger>
            )}
            <DialogContent>
                <DialogHeader>
                    <DialogTitle>{t('dialogTitle')}</DialogTitle>
                    <DialogDescription>
                        {t('description', { contactName })}
                    </DialogDescription>
                </DialogHeader>

                <form onSubmit={handleSubmit} className="grid gap-4">
                    <div className="grid gap-2">
                        <Label htmlFor="activity-type">{t('type')}</Label>
                        <select
                            id="activity-type"
                            value={type}
                            onChange={(e) => setType(e.target.value)}
                            className={inputClass}
                        >
                            {ACTIVITY_TYPES.map((value) => (
                                <option key={value} value={value}>
                                    {t(`type${value}` as 'typeCall' | 'typeEmail' | 'typeMeeting' | 'typeNote' | 'typeOther')}
                                </option>
                            ))}
                        </select>
                    </div>

                    <div className="grid gap-2">
                        <Label htmlFor="activity-date-time">{t('dateAndTime')}</Label>
                        <input
                            id="activity-date-time"
                            type="datetime-local"
                            value={timestamp}
                            onChange={(e) => setTimestamp(e.target.value)}
                            className={inputClass}
                        >
                        </input>
                    </div>

                    <div className="grid gap-2">
                        <Label htmlFor="activity-subject">{t('subject')}</Label>
                        <input
                            id="activity-subject"
                            type="text"
                            value={subject}
                            onChange={(e) => setSubject(e.target.value)}
                            className={inputClass}
                            placeholder={t('subjectPlaceholder')}
                            required
                            autoFocus
                        />
                    </div>

                    <div className="grid gap-2">
                        <Label htmlFor="activity-notes">{t('notes')}</Label>
                        <Textarea
                            id="activity-notes"
                            value={notes}
                            onChange={(e) => setNotes(e.target.value)}
                            placeholder={t('notesPlaceholder')}
                        />
                    </div>

                    <DialogFooter>
                        <DialogClose asChild>
                            <Button type="button" variant="outline" disabled={submitting}>
                                {t('cancel')}
                            </Button>
                        </DialogClose>
                        <Button type="submit" disabled={submitting} className="bg-brand text-white hover:bg-brand-dark">
                            {submitting ? <Loader2Icon className="size-4 animate-spin" /> : t('log')}
                        </Button>
                    </DialogFooter>
                </form>
            </DialogContent>
        </Dialog>
    );
}
