'use client';

import { useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import { useTranslations } from 'next-intl';
import { PlusIcon } from '@heroicons/react/24/solid';
import { Loader2Icon } from 'lucide-react';

import {
    ResponsiveDialog,
    ResponsiveDialogContent,
    ResponsiveDialogDescription,
    ResponsiveDialogFooter,
    ResponsiveDialogHeader,
    ResponsiveDialogTitle,
    ResponsiveDialogTrigger,
    ResponsiveDialogClose,
} from '@/components/ui/responsive-dialog';
import { Button } from '@/components/ui/button';
import { Label } from '@/components/ui/label';
import MentionEditor from '@/app/components/activity/notes/MentionEditor';
import { ENTITY_COMMANDS } from '@/app/components/activity/notes/commands/slashCommandRegistry';
import { Select, SelectItem, SelectContent, SelectValue, SelectTrigger } from '@/components/ui/select';
import { DialogStatusCover, resolveDialogStatus, fieldInputClass, fieldLeadIconClass } from '@/components/ui/dialog-status-cover';
import { TagIcon, CalendarIcon, BriefcaseIcon, PencilSquareIcon, Bars3BottomLeftIcon } from '@heroicons/react/24/outline';
import { cn } from '@/lib/utils';

import { toMysqlDateTime } from '@/app/lib/utils';

import { addDealPerson, createActivity, getCompanyDeals } from '@/app/lib/api';
import { useApiErrorToast } from '@/app/hooks/useApiErrorToast';
import { toastError, toastSuccess } from '@/app/lib/toast';
import { type Deal } from '@/app/lib/types';

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
    const showApiError = useApiErrorToast('ContactsNewActivityDialog');
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
    const [loadedCompanyId, setLoadedCompanyId] = useState<number | null>(null);
    const [submitting, setSubmitting] = useState(false);
    const [succeeded, setSucceeded] = useState(false);

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
            toastError(t('toastSubjectRequired'));
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
            setSucceeded(true);
            reset();
            router.refresh();
            setTimeout(() => setOpen(false), 900);
        } catch (err) {
            showApiError(err, 'toastFailedLog');
        } finally {
            setSubmitting(false);
        }
    }

    const loadingDeals = open && Boolean(companyId) && loadedCompanyId !== companyId;

    useEffect(() => {
        if (!open || !companyId) return;
        const cid = companyId;
        let active = true;
        getCompanyDeals(cid)
            .then((companyDeals) => {
                if (!active) return;
                setDeals(companyDeals);
                setLoadedCompanyId(cid);
            })
            .catch((err) => {
                if (!active) return;
                showApiError(err, 'toastFailedLoadDeals');
                setDeals([]);
                setLoadedCompanyId(cid);
            });
        return () => {
            active = false;
        };
    }, [companyId, open, showApiError, t]);

    const status = resolveDialogStatus({ isLoading: submitting, isSuccess: succeeded });

    const handleOpenChange = (next: boolean) => {
        if (!next && submitting) return;
        setOpen(next);
        if (!next) {
            reset();
            setSucceeded(false);
        }
    };

    return (
        <ResponsiveDialog open={open} onOpenChange={handleOpenChange}>
            {controlled ? null : (
                <ResponsiveDialogTrigger asChild>
                    <Button
                        type="button"
                        variant="ghost"
                        size="icon-sm"
                        title={t('triggerTitle')}
                        className="text-muted-foreground hover:text-foreground cursor-pointer"
                    >
                        <PlusIcon className="size-4" />
                        <span className="sr-only">{t('triggerSr')}</span>
                    </Button>
                </ResponsiveDialogTrigger>
            )}
            <ResponsiveDialogContent className="gap-0 overflow-hidden p-0 sm:max-w-lg">
                <DialogStatusCover status={status} />

                <div className="px-6 pb-6">
                <ResponsiveDialogHeader className="ncd-rise -mt-12 mb-5" style={{ animationDelay: '40ms' }}>
                    <ResponsiveDialogTitle className="text-xl font-semibold tracking-tight">{t('dialogTitle')}</ResponsiveDialogTitle>
                    <ResponsiveDialogDescription>
                        {t('description', { contactName })}
                    </ResponsiveDialogDescription>
                </ResponsiveDialogHeader>

                <form onSubmit={handleSubmit} className="grid gap-5">
                    <div className="ncd-rise grid gap-1.5" style={{ animationDelay: '90ms' }}>
                        <Label htmlFor="activity-type">{t('type')}</Label>
                        <div className="group relative">
                            <TagIcon className={fieldLeadIconClass} />
                            <Select value={type} onValueChange={setType}>
                                <SelectTrigger id="activity-type" className={cn(fieldInputClass, 'pl-9 pr-3')}>
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
                    </div>

                    <div className="ncd-rise grid gap-1.5" style={{ animationDelay: '140ms' }}>
                        <Label htmlFor="activity-date-time">{t('dateAndTime')}</Label>
                        <div className="group relative">
                            <CalendarIcon className={fieldLeadIconClass} />
                            <input
                                id="activity-date-time"
                                type="datetime-local"
                                value={timestamp}
                                onChange={(e) => setTimestamp(e.target.value)}
                                className={cn(fieldInputClass, 'pl-9 pr-3')}
                            />
                        </div>
                    </div>

                    <div className="ncd-rise grid gap-1.5" style={{ animationDelay: '190ms' }}>
                        <Label htmlFor="activity-deal">{t('deal')}</Label>
                        <div className="group relative">
                            <BriefcaseIcon className={fieldLeadIconClass} />
                            <Select
                                value={dealId}
                                onValueChange={setDealId}
                                disabled={loadingDeals || !companyId}
                            >
                                <SelectTrigger id="activity-deal" className={cn(fieldInputClass, 'pl-9 pr-3')}>
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
                    </div>

                    <div className="ncd-rise grid gap-1.5" style={{ animationDelay: '240ms' }}>
                        <Label htmlFor="activity-subject">{t('subject')}</Label>
                        <div className="group relative">
                            <PencilSquareIcon className={fieldLeadIconClass} />
                            <input
                                id="activity-subject"
                                type="text"
                                value={subject}
                                onChange={(e) => setSubject(e.target.value)}
                                className={cn(fieldInputClass, 'pl-9 pr-3')}
                                placeholder={t('subjectPlaceholder')}
                                required
                                autoFocus
                            />
                        </div>
                    </div>

                    <div className="ncd-rise grid gap-1.5" style={{ animationDelay: '290ms' }}>
                        <Label htmlFor="activity-notes">{t('notes')}</Label>
                        <div className="group relative">
                            <Bars3BottomLeftIcon className="pointer-events-none absolute left-3 top-3 size-4 text-muted-foreground transition-colors group-focus-within:text-brand" />
                            <MentionEditor
                                id="activity-notes"
                                value={notes}
                                onChange={setNotes}
                                placeholder={t('notesPlaceholder')}
                                commands={ENTITY_COMMANDS}
                                className={cn(fieldInputClass, 'min-h-24 pl-9 pr-3 py-2')}
                            />
                        </div>
                    </div>

                    <ResponsiveDialogFooter className="ncd-rise mt-5" style={{ animationDelay: '340ms' }}>
                        <ResponsiveDialogClose asChild>
                            <Button type="button" variant="outline" disabled={submitting}>
                                {t('cancel')}
                            </Button>
                        </ResponsiveDialogClose>
                        <Button type="submit" variant="brand" disabled={submitting || succeeded} className="min-w-24 shadow-sm transition hover:shadow-md">
                            {submitting ? <Loader2Icon className="size-4 animate-spin" /> : t('log')}
                        </Button>
                    </ResponsiveDialogFooter>
                </form>
                </div>
            </ResponsiveDialogContent>
        </ResponsiveDialog>
    );
}
