'use client';

import { useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import { toast } from 'sonner';
import { Loader2Icon } from 'lucide-react';
import { useTranslations } from 'next-intl';

import {
    ResponsiveDialog,
    ResponsiveDialogContent,
    ResponsiveDialogDescription,
    ResponsiveDialogFooter,
    ResponsiveDialogHeader,
    ResponsiveDialogTitle,
    ResponsiveDialogClose,
} from '@/components/ui/responsive-dialog';
import { Button } from '@/components/ui/button';
import { Label } from '@/components/ui/label';
import MentionEditor from '@/app/components/activity/notes/MentionEditor';
import { ENTITY_COMMANDS } from '@/app/components/activity/notes/commands/slashCommandRegistry';
import RecordSelect from '@/app/components/records/RecordSelect';

import { addDealPerson, ApiError, createActivity, getCompanyPeople } from '@/app/lib/api';
import { toastError, toastSuccess } from '@/app/lib/toast';
import { type Contact, type Deal } from '@/app/lib/types';
import { toMysqlDateTime } from '@/app/lib/utils';
import { Select, SelectContent, SelectValue, SelectTrigger, SelectItem } from '@/components/ui/select';
import { DialogStatusCover, resolveDialogStatus, fieldInputClass, fieldLeadIconClass } from '@/components/ui/dialog-status-cover';
import { TagIcon, UserIcon, CalendarIcon, PencilSquareIcon, Bars3BottomLeftIcon } from '@heroicons/react/24/outline';
import { cn } from '@/lib/utils';

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
    const [succeeded, setSucceeded] = useState(false);

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
            toastSuccess(t('activityLogged'));
            setSucceeded(true);
            reset();
            router.refresh();
            setTimeout(() => onOpenChange(false), 900);
        } catch (err) {
            const message = err instanceof ApiError ? err.message : err instanceof Error ? err.message : t('failedToLogActivity');
            toastError(message);
        } finally {
            setSubmitting(false);
        }
    }

    useEffect(() => {
        // get all contacts from the company associated with the deal
        getCompanyPeople(deal.company ?? 0).then(setContacts).catch(() => setContacts([]));
    }, []);

    const status = resolveDialogStatus({ isLoading: submitting, isSuccess: succeeded });

    const handleOpenChange = (next: boolean) => {
        if (!next && submitting) return;
        onOpenChange(next);
        if (!next) {
            reset();
            setSucceeded(false);
        }
    };

    return (
        <ResponsiveDialog open={open} onOpenChange={handleOpenChange}>
            <ResponsiveDialogContent className="gap-0 overflow-hidden p-0 sm:max-w-lg">
                <DialogStatusCover status={status} />

                <div className="px-6 pb-6">
                <ResponsiveDialogHeader className="ncd-rise -mt-12 mb-5" style={{ animationDelay: '40ms' }}>
                    <ResponsiveDialogTitle className="text-xl font-semibold tracking-tight">{t('title')}</ResponsiveDialogTitle>
                    <ResponsiveDialogDescription>
                        {t('description', { dealName })}
                    </ResponsiveDialogDescription>
                </ResponsiveDialogHeader>

                <form onSubmit={handleSubmit} className="grid gap-5">
                    <div className="ncd-rise grid gap-1.5" style={{ animationDelay: '90ms' }}>
                        <Label htmlFor="deal-activity-type">{t('type')}</Label>
                        <div className="group relative">
                            <TagIcon className={fieldLeadIconClass} />
                            <Select value={type} onValueChange={setType}>
                                <SelectTrigger id="deal-activity-type" className={cn(fieldInputClass, 'pl-9 pr-3')}>
                                    <SelectValue />
                                </SelectTrigger>
                                <SelectContent>
                                    {ACTIVITY_TYPES.map((value) => (
                                        <SelectItem key={value} value={value}>{t(`type${value}` as 'typeCall' | 'typeEmail' | 'typeMeeting' | 'typeNote' | 'typeOther')}</SelectItem>
                                    ))}
                                </SelectContent>
                            </Select>
                        </div>
                    </div>

                    <div className="ncd-rise grid gap-1.5" style={{ animationDelay: '140ms' }}>
                        <Label htmlFor="deal-activity-contact">{t('contact')}</Label>
                        <div className="group relative">
                            <UserIcon className={fieldLeadIconClass} />
                            <RecordSelect
                                id="deal-activity-contact"
                                value={contactId}
                                onValueChange={setContactId}
                                placeholder={t('selectContact')}
                                className={cn(fieldInputClass, 'pl-9 pr-3')}
                                options={contacts.map((contact) => ({
                                    id: contact.id,
                                    label: contact.name,
                                    imageUrl: contact.imageUrl,
                                }))}
                            />
                        </div>
                    </div>

                    <div className="ncd-rise grid gap-1.5" style={{ animationDelay: '190ms' }}>
                        <Label htmlFor="deal-activity-date-time">{t('dateTime')}</Label>
                        <div className="group relative">
                            <CalendarIcon className={fieldLeadIconClass} />
                            <input
                                id="deal-activity-date-time"
                                type="datetime-local"
                                value={timestamp}
                                onChange={(e) => setTimestamp(e.target.value)}
                                className={cn(fieldInputClass, 'pl-9 pr-3')}
                            />
                        </div>
                    </div>

                    <div className="ncd-rise grid gap-1.5" style={{ animationDelay: '240ms' }}>
                        <Label htmlFor="deal-activity-subject">{t('subject')}</Label>
                        <div className="group relative">
                            <PencilSquareIcon className={fieldLeadIconClass} />
                            <input
                                id="deal-activity-subject"
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
                        <Label htmlFor="deal-activity-notes">{t('notes')}</Label>
                        <div className="group relative">
                            <Bars3BottomLeftIcon className="pointer-events-none absolute left-3 top-3 size-4 text-muted-foreground transition-colors group-focus-within:text-brand" />
                            <MentionEditor
                                id="deal-activity-notes"
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
