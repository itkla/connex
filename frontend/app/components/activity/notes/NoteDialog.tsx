'use client';

import { useRef, useState, type WheelEvent } from 'react';
import { useRouter } from 'next/navigation';
import { useTranslations } from 'next-intl';
import { toastError, toastSuccess } from '@/app/lib/toast';
import { Loader2Icon } from 'lucide-react';
import { UserIcon, BriefcaseIcon } from '@heroicons/react/24/outline';

import {
    ResponsiveDialog,
    ResponsiveDialogContent,
    ResponsiveDialogHeader,
    ResponsiveDialogTitle,
    ResponsiveDialogDescription,
    ResponsiveDialogFooter,
    ResponsiveDialogClose,
} from '@/components/ui/responsive-dialog';
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
import RichNoteEditor from './RichNoteEditor';
import { InputGroupAddon } from '@/components/ui/input-group';
import { DialogStatusCover, resolveDialogStatus } from '@/components/ui/dialog-status-cover';
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
    /** Prefills the note content, e.g. text carried over from the Quick Create panel. */
    defaultContent?: string;
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
    defaultContent = '',
}: Props) {
    const submittingRef = useRef(false);

    const handleOpenChange = (next: boolean) => {
        if (!next && submittingRef.current) return;
        onOpenChange(next);
    };

    const [prevOpen, setPrevOpen] = useState(open);
    const [openCount, setOpenCount] = useState(0);
    if (open !== prevOpen) {
        setPrevOpen(open);
        if (open) setOpenCount((count) => count + 1);
    }

    return (
        <ResponsiveDialog open={open} onOpenChange={handleOpenChange}>
            <ResponsiveDialogContent className="gap-0 overflow-hidden p-0 sm:max-w-lg">
                <NoteDialogForm
                    key={openCount}
                    note={note}
                    persons={persons}
                    deals={deals}
                    currentUserId={currentUserId}
                    defaultPerson={defaultPerson}
                    defaultDeal={defaultDeal}
                    defaultContent={defaultContent}
                    onSubmittingChange={(value) => {
                        submittingRef.current = value;
                    }}
                    onClose={() => onOpenChange(false)}
                />
            </ResponsiveDialogContent>
        </ResponsiveDialog>
    );
}

type FormProps = {
    note: Note | null;
    persons: Contact[];
    deals: Deal[];
    currentUserId: number;
    defaultPerson: Contact | null;
    defaultDeal: Deal | null;
    defaultContent: string;
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
    defaultContent,
    onSubmittingChange,
    onClose,
}: FormProps) {
    const router = useRouter();
    const t = useTranslations('ActivityNotesDialog');
    const isEdit = note !== null;

    const [content, setContent] = useState(() => note?.content ?? defaultContent);
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
                    title: note.title ?? null,
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
                <ResponsiveDialogHeader className="ncd-rise -mt-12" style={{ animationDelay: '40ms' }}>
                    <ResponsiveDialogTitle className="text-xl font-semibold tracking-tight">{isEdit ? t('titleEdit') : t('titleCreate')}</ResponsiveDialogTitle>
                    <ResponsiveDialogDescription>{t('description')}</ResponsiveDialogDescription>
                </ResponsiveDialogHeader>

                <form onSubmit={handleSubmit} className="grid gap-5">
                    <div className="ncd-rise grid gap-1.5" style={{ animationDelay: '90ms' }}>
                        <Label htmlFor="note-content">{t('contentLabel')}</Label>
                        <div
                            id="note-content"
                            aria-invalid={fieldErrors.content ? true : undefined}
                            className={cn(
                                'overflow-hidden rounded-xl border bg-background transition-[box-shadow,border-color] focus-within:ring-2 focus-within:ring-brand/60',
                                fieldErrors.content
                                    ? 'border-destructive focus-within:ring-destructive/50'
                                    : 'border-border focus-within:border-brand',
                            )}
                        >
                            <RichNoteEditor
                                compact
                                value={content}
                                onChange={(next) => {
                                    setContent(next);
                                    clearError('content');
                                }}
                                excludeUserId={currentUserId}
                                autofocus
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

                    <ResponsiveDialogFooter className="ncd-rise" style={{ animationDelay: '190ms' }}>
                        <ResponsiveDialogClose asChild>
                            <Button type="button" variant="outline" disabled={submitting}>
                                {t('cancel')}
                            </Button>
                        </ResponsiveDialogClose>
                        <Button
                            type="submit"
                            variant="brand"
                            disabled={submitting || succeeded || !content.trim()}
                            className="min-w-24 shadow-sm transition hover:shadow-md"
                        >
                            {submitting ? (
                                <Loader2Icon className="size-4 animate-spin" />
                            ) : isEdit ? (
                                t('save')
                            ) : (
                                t('create')
                            )}
                        </Button>
                    </ResponsiveDialogFooter>
                </form>
            </div>
        </>
    );
}
