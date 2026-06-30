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
    const layoutId = `${uid}-morph`;
    const [expanded, setExpanded] = useState(false);
    const [draft, setDraft] = useState<PendingContactDraft>(EMPTY_DRAFT);
    const [errors, setErrors] = useState<FieldErrors>({});
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
        onAdd(normalize(draft));
        focusIntent.current = 'name';
        setDraft(EMPTY_DRAFT);
        setErrors({});
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
        if (event.key === 'Enter') {
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
            <div className="flex items-center justify-between">
                <Label>{t('labelContacts')}</Label>
                {contacts.length > 0 && (
                    <span className="text-xs text-muted-foreground">{t('contactsCount', { count: contacts.length })}</span>
                )}
            </div>

            {contacts.length > 0 && (
                <ul className="grid gap-1.5">
                    <AnimatePresence initial={false}>
                        {contacts.map((c) => (
                            <motion.li
                                key={c.tempId}
                                layout={!reduce}
                                initial={reduce ? false : { opacity: 0, height: 0 }}
                                animate={{ opacity: 1, height: 'auto' }}
                                exit={reduce ? { opacity: 0 } : { opacity: 0, height: 0 }}
                                transition={{ duration: reduce ? 0 : 0.16, ease: EASE_OUT }}
                                className="overflow-hidden"
                            >
                                <div className="flex items-center gap-2.5 rounded-xl bg-muted/60 px-3 py-2 ring-1 ring-border/70">
                                    <span className="flex size-8 shrink-0 items-center justify-center rounded-full bg-brand/10 text-xs font-semibold text-brand">
                                        {initials(c.name)}
                                    </span>
                                    <div className="min-w-0 flex-1">
                                        <p className="truncate text-sm font-medium text-foreground">{c.name}</p>
                                        {(c.title || c.email) && (
                                            <p className="truncate text-xs text-muted-foreground">
                                                {[c.title, c.email].filter(Boolean).join(' · ')}
                                            </p>
                                        )}
                                    </div>
                                    <button
                                        type="button"
                                        onClick={() => onRemove(c.tempId)}
                                        disabled={disabled}
                                        aria-label={t('removeContactAria', { name: c.name })}
                                        className="flex size-7 shrink-0 items-center justify-center rounded-md text-muted-foreground transition hover:bg-background hover:text-foreground active:scale-95 disabled:pointer-events-none disabled:opacity-50"
                                    >
                                        <XMarkIcon className="size-4" />
                                    </button>
                                </div>
                            </motion.li>
                        ))}
                    </AnimatePresence>
                </ul>
            )}

            <AnimatePresence mode="popLayout" initial={false}>
                {expanded ? (
                    <motion.div
                        key="form"
                        layoutId={reduce ? undefined : layoutId}
                        transition={reduce ? { duration: 0 } : MORPH_SPRING}
                        role="group"
                        aria-label={t('addContact')}
                        onKeyDown={handleKeyDown}
                        className="grid gap-2.5 rounded-xl bg-muted/40 p-3 ring-1 ring-border"
                    >
                        <div className="grid gap-1">
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
                                    aria-label={t('contactNamePlaceholder')}
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
                                        aria-label={t('contactEmailPlaceholder')}
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
                            <div className="group relative">
                                <BriefcaseIcon className={fieldLeadIconClass} />
                                <input
                                    id={`${uid}-title`}
                                    type="text"
                                    value={draft.title}
                                    onChange={(e) => setDraft((prev) => ({ ...prev, title: e.target.value }))}
                                    className={cn(fieldInputClass, 'pl-9 pr-3')}
                                    placeholder={t('contactTitlePlaceholder')}
                                    aria-label={t('contactTitlePlaceholder')}
                                />
                            </div>
                        </div>

                        <div className="group relative">
                            <PhoneIcon className={fieldLeadIconClass} />
                            <input
                                id={`${uid}-phone`}
                                type="tel"
                                value={draft.phone}
                                onChange={(e) => setDraft((prev) => ({ ...prev, phone: e.target.value }))}
                                className={cn(fieldInputClass, 'pl-9 pr-3')}
                                placeholder={t('contactPhonePlaceholder')}
                                aria-label={t('contactPhonePlaceholder')}
                            />
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
                                className="bg-brand text-white shadow-sm transition hover:bg-brand-hover hover:shadow-md"
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
                        layoutId={reduce ? undefined : layoutId}
                        transition={reduce ? { duration: 0 } : MORPH_SPRING}
                        onClick={openForm}
                        disabled={disabled}
                        whileTap={reduce ? undefined : { scale: 0.99 }}
                        className="flex w-full items-center justify-center gap-2 rounded-xl border border-dashed border-border py-3 text-sm font-medium text-muted-foreground transition hover:border-brand/50 hover:bg-muted/40 hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand disabled:pointer-events-none disabled:opacity-50"
                    >
                        <PlusIcon className="size-4" />
                        {contacts.length > 0 ? t('addAnotherContact') : t('addContact')}
                    </motion.button>
                )}
            </AnimatePresence>
        </div>
    );
});

export default CompanyContactsField;
