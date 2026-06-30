'use client';

import { forwardRef, useCallback, useEffect, useId, useImperativeHandle, useRef, useState, type KeyboardEvent } from 'react';
import { AnimatePresence, motion, useReducedMotion } from 'motion/react';
import { Button } from '@/components/ui/button';
import { Label } from '@/components/ui/label';
import { cn } from '@/lib/utils';
import { initials } from '@/app/lib/utils';
import { fieldErrorClass, fieldInputClass, fieldLeadIconClass } from '@/components/ui/dialog-status-cover';
import { BriefcaseIcon, EnvelopeIcon, PhoneIcon, PlusIcon, UserIcon, XMarkIcon } from '@heroicons/react/24/outline';
import { useTranslations } from 'next-intl';

const EASE_OUT: [number, number, number, number] = [0.23, 1, 0.32, 1];
const MORPH_SPRING = { type: 'spring' as const, stiffness: 520, damping: 42 };
const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

/** A contact staged in the create-company dialog before the company exists. */
export type PendingContact = {
    tempId: string;
    name: string;
    email: string;
    title: string;
    phone: string;
};

/** Fields of a staged contact, before a {@link PendingContact.tempId} is assigned. */
export type PendingContactDraft = Omit<PendingContact, 'tempId'>;

/** Imperative surface so the dialog can commit an open, valid mini-form on submit. */
export type CompanyContactsFieldHandle = {
    /**
     * Commit the open mini-form if it holds a valid contact, returning that draft so the
     * caller can include it when creating contacts. Returns null when the form is closed or
     * invalid (the draft is then dropped). Always leaves the form collapsed.
     */
    flush: () => PendingContactDraft | null;
};

const EMPTY_DRAFT: PendingContactDraft = { name: '', email: '', title: '', phone: '' };

type FieldErrors = { name?: string; email?: string };

type Props = {
    contacts: PendingContact[];
    onAdd: (draft: PendingContactDraft) => void;
    onRemove: (tempId: string) => void;
    disabled?: boolean;
};

/**
 * Inline affordance for staging contacts while creating a company. A dashed tile morphs into a
 * compact create-contact mini-form; confirming a contact collapses it into a removable row and
 * keeps the form open for the next, so several people can be staged in one pass.
 */
