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
import { DialogStatusCover, resolveDialogStatus, fieldInputClass, fieldLeadIconClass } from '@/components/ui/dialog-status-cover';
import RecordSelect from '@/app/components/records/RecordSelect';
import { UserCircleIcon, UserIcon, Bars3BottomLeftIcon, CalendarIcon } from '@heroicons/react/24/outline';
import { cn } from '@/lib/utils';

import { addDealPerson, ApiError, createTask, getCompanyPeople, getUsers } from '@/app/lib/api';
import { toastError, toastSuccess } from '@/app/lib/toast';
import { type Contact, type Deal, type User } from '@/app/lib/types';

export default function NewDealTaskDialog({
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
    const t = useTranslations('DealsNewTaskDialog');
    const [description, setDescription] = useState('');
    const [dueDate, setDueDate] = useState('');
    const [submitting, setSubmitting] = useState(false);
    const [users, setUsers] = useState<User[]>([]);
    const [assignedToId, setAssignedToId] = useState(currentUserId);
    const [contactId, setContactId] = useState('');
    const [contacts, setContacts] = useState<Contact[]>([]);
    const [companyId, setCompanyId] = useState('');
    const [loadingContacts, setLoadingContacts] = useState(false);
    const [succeeded, setSucceeded] = useState(false);
    const reset = () => {
        setDescription('');
        setDueDate('');
        setAssignedToId(currentUserId);
        setContactId('');
        setCompanyId('');
    };

    // async function loadCompanyPeople() {
    //     const people = await getCompanyPeople(deal.company);
    //     setContacts(people);
    // }

    async function handleSubmit(e: React.SubmitEvent<HTMLFormElement>) {
        e.preventDefault();
        if (!description.trim()) {
            toast.error(t('descriptionRequired'));
            return;
        }
        setSubmitting(true);
        try {
            const personId = contactId ? parseInt(contactId) : undefined;
            await createTask({
                description: description.trim(),
                dueDate: dueDate || undefined,
                assignedToId,
                dealId,
                personId,
            });
            if (personId != null) {
                await addDealPerson(dealId, personId, '').catch(() => {
                    toast.warning(t('taskCreatedButFailedToLink'));
                });
            }
            toastSuccess(t('taskAdded'));
            setSucceeded(true);
            reset();
            router.refresh();
            setTimeout(() => onOpenChange(false), 900);
        } catch (err) {
            const message = err instanceof ApiError ? err.message : err instanceof Error ? err.message : t('failedToCreateTask');
            toastError(message);
        } finally {
            setSubmitting(false);
        }
    }

    useEffect(() => {
        getUsers().then(setUsers).catch(() => setUsers([]));

        // get all contacts from the company associated with the deal
        getCompanyPeople(deal.company ?? 0).then(setContacts).catch(() => setContacts([]));
    }, []);

    // console.log('contacts', contacts);

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
                        <Label htmlFor="deal-task-assigned-to">{t('assignedTo')}</Label>
                        <div className="group relative">
                            <UserCircleIcon className={fieldLeadIconClass} />
                            <RecordSelect
                                id="deal-task-assigned-to"
                                value={assignedToId.toString()}
                                onValueChange={(value) => setAssignedToId(parseInt(value))}
                                placeholder={t('selectUser')}
                                className={cn(fieldInputClass, 'pl-9 pr-3')}
                                options={users.map((user) => ({
                                    id: user.id,
                                    label: user.displayName,
                                    imageUrl: user.profilePictureUrl,
                                }))}
                            />
                        </div>
                    </div>

                    <div className="ncd-rise grid gap-1.5" style={{ animationDelay: '140ms' }}>
                        <Label htmlFor="deal-task-contact">{t('contact')}</Label>
                        <div className="group relative">
                            <UserIcon className={fieldLeadIconClass} />
                            <RecordSelect
                                id="deal-task-contact"
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
                        <Label htmlFor="deal-task-description">{t('descriptionLabel')}</Label>
                        <div className="group relative">
                            <Bars3BottomLeftIcon className="pointer-events-none absolute left-3 top-3 size-4 text-muted-foreground transition-colors group-focus-within:text-brand" />
                            <MentionEditor
                                id="deal-task-description"
                                value={description}
                                onChange={setDescription}
                                placeholder={t('descriptionPlaceholder')}
                                autoFocus
                                className={cn(fieldInputClass, 'min-h-24 pl-9 pr-3 py-2')}
                            />
                        </div>
                    </div>

                    <div className="ncd-rise grid gap-1.5" style={{ animationDelay: '240ms' }}>
                        <Label htmlFor="deal-task-due">{t('dueDate')}</Label>
                        <div className="group relative">
                            <CalendarIcon className={fieldLeadIconClass} />
                            <input
                                id="deal-task-due"
                                type="date"
                                value={dueDate}
                                onChange={(e) => setDueDate(e.target.value)}
                                className={cn(fieldInputClass, 'pl-9 pr-3')}
                            />
                        </div>
                    </div>

                    <ResponsiveDialogFooter className="ncd-rise mt-5" style={{ animationDelay: '290ms' }}>
                        <ResponsiveDialogClose asChild>
                            <Button type="button" variant="outline" disabled={submitting}>
                                {t('cancel')}
                            </Button>
                        </ResponsiveDialogClose>
                        <Button type="submit" disabled={submitting || succeeded} className="min-w-24 bg-brand text-white shadow-sm transition hover:bg-brand-hover hover:shadow-md">
                            {submitting ? <Loader2Icon className="size-4 animate-spin" /> : t('create')}
                        </Button>
                    </ResponsiveDialogFooter>
                </form>
                </div>
            </ResponsiveDialogContent>
        </ResponsiveDialog>
    );
}