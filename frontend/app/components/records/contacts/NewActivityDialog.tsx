'use client';

import { useEffect, useState } from 'react';
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
import { Select, SelectItem, SelectContent, SelectValue, SelectTrigger } from '@/components/ui/select';

import { toMysqlDateTime } from '@/app/lib/utils';

import { ApiError, addDealPerson, createActivity, getCompanyDeals } from '@/app/lib/api';
import { toastError, toastSuccess } from '@/app/lib/toast';
import { type Deal } from '@/app/lib/types';

const inputClass = 'w-full rounded-lg bg-neutral-100 px-3 py-2 text-sm text-black placeholder-neutral-500 outline-none ring-1 ring-black/5 transition focus:ring-2 focus:ring-brand';

const ACTIVITY_TYPES = ['Call', 'Email', 'Meeting', 'Note', 'Other'] as const;

export default function NewActivityDialog({
    contactId,
    contactName,
    companyId,
    currentUserId,
    open: openProp,
    onOpenChange,
}: {
    contactId: number;
    contactName: string;
    companyId?: number | null;
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
    const [dealId, setDealId] = useState('none');
    const [deals, setDeals] = useState<Deal[]>([]);
    const [loadingDeals, setLoadingDeals] = useState(false);
    const [submitting, setSubmitting] = useState(false);

    const reset = () => {
        setType(ACTIVITY_TYPES[0]);
        setSubject('');
        setNotes('');
        setTimestamp('');
        setDealId('none');
    };

    async function handleSubmit(e: React.SubmitEvent<HTMLFormElement>) {
        e.preventDefault();
        if (!subject.trim()) {
            toast.error(t('toastSubjectRequired'));
            return;
        }
        setSubmitting(true);
        const selectedDealId = dealId !== 'none' ? parseInt(dealId, 10) : undefined;
        try {
            await createActivity({
                type,
                subject: subject.trim(),
                notes: notes.trim() || undefined,
                personId: contactId,
                dealId: selectedDealId,
                createdById: currentUserId,
                timestamp: timestamp ? toMysqlDateTime(timestamp) : toMysqlDateTime(),
            });
            if (selectedDealId) {
                await addDealPerson(selectedDealId, contactId, 'Contact');
            }
            toastSuccess(t('toastActivityLogged'));
            setOpen(false);
            reset();
            router.refresh();
        } catch (err) {
            const message = err instanceof ApiError ? err.message : err instanceof Error ? err.message : t('toastFailedLog');
            toastError(message);
        } finally {
            setSubmitting(false);
        }
    }

    async function loadCompanyDeals() {
        if (!companyId) {
            setDeals([]);
            return;
        }
        setLoadingDeals(true);
        try {
            const companyDeals = await getCompanyDeals(companyId);
            setDeals(companyDeals);
        } catch (err) {
            const message = err instanceof ApiError ? err.message : err instanceof Error ? err.message : t('toastFailedLoadDeals');
            toastError(message);
            setDeals([]);
        } finally {
            setLoadingDeals(false);
        }
    }

    useEffect(() => {
        if (open) loadCompanyDeals();
    }, [open, companyId]);

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
                        <Select value={type} onValueChange={setType}>
                            <SelectTrigger id="activity-type" className={inputClass}>
                                <SelectValue />
                            </SelectTrigger>
                            <SelectContent>
                                {ACTIVITY_TYPES.map((value) => (
                                    <SelectItem key={value} value={value}>
                                        {t(`type${value}` as 'typeCall' | 'typeEmail' | 'typeMeeting' | 'typeNote' | 'typeOther')}
                                    </SelectItem>
                                ))}
                            </SelectContent>
                        </Select>
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
                        <Label htmlFor="activity-deal">{t('deal')}</Label>
                        <Select
                            value={dealId}
                            onValueChange={setDealId}
                            disabled={loadingDeals || !companyId}
                        >
                            <SelectTrigger id="activity-deal" className={inputClass}>
                                <SelectValue
                                    placeholder={
                                        !companyId
                                            ? t('assignCompanyToLinkDealsPlaceholder')
                                            : loadingDeals
                                                ? t('loadingDealsPlaceholder')
                                                : t('noDealPlaceholder')
                                    }
                                />
                            </SelectTrigger>
                            <SelectContent>
                                <SelectItem value="none">{t('noDeal')}</SelectItem>
                                {deals.map((deal) => (
                                    <SelectItem key={deal.id} value={deal.id.toString()}>
                                        {deal.name}
                                    </SelectItem>
                                ))}
                            </SelectContent>
                        </Select>
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
