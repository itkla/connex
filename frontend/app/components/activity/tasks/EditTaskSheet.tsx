'use client';

import { useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import { useTranslations } from 'next-intl';
import { toast } from 'sonner';
import { toastError, toastSuccess } from '@/app/lib/toast';
import { Loader2Icon } from 'lucide-react';
import { ClipboardDocumentCheckIcon } from '@heroicons/react/24/outline';

import { cn } from '@/lib/utils';

import {
    Sheet,
    SheetContent,
    SheetHeader,
    SheetTitle,
    SheetDescription,
    SheetFooter,
    SheetClose,
} from '@/components/ui/sheet';
import { Button } from '@/components/ui/button';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import RecordSelect from '@/app/components/records/RecordSelect';
import { Checkbox } from '@/components/ui/checkbox';

import { ApiError, getCompanyPeople, getUsers, updateTask } from '@/app/lib/api';
import { type Contact, type Deal, type Task, type UpdateTaskPayload, type User } from '@/app/lib/types';
import { calendarDateInputValue } from '@/app/lib/utils';

const inputClass = 'w-full rounded-lg bg-muted px-3 py-2 text-sm text-foreground placeholder:text-muted-foreground outline-none ring-1 ring-border transition focus:ring-2 focus:ring-brand';

type TaskDraft = {
    description: string;
    dueDate: string;
    assignedToId: number;
    personId: string;
    dealId: string;
    completed: boolean;
};

function toDraft(t: Task): TaskDraft {
    return {
        description: t.description ?? '',
        dueDate: calendarDateInputValue(t.dueDate),
        assignedToId: t.assignedToId ?? 0,
        personId: t.personId != null ? t.personId.toString() : 'none',
        dealId: t.dealId != null ? t.dealId.toString() : 'none',
        completed: t.completed ?? false,
    };
}

export default function EditTaskSheet({
    task,
    open,
    onOpenChange,
    companyId,
    deals,
}: {
    task: Task;
    open: boolean;
    onOpenChange: (open: boolean) => void;
    companyId?: number | null;
    deals: Deal[];
}) {
    const router = useRouter();
    const t = useTranslations('ActivityEditTaskSheet');
    const [draft, setDraft] = useState<TaskDraft>(() => toDraft(task));
    const [users, setUsers] = useState<User[]>([]);
    const [contacts, setContacts] = useState<Contact[]>([]);
    const [isSaving, setIsSaving] = useState(false);

    const handleOpenChange = (next: boolean) => {
        onOpenChange(next);
        if (!next) setDraft(toDraft(task));
    };

    // TODO: merge these two useEffects, or better yet get rid of them lol
    useEffect(() => {
        getUsers().then(setUsers).catch(() => setUsers([]));
    }, []);

    useEffect(() => {
        if (!companyId) {
            // eslint-disable-next-line react-hooks/set-state-in-effect
            setContacts([]);
            return;
        }
        getCompanyPeople(companyId).then(setContacts).catch(() => setContacts([]));
    }, [companyId]);

    const saveUpdates = async () => {
        if (!draft.description.trim()) {
            toast.error(t('descriptionRequired'));
            return;
        }
        setIsSaving(true);
        try {
            const payload: UpdateTaskPayload = {
                description: draft.description.trim(),
                dueDate: draft.dueDate || undefined,
                assignedToId: draft.assignedToId,
                personId: draft.personId !== 'none' ? parseInt(draft.personId) : undefined,
                dealId: draft.dealId !== 'none' ? parseInt(draft.dealId) : undefined,
                completed: draft.completed,
            };
            await updateTask(task.id, payload);
            toastSuccess(t('taskUpdated'));
            handleOpenChange(false);
            router.refresh();
        } catch (err) {
            const message = err instanceof ApiError ? err.message : err instanceof Error ? err.message : t('updateFailed');
            toastError(message);
        } finally {
            setIsSaving(false);
        }
    };

    return (
        <Sheet open={open} onOpenChange={handleOpenChange}>
            <SheetContent side="right" className="flex w-full flex-col sm:max-w-lg">
                <SheetHeader className="border-b">
                    <div className="flex items-start gap-3">
                        <span className="flex size-9 shrink-0 items-center justify-center rounded-xl bg-brand-light text-brand-dark">
                            <ClipboardDocumentCheckIcon className="size-5" />
                        </span>
                        <div className="space-y-1">
                            <SheetTitle>{t('title')}</SheetTitle>
                            <SheetDescription>{t('description')}</SheetDescription>
                        </div>
                    </div>
                </SheetHeader>

                <div className="flex-1 overflow-y-auto px-4 py-2">
                    <div className="grid gap-4 pt-6">
                        <div className="grid gap-1.5">
                            <Label htmlFor="task-description">{t('descriptionLabel')}</Label>
                            <Textarea
                                id="task-description"
                                value={draft.description}
                                onChange={(e) => setDraft((d) => ({ ...d, description: e.target.value }))}
                                required
                                autoFocus
                            />
                        </div>

                        <div className="grid gap-1.5">
                            <Label htmlFor="task-assigned-to">{t('assignedToLabel')}</Label>
                            <RecordSelect
                                id="task-assigned-to"
                                value={draft.assignedToId ? draft.assignedToId.toString() : ''}
                                onValueChange={(value) => setDraft((d) => ({ ...d, assignedToId: parseInt(value) }))}
                                placeholder={t('selectUserPlaceholder')}
                                className={inputClass}
                                options={users.map((user) => ({
                                    id: user.id,
                                    label: user.displayName,
                                    imageUrl: user.profilePictureUrl,
                                }))}
                            />
                        </div>

                        <div className="grid gap-1.5">
                            <Label htmlFor="task-contact">{t('contactLabel')}</Label>
                            <RecordSelect
                                id="task-contact"
                                value={draft.personId}
                                onValueChange={(value) => setDraft((d) => ({ ...d, personId: value }))}
                                disabled={!companyId}
                                placeholder={companyId ? t('selectContactPlaceholder') : t('noCompanyLinkedPlaceholder')}
                                className={inputClass}
                                noneOption={{ value: 'none', label: t('noContact') }}
                                options={contacts.map((contact) => ({
                                    id: contact.id,
                                    label: contact.name,
                                    imageUrl: contact.imageUrl,
                                }))}
                            />
                        </div>

                        <div className="grid gap-1.5">
                            <Label htmlFor="task-deal">{t('dealLabel')}</Label>
                            <Select
                                value={draft.dealId}
                                onValueChange={(value) => setDraft((d) => ({ ...d, dealId: value }))}
                            >
                                <SelectTrigger id="task-deal" className={inputClass}>
                                    <SelectValue placeholder={t('selectDealPlaceholder')} />
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

                        <div className="grid gap-1.5">
                            <Label htmlFor="task-due">{t('dueDateLabel')}</Label>
                            <input
                                id="task-due"
                                type="date"
                                value={draft.dueDate}
                                onChange={(e) => setDraft((d) => ({ ...d, dueDate: e.target.value }))}
                                className={inputClass}
                            />
                        </div>

                        <div
                            className={cn(
                                'flex items-center gap-2.5 rounded-lg px-3 py-2.5 ring-1 ring-inset transition-colors',
                                draft.completed
                                    ? 'bg-brand-light/50 ring-brand-dark/15'
                                    : 'bg-muted ring-border',
                            )}
                        >
                            <Checkbox
                                id="task-completed"
                                checked={draft.completed}
                                onCheckedChange={(checked) => setDraft((d) => ({ ...d, completed: checked === true }))}
                                className="size-[18px] rounded-full border-border data-[state=checked]:border-brand data-[state=checked]:bg-brand data-[state=checked]:text-white"
                            />
                            <Label
                                htmlFor="task-completed"
                                className={cn('cursor-pointer text-sm font-medium', draft.completed ? 'text-brand-dark' : 'text-foreground')}
                            >
                                {t('completedLabel')}
                            </Label>
                        </div>
                    </div>
                </div>

                <SheetFooter className="border-t">
                    <SheetClose asChild>
                        <Button variant="outline" disabled={isSaving}>{t('cancel')}</Button>
                    </SheetClose>
                    <Button
                        onClick={saveUpdates}
                        disabled={isSaving}
                        className="bg-brand text-white transition-transform hover:bg-brand-dark active:scale-[0.98]"
                    >
                        {isSaving ? <Loader2Icon className="size-4 animate-spin" /> : t('save')}
                    </Button>
                </SheetFooter>
            </SheetContent>
        </Sheet>
    );
}