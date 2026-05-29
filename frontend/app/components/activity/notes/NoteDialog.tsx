'use client';

import { useEffect, useState, type WheelEvent } from 'react';
import { useRouter } from 'next/navigation';
import { useTranslations } from 'next-intl';
import { toastError, toastSuccess } from '@/app/lib/toast';
import { Loader2Icon } from 'lucide-react';

import {
    Dialog,
    DialogContent,
    DialogHeader,
    DialogTitle,
    DialogDescription,
    DialogFooter,
    DialogClose,
} from '@/components/ui/dialog';
import {
    Combobox,
    ComboboxContent,
    ComboboxEmpty,
    ComboboxInput,
    ComboboxItem,
    ComboboxList,
} from '@/components/ui/combobox';
import { Button } from '@/components/ui/button';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';

import { ApiError, createNote, updateNote } from '@/app/lib/api';
import type { Contact, Deal, Note } from '@/app/lib/types';

type Props = {
    open: boolean;
    onOpenChange: (open: boolean) => void;
    note: Note | null;
    persons: Contact[];
    deals: Deal[];
    currentUserId: number;
    defaultPerson?: Contact | null;
    defaultDeal?: Deal | null;
};

export default function NoteDialog({
    open,
    onOpenChange,
    note,
    persons,
    deals,
    currentUserId,
    defaultPerson = null,
    defaultDeal = null,
}: Props) {
    const router = useRouter();
    const t = useTranslations('ActivityNotesDialog');
    const isEdit = note !== null;

    const [content, setContent] = useState('');
    const [selectedPerson, setSelectedPerson] = useState<Contact | null>(null);
    const [selectedDeal, setSelectedDeal] = useState<Deal | null>(null);
    const [submitting, setSubmitting] = useState(false);

    useEffect(() => {
        if (!open) return;
        if (note) {
            setContent(note.content ?? '');
            setSelectedPerson(persons.find((p) => p.id === note.person) ?? null);
            setSelectedDeal(deals.find((d) => d.id === note.deal) ?? null);
        } else {
            setContent('');
            setSelectedPerson(defaultPerson ?? null);
            setSelectedDeal(defaultDeal ?? null);
        }
    }, [open, note, persons, deals, defaultPerson, defaultDeal]);

    const handleListWheel = (e: WheelEvent<HTMLDivElement>) => {
        const lineHeightPx = 16;
        const delta = e.deltaMode === 1 ? e.deltaY * lineHeightPx : e.deltaY;
        e.currentTarget.scrollTop += delta;
    };

    const handleSubmit = async (e: React.FormEvent<HTMLFormElement>) => {
        e.preventDefault();
        const trimmed = content.trim();
        if (!trimmed) {
            toastError(t('toastContentRequired'));
            return;
        }
        setSubmitting(true);
        try {
            if (isEdit && note) {
                await updateNote(note.id, {
                    content: trimmed,
                    author: note.author,
                    person: selectedPerson?.id ?? null,
                    deal: selectedDeal?.id ?? null,
                });
                toastSuccess(t('toastUpdated'));
            } else {
                await createNote({
                    content: trimmed,
                    author: currentUserId,
                    person: selectedPerson?.id ?? null,
                    deal: selectedDeal?.id ?? null,
                });
                toastSuccess(t('toastCreated'));
            }
            onOpenChange(false);
            router.refresh();
        } catch (err) {
            const message =
                err instanceof ApiError ? err.message :
                err instanceof Error ? err.message :
                isEdit ? t('toastFailedSave') : t('toastFailedCreate');
            toastError(message);
        } finally {
            setSubmitting(false);
        }
    };

    const inputClass =
        'w-full rounded-lg bg-neutral-100 px-3 py-2 text-sm text-black placeholder-neutral-500 outline-none ring-1 ring-black/5 transition focus:ring-2 focus:ring-brand';

    return (
        <Dialog open={open} onOpenChange={onOpenChange}>
            <DialogContent>
                <DialogHeader>
                    <DialogTitle>{isEdit ? t('titleEdit') : t('titleCreate')}</DialogTitle>
                    <DialogDescription>{t('description')}</DialogDescription>
                </DialogHeader>

                <form onSubmit={handleSubmit} className="grid gap-4">
                    <div className="grid gap-1.5">
                        <Label htmlFor="note-content">{t('contentLabel')}</Label>
                        <Textarea
                            id="note-content"
                            value={content}
                            onChange={(e) => setContent(e.target.value)}
                            placeholder={t('contentPlaceholder')}
                            rows={6}
                            autoFocus
                            required
                        />
                    </div>

                    <div className="grid grid-cols-1 gap-3 md:grid-cols-2">
                        <div className="grid gap-1.5">
                            <Label htmlFor="note-person">{t('personLabel')}</Label>
                            <Combobox
                                items={persons}
                                itemToStringLabel={(p: Contact) => p.name}
                                value={selectedPerson}
                                onValueChange={(p) => setSelectedPerson(p as Contact | null)}
                            >
                                <ComboboxInput
                                    id="note-person"
                                    placeholder={t('personPlaceholder')}
                                    className="ring-1 ring-black/5"
                                />
                                <ComboboxContent className="pointer-events-auto">
                                    <ComboboxList onWheel={handleListWheel}>
                                        <ComboboxEmpty>{t('noPersonFound')}</ComboboxEmpty>
                                        {persons.map((p) => (
                                            <ComboboxItem key={p.id} value={p}>
                                                {p.name}
                                            </ComboboxItem>
                                        ))}
                                    </ComboboxList>
                                </ComboboxContent>
                            </Combobox>
                        </div>

                        <div className="grid gap-1.5">
                            <Label htmlFor="note-deal">{t('dealLabel')}</Label>
                            <Combobox
                                items={deals}
                                itemToStringLabel={(d: Deal) => d.name}
                                value={selectedDeal}
                                onValueChange={(d) => setSelectedDeal(d as Deal | null)}
                            >
                                <ComboboxInput
                                    id="note-deal"
                                    placeholder={t('dealPlaceholder')}
                                    className="ring-1 ring-black/5"
                                />
                                <ComboboxContent className="pointer-events-auto">
                                    <ComboboxList onWheel={handleListWheel}>
                                        <ComboboxEmpty>{t('noDealFound')}</ComboboxEmpty>
                                        {deals.map((d) => (
                                            <ComboboxItem key={d.id} value={d}>
                                                {d.name}
                                            </ComboboxItem>
                                        ))}
                                    </ComboboxList>
                                </ComboboxContent>
                            </Combobox>
                        </div>
                    </div>

                    <DialogFooter>
                        <DialogClose asChild>
                            <Button type="button" variant="outline" disabled={submitting}>
                                {t('cancel')}
                            </Button>
                        </DialogClose>
                        <Button
                            type="submit"
                            disabled={submitting || !content.trim()}
                            className="bg-brand text-white hover:bg-brand-dark"
                        >
                            {submitting ? (
                                <Loader2Icon className="size-4 animate-spin" />
                            ) : isEdit ? (
                                t('save')
                            ) : (
                                t('create')
                            )}
                        </Button>
                    </DialogFooter>
                </form>
            </DialogContent>
        </Dialog>
    );
}