'use client';

import { useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import { toast } from 'sonner';
import { Loader2Icon } from 'lucide-react';
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
import RecordSelect from '@/app/components/records/RecordSelect';

import { addDealPerson, ApiError, createTask, getCompanyPeople, getUsers } from '@/app/lib/api';
import { toastError, toastSuccess } from '@/app/lib/toast';
import { type Contact, type Deal, type User } from '@/app/lib/types';

const inputClass = 'w-full rounded-lg bg-neutral-100 px-3 py-2 text-sm text-black placeholder-neutral-500 outline-none ring-1 ring-black/5 transition focus:ring-2 focus:ring-brand';

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
            onOpenChange(false);
            reset();
            router.refresh();
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
                        <Label htmlFor="deal-task-assigned-to">{t('assignedTo')}</Label>
                        <RecordSelect
                            id="deal-task-assigned-to"
                            value={assignedToId.toString()}
                            onValueChange={(value) => setAssignedToId(parseInt(value))}
                            placeholder={t('selectUser')}
                            className={inputClass}
                            options={users.map((user) => ({
                                id: user.id,
                                label: user.displayName,
                                imageUrl: user.profilePictureUrl,
                            }))}
                        />
                    </div>

                    <div className="grid gap-2">
                        <Label htmlFor="deal-task-contact">{t('contact')}</Label>
                        <RecordSelect
                            id="deal-task-contact"
                            value={contactId}
                            onValueChange={setContactId}
                            placeholder={t('selectContact')}
                            className={inputClass}
                            options={contacts.map((contact) => ({
                                id: contact.id,
                                label: contact.name,
                                imageUrl: contact.imageUrl,
                            }))}
                        />
                    </div>

                    <div className="grid gap-2">
                        <Label htmlFor="deal-task-description">{t('descriptionLabel')}</Label>
                        <Textarea
                            id="deal-task-description"
                            value={description}
                            onChange={(e) => setDescription(e.target.value)}
                            placeholder={t('descriptionPlaceholder')}
                            required
                            autoFocus
                        />
                    </div>

                    <div className="grid gap-2">
                        <Label htmlFor="deal-task-due">{t('dueDate')}</Label>
                        <input
                            id="deal-task-due"
                            type="date"
                            value={dueDate}
                            onChange={(e) => setDueDate(e.target.value)}
                            className={inputClass}
                        />
                    </div>

                    <DialogFooter>
                        <DialogClose asChild>
                            <Button type="button" variant="outline" disabled={submitting}>
                                {t('cancel')}
                            </Button>
                        </DialogClose>
                        <Button type="submit" disabled={submitting} className="bg-brand text-white hover:bg-brand-dark">
                            {submitting ? <Loader2Icon className="size-4 animate-spin" /> : t('create')}
                        </Button>
                    </DialogFooter>
                </form>
            </DialogContent>
        </Dialog>
    );
}