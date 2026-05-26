'use client';

import { useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import { toast } from 'sonner';
import { Loader2Icon } from 'lucide-react';
import { UserIcon } from '@heroicons/react/24/outline';

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

import { addDealPerson, ApiError, createTask, getCompanyPeople, getUsers } from '@/app/lib/api';
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
            toast.error('Description is required');
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
                    toast.warning('Task created, but failed to link contact to deal');
                });
            }
            toast.success('Task added', {
                style: { backgroundColor: 'var(--color-brand)', color: 'white' },
            });
            onOpenChange(false);
            reset();
            router.refresh();
        } catch (err) {
            const message = err instanceof ApiError ? err.message : err instanceof Error ? err.message : 'Failed to create task';
            toast.error(message, {
                style: { backgroundColor: 'var(--color-destructive)', color: 'white' },
            });
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
                    <DialogTitle>New task</DialogTitle>
                    <DialogDescription>
                        Add a task linked to {dealName}. Assigned to you by default.
                    </DialogDescription>
                </DialogHeader>

                <form onSubmit={handleSubmit} className="grid gap-4">
                    <div className="grid gap-2">
                        <Label htmlFor="deal-task-assigned-to">Assigned to</Label>
                        <Select value={assignedToId.toString()} onValueChange={(value) => setAssignedToId(parseInt(value))}>
                            <SelectTrigger className={inputClass}>
                                <SelectValue placeholder="Select user" />
                            </SelectTrigger>
                            <SelectContent>
                                {users.map((user) => (
                                    <SelectItem key={user.id} value={user.id.toString()}>
                                        <Avatar>
                                            <AvatarImage src={user.profilePictureUrl} />
                                            <AvatarFallback>
                                                <UserIcon className="size-4" />
                                            </AvatarFallback>
                                        </Avatar>
                                        {user.displayName}
                                    </SelectItem>
                                ))}
                            </SelectContent>
                        </Select>
                    </div>

                    <div className="grid gap-2">
                        <Label htmlFor="deal-task-contact">Contact</Label>
                        <Select value={contactId} onValueChange={(value) => setContactId(value)}>
                            <SelectTrigger className={inputClass}>
                                <SelectValue placeholder="Select contact" />
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
                        <Label htmlFor="deal-task-description">Description</Label>
                        <Textarea
                            id="deal-task-description"
                            value={description}
                            onChange={(e) => setDescription(e.target.value)}
                            placeholder="Send revised proposal…"
                            required
                            autoFocus
                        />
                    </div>

                    <div className="grid gap-2">
                        <Label htmlFor="deal-task-due">Due date</Label>
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
                                Cancel
                            </Button>
                        </DialogClose>
                        <Button type="submit" disabled={submitting} className="bg-brand text-white hover:bg-brand-dark">
                            {submitting ? <Loader2Icon className="size-4 animate-spin" /> : 'Create'}
                        </Button>
                    </DialogFooter>
                </form>
            </DialogContent>
        </Dialog>
    );
}