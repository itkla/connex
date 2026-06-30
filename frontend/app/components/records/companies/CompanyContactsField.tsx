'use client';

import { useEffect, useMemo } from 'react';
import { AnimatePresence, motion, useReducedMotion } from 'motion/react';
import { Label } from '@/components/ui/label';
import { initials } from '@/app/lib/utils';
import { PencilSquareIcon, PlusIcon, XMarkIcon } from '@heroicons/react/24/outline';
import { useTranslations } from 'next-intl';

const EASE_OUT: [number, number, number, number] = [0.23, 1, 0.32, 1];

/** A contact staged in the create-company dialog before the company exists. */
export type PendingContact = {
    tempId: string;
    name: string;
    email: string;
    title: string;
    phone: string;
    imageFile: File | null;
};

/** Fields of a staged contact, before a {@link PendingContact.tempId} is assigned. */
export type PendingContactDraft = Omit<PendingContact, 'tempId'>;

type Props = {
    contacts: PendingContact[];
    onAdd: () => void;
    onEdit: (contact: PendingContact) => void;
    onRemove: (contact: PendingContact) => void;
    disabled?: boolean;
};

/**
 * Round avatar for a staged contact: previews the pending photo file (managing the object URL so
 * it is revoked, not leaked) and otherwise falls back to the name's initials.
 */
function StagedAvatar({ contact }: { contact: PendingContact }) {
    const src = useMemo(() => (contact.imageFile ? URL.createObjectURL(contact.imageFile) : null), [contact.imageFile]);
    useEffect(() => {
        if (!src) return;
        return () => URL.revokeObjectURL(src);
    }, [src]);

    if (src) {
        return <img src={src} alt="" className="size-9 shrink-0 rounded-full object-cover ring-1 ring-inset ring-border" />;
    }
    return (
        <span className="flex size-9 shrink-0 select-none items-center justify-center rounded-full bg-brand/10 text-xs font-semibold text-brand ring-1 ring-inset ring-brand/15">
            {initials(contact.name)}
        </span>
    );
}

/**
 * Staged-contacts section of the create-company dialog: the list of people queued for creation
 * plus an affordance to add another. Adding or tapping a row delegates to the dialog, which slides
 * to a dedicated contact sub-view; this component only renders the queue.
 */
export default function CompanyContactsField({ contacts, onAdd, onEdit, onRemove, disabled = false }: Props) {
    const t = useTranslations('CompaniesNewDialog');
    const reduce = useReducedMotion() ?? false;

    return (
        <div className="grid gap-2">
            <div className="flex items-center justify-between gap-2">
                <div className="flex items-baseline gap-1.5">
                    <Label>{t('labelContacts')}</Label>
                    <span className="text-xs font-normal text-muted-foreground">{t('contactsOptional')}</span>
                </div>
                {contacts.length > 0 && (
                    <span className="inline-flex items-center rounded-full bg-muted px-2 py-0.5 text-xs font-medium tabular-nums text-muted-foreground ring-1 ring-border/60">
                        {t('contactsCount', { count: contacts.length })}
                    </span>
                )}
            </div>

            {contacts.length > 0 && (
                <ul className="grid gap-1.5">
                    <AnimatePresence initial={false}>
                        {contacts.map((c) => {
                            const meta = [c.title, c.email].filter(Boolean);
                            return (
                                <motion.li
                                    key={c.tempId}
                                    layout={!reduce}
                                    initial={reduce ? false : { opacity: 0, height: 0 }}
                                    animate={{ opacity: 1, height: 'auto' }}
                                    exit={reduce ? { opacity: 0 } : { opacity: 0, height: 0, transition: { duration: 0.12, ease: EASE_OUT } }}
                                    transition={{ duration: reduce ? 0 : 0.18, ease: EASE_OUT }}
                                    className="overflow-hidden"
                                >
                                    <div className="group flex items-center gap-2.5 rounded-xl bg-muted/50 pl-3 pr-2 py-2 ring-1 ring-inset ring-border/60 transition-colors focus-within:ring-brand/40 hover:bg-muted">
                                        <button
                                            type="button"
                                            onClick={() => onEdit(c)}
                                            disabled={disabled}
                                            aria-label={t('editContactAria', { name: c.name })}
                                            className="-m-1 flex min-w-0 flex-1 items-center gap-2.5 rounded-lg p-1 text-left outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-brand disabled:pointer-events-none"
                                        >
                                            <StagedAvatar contact={c} />
                                            <span className="min-w-0 flex-1">
                                                <span className="block truncate text-sm font-semibold tracking-tight text-foreground" title={c.name}>
                                                    {c.name}
                                                </span>
                                                {meta.length > 0 && (
                                                    <span className="block truncate text-xs text-muted-foreground" title={meta.join(' · ')}>
                                                        {c.title}
                                                        {c.title && c.email && (
                                                            <span aria-hidden className="px-1 text-muted-foreground/40">
                                                                ·
                                                            </span>
                                                        )}
                                                        {c.email}
                                                    </span>
                                                )}
                                            </span>
                                            <PencilSquareIcon className="size-4 shrink-0 text-muted-foreground opacity-0 transition-opacity group-hover:opacity-100 group-focus-within:opacity-100" />
                                        </button>
                                        <button
                                            type="button"
                                            onClick={() => onRemove(c)}
                                            disabled={disabled}
                                            aria-label={t('removeContactAria', { name: c.name })}
                                            className="flex size-7 shrink-0 items-center justify-center rounded-lg text-muted-foreground transition hover:bg-background hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-brand active:scale-95 disabled:pointer-events-none disabled:opacity-50"
                                        >
                                            <XMarkIcon className="size-4" />
                                        </button>
                                    </div>
                                </motion.li>
                            );
                        })}
                    </AnimatePresence>
                </ul>
            )}

            <button
                type="button"
                id="company-add-contact-trigger"
                onClick={onAdd}
                disabled={disabled}
                className="flex w-full items-center justify-center gap-2 rounded-xl border border-dashed border-border/80 py-3 text-sm font-medium text-muted-foreground transition hover:border-brand/50 hover:bg-muted/40 hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-brand active:scale-[0.99] disabled:pointer-events-none disabled:opacity-50"
            >
                <PlusIcon className="size-4" />
                {contacts.length > 0 ? t('addAnotherContact') : t('addContact')}
            </button>
        </div>
    );
}
