'use client';

import { useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
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

import { ApiError, createTask, getUsers } from '@/app/lib/api';
import { User } from '@/app/lib/types';
import { Select, SelectItem, SelectContent, SelectValue, SelectTrigger } from '@/components/ui/select';
import { UserIcon } from '@heroicons/react/24/outline';
// import UserAvatar from '@/app/components/users/UserAvatar';
import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar';

const inputClass = 'w-full rounded-lg bg-neutral-100 px-3 py-2 text-sm text-black placeholder-neutral-500 outline-none ring-1 ring-black/5 transition focus:ring-2 focus:ring-brand';

export default function NewTaskDialog({
    contactId,
    contactName,
    currentUserId,
    open: openProp,
    onOpenChange,
}: {
    contactId: number;
    contactName: string;
    currentUserId: number;
    open?: boolean;
    onOpenChange?: (open: boolean) => void;
}) {
    const router = useRouter();
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

    const reset = () => {
        setDescription('');
        setDueDate('');
        setAssignedToId(currentUserId);
    };

    async function handleSubmit(e: React.FormEvent<HTMLFormElement>) {
        e.preventDefault();
        if (!description.trim()) {
            toast.error('Description is required');
            return;
        }
        setSubmitting(true);
        try {
            await createTask({
                description: description.trim(),
                dueDate: dueDate || undefined,
                assignedToId,
                personId: contactId,
            });
            toast.success('Task added', {
                style: { backgroundColor: 'var(--color-brand)', color: 'white' },
            });
            setOpen(false);
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

    async function getOrgUsers() {
        const users = await getUsers();
        setUsers(users);
    }

    useEffect(() => {
        getOrgUsers();
    }, []);

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
                        title="Add task"
                        className="text-neutral-500 hover:text-black cursor-pointer"
                    >
                        <PlusIcon className="size-4" />
                        <span className="sr-only">Add task</span>
                    </Button>
                </DialogTrigger>
            )}
            <DialogContent>
                <DialogHeader>
                    <DialogTitle>New task</DialogTitle>
                    <DialogDescription>
                        Add a task linked to {contactName}. Assigned to you by default.
                    </DialogDescription>
                </DialogHeader>

                <form onSubmit={handleSubmit} className="grid gap-4">
                    <div className="grid gap-2">
                        <Label htmlFor="task-assigned-to">Assigned to</Label>
                        <Select value={assignedToId.toString()} onValueChange={(value) => setAssignedToId(parseInt(value))}>
                            <SelectTrigger className={inputClass}>
                                <SelectValue placeholder="Select user" />
                            </SelectTrigger>
                            <SelectContent>
                                {/* <SelectItem value={currentUserId.toString()}>You</SelectItem> */}
                                {users.map((user) => (
                                    // console.log(user),
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
                        <Label htmlFor="task-description">Description</Label>
                        <Textarea
                            id="task-description"
                            value={description}
                            onChange={(e) => setDescription(e.target.value)}
                            placeholder="Follow up on proposal…"
                            required
                            autoFocus
                        />
                    </div>

                    <div className="grid gap-2">
                        <Label htmlFor="task-due">Due date</Label>
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
