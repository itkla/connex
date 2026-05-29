'use client';

import { useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import { toast } from 'sonner';
import { Loader2Icon } from 'lucide-react';
import { UserIcon } from '@heroicons/react/24/outline';
import { useTranslations } from 'next-intl';

import {
    Dialog,
    DialogContent,
    DialogDescription,
    DialogFooter,
    DialogHeader,
    DialogTitle,
    DialogClose,
} from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
import { Select, SelectItem, SelectContent, SelectValue, SelectTrigger } from '@/components/ui/select';
import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar';

import { addDealPerson, ApiError, createActivity, getCompanyPeople } from '@/app/lib/api';
import { type Contact, type Deal } from '@/app/lib/types';
import { toMysqlDateTime } from '@/app/lib/utils';

const inputClass = 'w-full rounded-lg bg-neutral-100 px-3 py-2 text-sm text-black placeholder-neutral-500 outline-none ring-1 ring-black/5 transition focus:ring-2 focus:ring-brand';

const ACTIVITY_TYPES = ['Call', 'Email', 'Meeting', 'Note', 'Other'] as const;

export default function NewDealActivityDialog({
    dealId,
    dealName,
    currentUserId,
    deal,
    open,
    onOpenChange,
}: {
    dealId: number;
    dealName: string;
    currentUserId: number;
    deal: Deal;
    open: boolean;
    onOpenChange: (open: boolean) => void;
}) {
    const router = useRouter();
    const t = useTranslations('DealsNewActivityDialog');
    const [type, setType] = useState<string>(ACTIVITY_TYPES[0]);
    const [subject, setSubject] = useState('');
    const [notes, setNotes] = useState('');
    const [timestamp, setTimestamp] = useState('');
    const [contactId, setContactId] = useState('');
    const [contacts, setContacts] = useState<Contact[]>([]);
    const [submitting, setSubmitting] = useState(false);

    const reset = () => {
        setType(ACTIVITY_TYPES[0]);
        setSubject('');
        setNotes('');
        setTimestamp('');
        setContactId('');
    };

    async function handleSubmit(e: React.SubmitEvent<HTMLFormElement>) {
        e.preventDefault();
        if (!subject.trim()) {
            toast.error(t('subjectRequired'));
            return;
        }
        setSubmitting(true);
        try {
            const personId = contactId ? parseInt(contactId) : undefined;
            await createActivity({
                type,
                subject: subject.trim(),
                notes: notes.trim() || undefined,
                dealId,
                personId,
                createdById: currentUserId,
                timestamp: timestamp ? toMysqlDateTime(timestamp) : toMysqlDateTime(),
            });
            if (personId != null) {
                await addDealPerson(dealId, personId, '').catch(() => {
                    toast.warning(t('activityLoggedButFailedToLink'));
                });
            }
            toast.success(t('activityLogged'), {
                style: { backgroundColor: 'var(--color-brand)', color: 'white' },
            });
            onOpenChange(false);
            reset();
            router.refresh();
        } catch (err) {
            const message = err instanceof ApiError ? err.message : err instanceof Error ? err.message : t('failedToLogActivity');
            toast.error(message, {
                style: { backgroundColor: 'var(--color-destructive)', color: 'white' },
            });
        } finally {
            setSubmitting(false);
        }
    }

    useEffect(() => {
        // get all contacts from the company associated with the deal
        getCompanyPeople(deal.company ?? 0).then(setContacts).catch(() => setContacts([]));
    }, []);

    return (
        <Dialog
            open={open}
            onOpenChange={(next) => {
                onOpenChange(next);
                if (!next) reset();
            }}
        >
            <DialogContent>
                <DialogHeader>
                    <DialogTitle>{t('title')}</DialogTitle>
                    <DialogDescription>
                        {t('description', { dealName })}
                    </DialogDescription>
                </DialogHeader>

                <form onSubmit={handleSubmit} className="grid gap-4">
                    <div className="grid gap-2">
                        <Label htmlFor="deal-activity-type">{t('type')}</Label>
                        <select
                            id="deal-activity-type"
                            value={type}
                            onChange={(e) => setType(e.target.value)}
                            className={inputClass}
                        >
                            {ACTIVITY_TYPES.map((t) => (
                                <option key={t} value={t}>
                                    {t}
                                </option>
                            ))}
                        </select>
                    </div>

                    <div className="grid gap-2">
                        <Label htmlFor="deal-activity-contact">{t('contact')}</Label>
                        <Select value={contactId} onValueChange={(value) => setContactId(value)}>
                            <SelectTrigger className={inputClass}>
                                <SelectValue placeholder={t('selectContact')} />
                            </SelectTrigger>
                            <SelectContent>
                                {contacts.map((contact) => (
                                    <SelectItem key={contact.id} value={contact.id.toString()}>
                                        <Avatar>
                                            <AvatarImage src={contact.imageUrl} />
                                            <AvatarFallback>
                                                <UserIcon className="size-4" />
                                            </AvatarFallback>
                                        </Avatar>
                                        {contact.name}
                                    </SelectItem>
                                ))}
                            </SelectContent>
                        </Select>
                    </div>

                    <div className="grid gap-2">
                        <Label htmlFor="deal-activity-date-time">{t('dateTime')}</Label>
                        <input
                            id="deal-activity-date-time"
                            type="datetime-local"
                            value={timestamp}
                            onChange={(e) => setTimestamp(e.target.value)}
                            className={inputClass}
                        />
                    </div>

                    <div className="grid gap-2">
                        <Label htmlFor="deal-activity-subject">{t('subject')}</Label>
                        <input
                            id="deal-activity-subject"
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
                        <Label htmlFor="deal-activity-notes">{t('notes')}</Label>
                        <Textarea
                            id="deal-activity-notes"
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