const CompanyContactsField = forwardRef<CompanyContactsFieldHandle, Props>(function CompanyContactsField(
    { contacts, onAdd, onRemove, disabled = false },
    ref,
) {
    const t = useTranslations('CompaniesNewDialog');
    const reduce = useReducedMotion() ?? false;
    const uid = useId();
    const [expanded, setExpanded] = useState(false);
    const [draft, setDraft] = useState<PendingContactDraft>(EMPTY_DRAFT);
    const [errors, setErrors] = useState<FieldErrors>({});
    const [announcement, setAnnouncement] = useState('');
    const nameRef = useRef<HTMLInputElement>(null);
    const tileRef = useRef<HTMLButtonElement>(null);
    const focusIntent = useRef<'name' | 'tile' | null>(null);

    useEffect(() => {
        if (focusIntent.current === 'name') {
            focusIntent.current = null;
            nameRef.current?.focus();
        } else if (focusIntent.current === 'tile') {
            focusIntent.current = null;
            tileRef.current?.focus();
        }
    });

    const announce = useCallback((message: string) => {
        setAnnouncement('');
        queueMicrotask(() => setAnnouncement(message));
    }, []);

    const validate = useCallback(
        (value: PendingContactDraft): FieldErrors => {
            const next: FieldErrors = {};
            if (!value.name.trim()) next.name = t('contactNameRequired');
            else if (value.email.trim() && !EMAIL_PATTERN.test(value.email.trim())) next.email = t('contactEmailInvalid');
            return next;
        },
        [t],
    );

    const normalize = (value: PendingContactDraft): PendingContactDraft => ({
        name: value.name.trim(),
        email: value.email.trim(),
        title: value.title.trim(),
        phone: value.phone.trim(),
    });

    const openForm = () => {
        focusIntent.current = 'name';
        setExpanded(true);
    };

    const closeForm = () => {
        focusIntent.current = 'tile';
        setDraft(EMPTY_DRAFT);
        setErrors({});
        setExpanded(false);
    };

    const confirmDraft = () => {
        const next = validate(draft);
        if (next.name || next.email) {
            setErrors(next);
            return;
        }
        const value = normalize(draft);
        onAdd(value);
        announce(t('contactAddedAnnounce', { name: value.name }));
        focusIntent.current = 'name';
        setDraft(EMPTY_DRAFT);
        setErrors({});
    };

    const removeContact = (contact: PendingContact) => {
        onRemove(contact.tempId);
        announce(t('contactRemovedAnnounce', { name: contact.name }));
        focusIntent.current = expanded ? 'name' : 'tile';
    };

    useImperativeHandle(
        ref,
        () => ({
            flush: () => {
                if (!expanded) return null;
                const next = validate(draft);
                const value = next.name || next.email ? null : normalize(draft);
                setDraft(EMPTY_DRAFT);
                setErrors({});
                setExpanded(false);
                return value;
            },
        }),
        [expanded, draft, validate],
    );

    const handleKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
        if (event.key === 'Enter' && event.target instanceof HTMLInputElement) {
            event.preventDefault();
            event.stopPropagation();
            confirmDraft();
        } else if (event.key === 'Escape') {
            event.preventDefault();
            event.stopPropagation();
            closeForm();
        }
    };

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
                                    <div className="flex items-center gap-2.5 rounded-xl bg-muted/50 px-3 py-2 ring-1 ring-inset ring-border/60">
                                        <span className="flex size-8 shrink-0 select-none items-center justify-center rounded-full bg-brand/10 text-xs font-semibold text-brand ring-1 ring-inset ring-brand/15">
                                            {initials(c.name)}
                                        </span>
                                        <div className="min-w-0 flex-1">
                                            <p className="truncate text-sm font-semibold tracking-tight text-foreground" title={c.name}>
                                                {c.name}
                                            </p>
                                            {meta.length > 0 && (
                                                <p className="truncate text-xs text-muted-foreground" title={meta.join(' · ')}>
                                                    {c.title}
                                                    {c.title && c.email && (
                                                        <span aria-hidden className="px-1 text-muted-foreground/40">
                                                            ·
                                                        </span>
                                                    )}
                                                    {c.email}
                                                </p>
                                            )}
                                        </div>
                                        <button
                                            type="button"
                                            onClick={() => removeContact(c)}
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

            <motion.div layout={!reduce} transition={reduce ? { duration: 0 } : MORPH_SPRING} className="overflow-hidden rounded-xl">
                <AnimatePresence mode="popLayout" initial={false}>
                    {expanded ? (
                        <motion.div
                            key="form"
                            initial={reduce ? false : { opacity: 0 }}
                            animate={{ opacity: 1 }}
                            exit={{ opacity: 0 }}
                            transition={{ duration: reduce ? 0 : 0.12, ease: EASE_OUT }}
                            role="group"
                            aria-label={t('addContact')}
                            onKeyDown={handleKeyDown}
                            className="grid gap-2.5 rounded-xl bg-muted/50 p-3 ring-1 ring-inset ring-border transition-shadow focus-within:ring-brand/40"
                        >
                            <div className="grid gap-1">
                                <Label htmlFor={`${uid}-name`} className="sr-only">
                                    {t('contactNameLabel')}
                                </Label>
                                <div className="group relative">
                                    <UserIcon className={fieldLeadIconClass} />
                                    <input
                                        ref={nameRef}
                                        id={`${uid}-name`}
                                        type="text"
                                        value={draft.name}
                                        onChange={(e) => {
                                            setDraft((prev) => ({ ...prev, name: e.target.value }));
                                            if (errors.name) setErrors((prev) => ({ ...prev, name: undefined }));
                                        }}
                                        className={cn(fieldInputClass, 'pl-9 pr-3', errors.name && fieldErrorClass)}
                                        placeholder={t('contactNamePlaceholder')}
                                        aria-required
                                        aria-invalid={Boolean(errors.name)}
                                        aria-describedby={errors.name ? `${uid}-name-error` : undefined}
                                    />
                                </div>
                                {errors.name && (
                                    <p id={`${uid}-name-error`} className="text-xs text-destructive">
                                        {errors.name}
                                    </p>
                                )}
                            </div>

                            <div className="grid grid-cols-2 gap-2">
                                <div className="grid gap-1">
                                    <Label htmlFor={`${uid}-email`} className="sr-only">
                                        {t('contactEmailLabel')}
                                    </Label>
                                    <div className="group relative">
                                        <EnvelopeIcon className={fieldLeadIconClass} />
                                        <input
                                            id={`${uid}-email`}
                                            type="email"
                                            value={draft.email}
                                            onChange={(e) => {
                                                setDraft((prev) => ({ ...prev, email: e.target.value }));
                                                if (errors.email) setErrors((prev) => ({ ...prev, email: undefined }));
                                            }}
                                            className={cn(fieldInputClass, 'pl-9 pr-3', errors.email && fieldErrorClass)}
                                            placeholder={t('contactEmailPlaceholder')}
                                            aria-invalid={Boolean(errors.email)}
                                            aria-describedby={errors.email ? `${uid}-email-error` : undefined}
                                        />
                                    </div>
                                    {errors.email && (
                                        <p id={`${uid}-email-error`} className="text-xs text-destructive">
                                            {errors.email}
                                        </p>
                                    )}
                                </div>
                                <div className="grid gap-1">
                                    <Label htmlFor={`${uid}-title`} className="sr-only">
                                        {t('contactTitleLabel')}
                                    </Label>
                                    <div className="group relative">
                                        <BriefcaseIcon className={fieldLeadIconClass} />
                                        <input
                                            id={`${uid}-title`}
                                            type="text"
                                            value={draft.title}
                                            onChange={(e) => setDraft((prev) => ({ ...prev, title: e.target.value }))}
                                            className={cn(fieldInputClass, 'pl-9 pr-3')}
                                            placeholder={t('contactTitlePlaceholder')}
                                        />
                                    </div>
                                </div>
                            </div>

                            <div className="grid gap-1">
                                <Label htmlFor={`${uid}-phone`} className="sr-only">
                                    {t('contactPhoneLabel')}
                                </Label>
                                <div className="group relative">
                                    <PhoneIcon className={fieldLeadIconClass} />
                                    <input
                                        id={`${uid}-phone`}
                                        type="tel"
                                        value={draft.phone}
                                        onChange={(e) => setDraft((prev) => ({ ...prev, phone: e.target.value }))}
                                        className={cn(fieldInputClass, 'pl-9 pr-3')}
                                        placeholder={t('contactPhonePlaceholder')}
                                    />
                                </div>
                            </div>

                            <div className="flex items-center justify-end gap-2 pt-0.5">
                                <Button type="button" variant="ghost" size="sm" onClick={closeForm} disabled={disabled}>
                                    {t('contactCancel')}
                                </Button>
                                <Button
                                    type="button"
                                    size="sm"
                                    onClick={confirmDraft}
                                    disabled={disabled}
                                    className="bg-brand text-white transition hover:bg-brand-hover active:scale-[0.97]"
                                >
                                    {t('contactAdd')}
                                </Button>
                            </div>
                        </motion.div>
                    ) : (
                        <motion.button
                            key="tile"
                            ref={tileRef}
                            type="button"
                            initial={reduce ? false : { opacity: 0 }}
                            animate={{ opacity: 1 }}
                            exit={{ opacity: 0 }}
                            transition={{ duration: reduce ? 0 : 0.12, ease: EASE_OUT }}
                            onClick={openForm}
                            disabled={disabled}
                            whileTap={reduce ? undefined : { scale: 0.98 }}
                            className="flex w-full items-center justify-center gap-2 rounded-xl border border-dashed border-border/80 py-3 text-sm font-medium text-muted-foreground transition hover:border-brand/50 hover:bg-muted/40 hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-brand disabled:pointer-events-none disabled:opacity-50"
                        >
                            <PlusIcon className="size-4" />
                            {contacts.length > 0 ? t('addAnotherContact') : t('addContact')}
                        </motion.button>
                    )}
                </AnimatePresence>
            </motion.div>

            <p aria-live="polite" className="sr-only">
                {announcement}
            </p>
        </div>
    );
});

export default CompanyContactsField;
