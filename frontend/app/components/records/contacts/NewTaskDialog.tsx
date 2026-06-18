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
import { toastError, toastSuccess } from '@/app/lib/toast';
import { Deal, User } from '@/app/lib/types';
import { Select, SelectItem, SelectContent, SelectValue, SelectTrigger } from '@/components/ui/select';
import { DialogStatusCover, resolveDialogStatus, fieldInputClass, fieldLeadIconClass } from '@/components/ui/dialog-status-cover';
import RecordSelect from '@/app/components/records/RecordSelect';
import { UserCircleIcon, BriefcaseIcon, Bars3BottomLeftIcon, CalendarIcon } from '@heroicons/react/24/outline';
import { cn } from '@/lib/utils';

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
    const [succeeded, setSucceeded] = useState(false);
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
            toastSuccess(t('toastTaskAdded'));
            setSucceeded(true);
            reset();
            router.refresh();
            setTimeout(() => setOpen(false), 900);
        } catch (err) {
            const message = err instanceof ApiError ? err.message : err instanceof Error ? err.message : t('toastFailedCreate');
            toastError(message);
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
            toastError(message);
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
        <Dialog open={open} onOpenChange={handleOpenChange}>
            {controlled ? null : (
                <DialogTrigger asChild>
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
                </DialogTrigger>
            )}
            <DialogContent className="gap-0 overflow-hidden p-0 sm:max-w-lg">
                <DialogStatusCover status={status} />

                <div className="px-6 pb-6">
                <DialogHeader className="ncd-rise -mt-12 mb-5" style={{ animationDelay: '40ms' }}>
                    <DialogTitle className="text-xl font-semibold tracking-tight">{t('dialogTitle')}</DialogTitle>
                    <DialogDescription>
                        {t('description', { contactName })}
                    </DialogDescription>
                </DialogHeader>

                <form onSubmit={handleSubmit} className="grid gap-5">
                    <div className="ncd-rise grid gap-1.5" style={{ animationDelay: '90ms' }}>
                        <Label htmlFor="task-assigned-to">{t('assignedTo')}</Label>
                        <div className="group relative">
                            <UserCircleIcon className={fieldLeadIconClass} />
                            <RecordSelect
                                id="task-assigned-to"
                                value={assignedToId.toString()}
                                onValueChange={(value) => setAssignedToId(parseInt(value))}
                                placeholder={t('selectUserPlaceholder')}
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
                        <Label htmlFor="task-deal">{t('deal')}</Label>
                        <div className="group relative">
                            <BriefcaseIcon className={fieldLeadIconClass} />
                            <Select
                                value={dealId}
                                onValueChange={setDealId}
                                disabled={loadingDeals || !companyId}
                            >
                                <SelectTrigger id="task-deal" className={cn(fieldInputClass, 'pl-9 pr-3')}>
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
                    <div className="ncd-rise grid gap-1.5" style={{ animationDelay: '190ms' }}>
                        <Label htmlFor="task-description">{t('descriptionField')}</Label>
                        <div className="group relative">
                            <Bars3BottomLeftIcon className="pointer-events-none absolute left-3 top-3 size-4 text-muted-foreground transition-colors group-focus-within:text-brand" />
                            <Textarea
                                id="task-description"
                                value={description}
                                onChange={(e) => setDescription(e.target.value)}
                                placeholder={t('descriptionPlaceholder')}
                                required
                                autoFocus
                                className={cn(fieldInputClass, 'min-h-24 pl-9 pr-3')}
                            />
                        </div>
                    </div>

                    <div className="ncd-rise grid gap-1.5" style={{ animationDelay: '240ms' }}>
                        <Label htmlFor="task-due">{t('dueDate')}</Label>
                        <div className="group relative">
                            <CalendarIcon className={fieldLeadIconClass} />
                            <input
                                id="task-due"
                                type="date"
                                value={dueDate}
                                onChange={(e) => setDueDate(e.target.value)}
                                className={cn(fieldInputClass, 'pl-9 pr-3')}
                            />
                        </div>
                    </div>

                    <DialogFooter className="ncd-rise mt-5" style={{ animationDelay: '290ms' }}>
                        <DialogClose asChild>
                            <Button type="button" variant="outline" disabled={submitting}>
                                {t('cancel')}
                            </Button>
                        </DialogClose>
                        <Button type="submit" disabled={submitting || succeeded} className="min-w-24 bg-brand text-white shadow-sm transition hover:bg-brand-hover hover:shadow-md">
                            {submitting ? <Loader2Icon className="size-4 animate-spin" /> : t('create')}
                        </Button>
                    </DialogFooter>
                </form>
                </div>
            </DialogContent>
        </Dialog>
    );
}
