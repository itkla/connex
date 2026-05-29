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

import { ApiError, addDealPerson, createTask, getCompanyDeals, getUsers } from '@/app/lib/api';
import { Deal, User } from '@/app/lib/types';
import { Select, SelectItem, SelectContent, SelectValue, SelectTrigger } from '@/components/ui/select';
import RecordSelect from '@/app/components/records/RecordSelect';

const inputClass = 'w-full rounded-lg bg-neutral-100 px-3 py-2 text-sm text-black placeholder-neutral-500 outline-none ring-1 ring-black/5 transition focus:ring-2 focus:ring-brand';

export default function NewTaskDialog({
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
    const t = useTranslations('ContactsNewTaskDialog');
    const controlled = openProp !== undefined;
    const [internalOpen, setInternalOpen] = useState(false);
    const open = controlled ? openProp : internalOpen;
    const setOpen = (next: boolean) => {
        if (!controlled) setInternalOpen(next);
        onOpenChange?.(next);
    };
    const [description, setDescription] = useState('');
    const [dueDate, setDueDate] = useState('');
    const [submitting, setSubmitting] = useState(false);
    const [users, setUsers] = useState<User[]>([]);
    const [assignedToId, setAssignedToId] = useState(currentUserId);
    const [dealId, setDealId] = useState('none');
    const [deals, setDeals] = useState<Deal[]>([]);
    const [loadingDeals, setLoadingDeals] = useState(false);
    const reset = () => {
        setDescription('');
        setDueDate('');
        setAssignedToId(currentUserId);
        setDealId('none');
    };

    async function handleSubmit(e: React.SubmitEvent<HTMLFormElement>) {
        e.preventDefault();
        if (!description.trim()) {
            toast.error(t('toastDescriptionRequired'));
            return;
        }
        setSubmitting(true);
        const selectedDealId = dealId !== 'none' ? parseInt(dealId, 10) : undefined;
        try {
            await createTask({
                description: description.trim(),
                dueDate: dueDate || undefined,
                assignedToId,
                personId: contactId,
                dealId: selectedDealId,
            });
            if (selectedDealId) {
                await addDealPerson(selectedDealId, contactId, 'Contact');
            }
            toast.success(t('toastTaskAdded'), {
                style: { backgroundColor: 'var(--color-brand)', color: 'white' },
            });
            setOpen(false);
            reset();
            router.refresh();
        } catch (err) {
            const message = err instanceof ApiError ? err.message : err instanceof Error ? err.message : t('toastFailedCreate');
            toast.error(message, {
                style: { backgroundColor: 'var(--color-destructive)', color: 'white' },
            });
        } finally {
            setSubmitting(false);
        }
    }

    async function getOrgUsers() {
        const users = await getUsers();
        setUsers(users);
    }

    // load the deals for the company associated with the contact
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
            toast.error(message, {
                style: { backgroundColor: 'var(--color-destructive)', color: 'white' },
            });
            setDeals([]);
        } finally {
            setLoadingDeals(false);
        }
    }

    useEffect(() => {
        getOrgUsers();
    }, []);

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
                        <Label htmlFor="task-assigned-to">{t('assignedTo')}</Label>
                        <RecordSelect
                            id="task-assigned-to"
                            value={assignedToId.toString()}
                            onValueChange={(value) => setAssignedToId(parseInt(value))}
                            placeholder={t('selectUserPlaceholder')}
                            className={inputClass}
                            options={users.map((user) => ({
                                id: user.id,
                                label: user.displayName,
                                imageUrl: user.profilePictureUrl,
                            }))}
                        />
                    </div>
                    <div className="grid gap-2">
                        <Label htmlFor="task-deal">{t('deal')}</Label>
                        <Select
                            value={dealId}
                            onValueChange={setDealId}
                            disabled={loadingDeals || !companyId}
                        >
                            <SelectTrigger id="task-deal" className={inputClass}>
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
                        <Label htmlFor="task-description">{t('descriptionField')}</Label>
                        <Textarea
                            id="task-description"
                            value={description}
                            onChange={(e) => setDescription(e.target.value)}
                            placeholder={t('descriptionPlaceholder')}
                            required
                            autoFocus
                        />
                    </div>

                    <div className="grid gap-2">
                        <Label htmlFor="task-due">{t('dueDate')}</Label>
                        <input
                            id="task-due"
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
