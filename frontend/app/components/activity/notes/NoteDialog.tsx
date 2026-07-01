'use client';

import { useRef, useState, type WheelEvent } from 'react';
import { useRouter } from 'next/navigation';
import { useTranslations } from 'next-intl';
import { toastError, toastSuccess } from '@/app/lib/toast';
import { Loader2Icon } from 'lucide-react';
import { Bars3BottomLeftIcon, UserIcon, BriefcaseIcon } from '@heroicons/react/24/outline';

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
import MentionEditor from './MentionEditor';
import { InputGroupAddon } from '@/components/ui/input-group';
import { DialogStatusCover, resolveDialogStatus, fieldInputClass } from '@/components/ui/dialog-status-cover';
import { cn } from '@/lib/utils';

import { ApiError, createNote, updateNote, isFieldError } from '@/app/lib/api';
import { useFieldErrors } from '@/app/hooks/useFieldErrors';
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
    const submittingRef = useRef(false);

    const handleOpenChange = (next: boolean) => {
        if (!next && submittingRef.current) return;
        onOpenChange(next);
    };

    return (
        <Dialog open={open} onOpenChange={handleOpenChange}>
            <DialogContent className="gap-0 overflow-hidden p-0 sm:max-w-lg">
                <NoteDialogForm
                    note={note}
                    persons={persons}
                    deals={deals}
                    currentUserId={currentUserId}
                    defaultPerson={defaultPerson}
                    defaultDeal={defaultDeal}
                    onSubmittingChange={(value) => {
                        submittingRef.current = value;
                    }}
                    onClose={() => onOpenChange(false)}
                />
            </DialogContent>
        </Dialog>
    );
}

type FormProps = {
    note: Note | null;
    persons: Contact[];
    deals: Deal[];
    currentUserId: number;
    defaultPerson: Contact | null;
    defaultDeal: Deal | null;
    onSubmittingChange: (value: boolean) => void;
    onClose: () => void;
};

/**
 * The note form. It lives inside {@code DialogContent} — which unmounts on close —
 * so its state initializes fresh from props on each open, with no reset effect.
 */
function NoteDialogForm({
    note,
    persons,
    deals,
    currentUserId,
    defaultPerson,
    defaultDeal,
    onSubmittingChange,
    onClose,
}: FormProps) {
    const router = useRouter();
    const t = useTranslations('ActivityNotesDialog');
    const isEdit = note !== null;

    const [content, setContent] = useState(() => note?.content ?? '');
    const [selectedPerson, setSelectedPerson] = useState<Contact | null>(() =>
        note ? persons.find((p) => p.id === note.person) ?? null : defaultPerson,
    );
    const [selectedDeal, setSelectedDeal] = useState<Deal | null>(() =>
        note ? deals.find((d) => d.id === note.deal) ?? null : defaultDeal,
    );
    const [submitting, setSubmitting] = useState(false);
    const [succeeded, setSucceeded] = useState(false);
    const { fieldErrors, reset: resetFieldErrors, clearError, captureFieldErrors } = useFieldErrors();

    const handleListWheel = (e: WheelEvent<HTMLDivElement>) => {
        const lineHeightPx = 16;
        const delta = e.deltaMode === 1 ? e.deltaY * lineHeightPx : e.deltaY;
        e.currentTarget.scrollTop += delta;
    };

    const handleSubmit = async (e: React.FormEvent<HTMLFormElement>) => {
        e.preventDefault();
        resetFieldErrors();
        const trimmed = content.trim();
        setSubmitting(true);
        onSubmittingChange(true);
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
            setSucceeded(true);
            router.refresh();
            setTimeout(() => onClose(), 900);
        } catch (err) {
            if (captureFieldErrors(err)) {
                if (isFieldError(err)) {
                    const firstKey = Object.keys(err.fieldErrors)[0];
                    if (firstKey) {
                        requestAnimationFrame(() => document.getElementById(`note-${firstKey}`)?.focus());
                    }
                }
                return;
            }
            const message =
                err instanceof ApiError ? err.message :
                err instanceof Error ? err.message :
                isEdit ? t('toastFailedSave') : t('toastFailedCreate');
            toastError(message);
        } finally {
            setSubmitting(false);
            onSubmittingChange(false);
        }
    };

    const hasErrors = Object.keys(fieldErrors).length > 0;
    const status = resolveDialogStatus({ isLoading: submitting, hasErrors, isSuccess: succeeded });

    return (
        <>
            <DialogStatusCover status={status} />

            <div className="px-6 pb-6">
                <DialogHeader className="ncd-rise -mt-12" style={{ animationDelay: '40ms' }}>
                    <DialogTitle className="text-xl font-semibold tracking-tight">{isEdit ? t('titleEdit') : t('titleCreate')}</DialogTitle>
                    <DialogDescription>{t('description')}</DialogDescription>
                </DialogHeader>

                <form onSubmit={handleSubmit} className="grid gap-5">
                    <div className="ncd-rise grid gap-1.5" style={{ animationDelay: '90ms' }}>
                        <Label htmlFor="note-content">{t('contentLabel')}</Label>
                        <div className="group relative">
                            <Bars3BottomLeftIcon className="pointer-events-none absolute left-3 top-3 size-4 text-muted-foreground transition-colors group-focus-within:text-brand" />
                            <MentionEditor
                                id="note-content"
                                value={content}
                                onChange={(next) => {
                                    setContent(next);
                                    clearError('content');
                                }}
                                placeholder={t('contentPlaceholder')}
                                ariaInvalid={Boolean(fieldErrors.content)}
                                ariaDescribedby={fieldErrors.content ? 'note-content-error' : undefined}
                                autoFocus
                                className={cn(fieldInputClass, 'pl-9 pr-3 py-2')}
                            />
                        </div>
                        {fieldErrors.content && <p id="note-content-error" className="text-sm text-destructive">{fieldErrors.content}</p>}
                    </div>

                    <div className="ncd-rise grid grid-cols-1 gap-3 md:grid-cols-2" style={{ animationDelay: '140ms' }}>
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
                                    className="rounded-lg border-0 bg-muted shadow-none ring-1 ring-border dark:bg-muted has-[[data-slot=input-group-control]:focus-visible]:ring-2 has-[[data-slot=input-group-control]:focus-visible]:ring-brand"
                                >
                                    <InputGroupAddon align="inline-start">
                                        <UserIcon className="size-4 text-muted-foreground transition-colors group-focus-within/input-group:text-brand" />
                                    </InputGroupAddon>
                                </ComboboxInput>
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
                                    className="rounded-lg border-0 bg-muted shadow-none ring-1 ring-border dark:bg-muted has-[[data-slot=input-group-control]:focus-visible]:ring-2 has-[[data-slot=input-group-control]:focus-visible]:ring-brand"
                                >
                                    <InputGroupAddon align="inline-start">
                                        <BriefcaseIcon className="size-4 text-muted-foreground transition-colors group-focus-within/input-group:text-brand" />
                                    </InputGroupAddon>
                                </ComboboxInput>
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

                    <DialogFooter className="ncd-rise" style={{ animationDelay: '190ms' }}>
                        <DialogClose asChild>
                            <Button type="button" variant="outline" disabled={submitting}>
                                {t('cancel')}
                            </Button>
                        </DialogClose>
                        <Button
                            type="submit"
                            disabled={submitting || succeeded || !content.trim()}
                            className="min-w-24 bg-brand text-white shadow-sm transition hover:bg-brand-hover hover:shadow-md"
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
            </div>
        </>
    );
}
