'use client';

import { useState } from 'react';
import { useRouter } from 'next/navigation';
import { useTranslations } from 'next-intl';
import { PlusIcon } from '@heroicons/react/16/solid';
import { CheckCircleIcon, DocumentTextIcon } from '@heroicons/react/24/outline';
import { Loader2Icon } from 'lucide-react';

import {
    Dialog,
    DialogClose,
    DialogContent,
    DialogDescription,
    DialogFooter,
    DialogHeader,
    DialogTitle,
} from '@/components/ui/dialog';
import {
    DropdownMenu,
    DropdownMenuContent,
    DropdownMenuItem,
    DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { Button } from '@/components/ui/button';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
import { createNote, createTask } from '@/app/lib/api';
import { toastError, toastSuccess } from '@/app/lib/toast';

type Which = 'task' | 'note' | null;

const inputClass =
    'w-full rounded-lg bg-muted px-3 py-2 text-sm text-foreground placeholder:text-muted-foreground outline-none ring-1 ring-border transition focus:ring-2 focus:ring-brand';

export default function QuickCreate({ currentUserId }: { currentUserId: number }) {
    const router = useRouter();
    const t = useTranslations('DashboardQuickCreate');
    const [which, setWhich] = useState<Which>(null);
    const [submitting, setSubmitting] = useState(false);
    const [description, setDescription] = useState('');
    const [dueDate, setDueDate] = useState('');
    const [content, setContent] = useState('');

    const reset = () => {
        setDescription('');
        setDueDate('');
        setContent('');
    };
    const close = () => {
        setWhich(null);
        reset();
    };

    async function submitTask(e: React.FormEvent<HTMLFormElement>) {
        e.preventDefault();
        if (!description.trim()) {
            toastError(t('descriptionRequired'));
            return;
        }
        setSubmitting(true);
        try {
            await createTask({
                description: description.trim(),
                dueDate: dueDate || undefined,
                assignedToId: currentUserId,
            });
            toastSuccess(t('taskCreated'), {
                action: { label: t('view'), onClick: () => router.push('/activity/tasks') },
            });
            close();
            router.refresh();
        } catch (err) {
            toastError(err instanceof Error ? err.message : t('failed'));
        } finally {
            setSubmitting(false);
        }
    }

    async function submitNote(e: React.FormEvent<HTMLFormElement>) {
        e.preventDefault();
        if (!content.trim()) {
            toastError(t('contentRequired'));
            return;
        }
        setSubmitting(true);
        try {
            await createNote({ content: content.trim(), author: currentUserId });
            toastSuccess(t('noteCreated'), {
                action: { label: t('view'), onClick: () => router.push('/activity/notes') },
            });
            close();
            router.refresh();
        } catch (err) {
            toastError(err instanceof Error ? err.message : t('failed'));
        } finally {
            setSubmitting(false);
        }
    }

    return (
        <>
            <DropdownMenu>
                <DropdownMenuTrigger asChild>
                    <button
                        type="button"
                        className="inline-flex items-center gap-1.5 rounded-full bg-brand px-4 py-2 text-sm font-semibold text-neutral-950 transition-[transform,background-color] duration-150 ease-out hover:bg-brand-hover active:scale-[0.97]"
                    >
                        <PlusIcon className="size-4" />
                        {t('new')}
                    </button>
                </DropdownMenuTrigger>
                <DropdownMenuContent align="end" className="w-44">
                    <DropdownMenuItem
                        onSelect={(e) => {
                            e.preventDefault();
                            setWhich('task');
                        }}
                    >
                        <CheckCircleIcon className="size-4 text-muted-foreground" />
                        {t('newTask')}
                    </DropdownMenuItem>
                    <DropdownMenuItem
                        onSelect={(e) => {
                            e.preventDefault();
                            setWhich('note');
                        }}
                    >
                        <DocumentTextIcon className="size-4 text-muted-foreground" />
                        {t('newNote')}
                    </DropdownMenuItem>
                </DropdownMenuContent>
            </DropdownMenu>

            <Dialog open={which === 'task'} onOpenChange={(open) => !open && close()}>
                <DialogContent>
                    <DialogHeader>
                        <DialogTitle>{t('taskTitle')}</DialogTitle>
                        <DialogDescription>{t('taskDescription')}</DialogDescription>
                    </DialogHeader>
                    <form onSubmit={submitTask} className="grid gap-4">
                        <div className="grid gap-2">
                            <Label htmlFor="qc-task-desc">{t('descriptionField')}</Label>
                            <Textarea
                                id="qc-task-desc"
                                value={description}
                                onChange={(e) => setDescription(e.target.value)}
                                placeholder={t('descriptionPlaceholder')}
                                autoFocus
                                required
                            />
                        </div>
                        <div className="grid gap-2">
                            <Label htmlFor="qc-task-due">{t('dueDate')}</Label>
                            <input
                                id="qc-task-due"
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
                            <Button
                                type="submit"
                                disabled={submitting}
                                className="bg-brand text-white hover:bg-brand-dark"
                            >
                                {submitting ? <Loader2Icon className="size-4 animate-spin" /> : t('create')}
                            </Button>
                        </DialogFooter>
                    </form>
                </DialogContent>
            </Dialog>

            <Dialog open={which === 'note'} onOpenChange={(open) => !open && close()}>
                <DialogContent>
                    <DialogHeader>
                        <DialogTitle>{t('noteTitle')}</DialogTitle>
                        <DialogDescription>{t('noteDescription')}</DialogDescription>
                    </DialogHeader>
                    <form onSubmit={submitNote} className="grid gap-4">
                        <div className="grid gap-2">
                            <Label htmlFor="qc-note-content">{t('contentField')}</Label>
                            <Textarea
                                id="qc-note-content"
                                value={content}
                                onChange={(e) => setContent(e.target.value)}
                                placeholder={t('contentPlaceholder')}
                                className="min-h-32"
                                autoFocus
                                required
                            />
                        </div>
                        <DialogFooter>
                            <DialogClose asChild>
                                <Button type="button" variant="outline" disabled={submitting}>
                                    {t('cancel')}
                                </Button>
                            </DialogClose>
                            <Button
                                type="submit"
                                disabled={submitting}
                                className="bg-brand text-white hover:bg-brand-dark"
                            >
                                {submitting ? <Loader2Icon className="size-4 animate-spin" /> : t('create')}
                            </Button>
                        </DialogFooter>
                    </form>
                </DialogContent>
            </Dialog>
        </>
    );
}