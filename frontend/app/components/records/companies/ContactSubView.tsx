'use client';

import { useEffect, useRef, useState, type KeyboardEvent } from 'react';
import { Button } from '@/components/ui/button';
import { Label } from '@/components/ui/label';
import { cn } from '@/lib/utils';
import { initials } from '@/app/lib/utils';
import { fieldErrorClass, fieldInputClass, fieldLeadIconClass } from '@/components/ui/dialog-status-cover';
import { QuickEditMediaUpload } from '@/app/components/records/quick-edit/QuickEditSheetShell';
import { ArrowLeftIcon, BriefcaseIcon, EnvelopeIcon, PhoneIcon, UserIcon } from '@heroicons/react/24/outline';
import { useTranslations } from 'next-intl';
import type { PendingContactDraft } from '@/app/components/records/companies/CompanyContactsField';
import { toastError } from '@/app/lib/toast';

const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

type FieldErrors = { name?: string; email?: string };

type Props = {
    mode: 'new' | 'edit';
    initial: PendingContactDraft;
    onDone: (draft: PendingContactDraft) => void;
    onBack: () => void;
    disabled?: boolean;
};

/**
 * Full-page contact form the create-company dialog slides to when staging or editing a person.
 * Collects name (required), email, title, phone, and an optional profile photo, then returns the
 * draft to the dialog on Done. Back discards.
 */
export default function ContactSubView({ mode, initial, onDone, onBack, disabled = false }: Props) {
    const t = useTranslations('CompaniesNewDialog');
    const [name, setName] = useState(initial.name);
    const [email, setEmail] = useState(initial.email);
    const [title, setTitle] = useState(initial.title);
    const [phone, setPhone] = useState(initial.phone);
    const [imageFile, setImageFile] = useState<File | null>(initial.imageFile);
    const [errors, setErrors] = useState<FieldErrors>({});
    const nameRef = useRef<HTMLInputElement>(null);

    useEffect(() => {
        const id = requestAnimationFrame(() => nameRef.current?.focus());
        return () => cancelAnimationFrame(id);
    }, []);

    const submit = () => {
        const next: FieldErrors = {};
        if (!name.trim()) next.name = t('contactNameRequired');
        else if (email.trim() && !EMAIL_PATTERN.test(email.trim())) next.email = t('contactEmailInvalid');
        if (next.name || next.email) {
            setErrors(next);
            return;
        }
        onDone({ name: name.trim(), email: email.trim(), title: title.trim(), phone: phone.trim(), imageFile });
    };

    const handleKeyDown = (event: KeyboardEvent<HTMLInputElement>) => {
        if (event.key === 'Enter') {
            event.preventDefault();
            submit();
        } else if (event.key === 'Escape') {
            event.preventDefault();
            onBack();
        }
    };

    const fallbackInitials = initials(name || '');

    return (
        <div className="flex max-h-[85dvh] flex-col">
            <div className="flex items-center gap-2 px-6 pb-2 pt-5">
                <button
                    type="button"
                    onClick={onBack}
                    disabled={disabled}
                    aria-label={t('contactBack')}
                    className="-ml-1.5 flex size-9 shrink-0 items-center justify-center rounded-lg text-muted-foreground transition hover:bg-muted hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-brand active:scale-95 disabled:pointer-events-none disabled:opacity-50"
                >
                    <ArrowLeftIcon className="size-5" />
                </button>
                <h2 className="text-lg font-semibold tracking-tight text-foreground">
                    {mode === 'edit' ? t('contactViewEditTitle') : t('contactViewAddTitle')}
                </h2>
            </div>

            <div className="flex min-h-0 flex-1 flex-col gap-5 overflow-y-auto px-6 py-4">
            <div className="flex items-center gap-4">
                <QuickEditMediaUpload
                    id="pending-contact-photo"
                    label={t('contactPhotoLabel')}
                    shape="round"
                    file={imageFile}
                    existingUrl={null}
                    fallback={
                        fallbackInitials ? (
                            <span className="select-none text-base font-semibold text-muted-foreground">{fallbackInitials}</span>
                        ) : (
                            <UserIcon className="size-6 text-muted-foreground" />
                        )
                    }
                    onSelect={setImageFile}
                    onInvalidSelect={() => toastError(t('contactPhotoUnsupported'))}
                />
                <p className="text-sm text-muted-foreground">{t('contactPhotoHint')}</p>
            </div>

            <div className="grid gap-4">
                <div className="grid gap-1.5">
                    <Label htmlFor="pending-contact-name">
                        {t('contactNameLabel')} <span className="text-muted-foreground">*</span>
                    </Label>
                    <div className="group relative">
                        <UserIcon className={fieldLeadIconClass} />
                        <input
                            ref={nameRef}
                            id="pending-contact-name"
                            type="text"
                            value={name}
                            onChange={(e) => {
                                setName(e.target.value);
                                if (errors.name) setErrors((prev) => ({ ...prev, name: undefined }));
                            }}
                            onKeyDown={handleKeyDown}
                            className={cn(fieldInputClass, 'pl-9 pr-3', errors.name && fieldErrorClass)}
                            placeholder={t('contactNamePlaceholder')}
                            aria-required
                            aria-invalid={Boolean(errors.name)}
                            aria-describedby={errors.name ? 'pending-contact-name-error' : undefined}
                        />
                    </div>
                    {errors.name && (
                        <p id="pending-contact-name-error" className="text-sm text-destructive">
                            {errors.name}
                        </p>
                    )}
                </div>

                <div className="grid gap-1.5">
                    <Label htmlFor="pending-contact-email">{t('contactEmailLabel')}</Label>
                    <div className="group relative">
                        <EnvelopeIcon className={fieldLeadIconClass} />
                        <input
                            id="pending-contact-email"
                            type="email"
                            value={email}
                            onChange={(e) => {
                                setEmail(e.target.value);
                                if (errors.email) setErrors((prev) => ({ ...prev, email: undefined }));
                            }}
                            onKeyDown={handleKeyDown}
                            className={cn(fieldInputClass, 'pl-9 pr-3', errors.email && fieldErrorClass)}
                            placeholder={t('contactEmailPlaceholder')}
                            aria-invalid={Boolean(errors.email)}
                            aria-describedby={errors.email ? 'pending-contact-email-error' : undefined}
                        />
                    </div>
                    {errors.email && (
                        <p id="pending-contact-email-error" className="text-sm text-destructive">
                            {errors.email}
                        </p>
                    )}
                </div>

                <div className="grid grid-cols-2 gap-3">
                    <div className="grid gap-1.5">
                        <Label htmlFor="pending-contact-title">{t('contactTitleLabel')}</Label>
                        <div className="group relative">
                            <BriefcaseIcon className={fieldLeadIconClass} />
                            <input
                                id="pending-contact-title"
                                type="text"
                                value={title}
                                onChange={(e) => setTitle(e.target.value)}
                                onKeyDown={handleKeyDown}
                                className={cn(fieldInputClass, 'pl-9 pr-3')}
                                placeholder={t('contactTitlePlaceholder')}
                            />
                        </div>
                    </div>
                    <div className="grid gap-1.5">
                        <Label htmlFor="pending-contact-phone">{t('contactPhoneLabel')}</Label>
                        <div className="group relative">
                            <PhoneIcon className={fieldLeadIconClass} />
                            <input
                                id="pending-contact-phone"
                                type="tel"
                                value={phone}
                                onChange={(e) => setPhone(e.target.value)}
                                onKeyDown={handleKeyDown}
                                className={cn(fieldInputClass, 'pl-9 pr-3')}
                                placeholder={t('contactPhonePlaceholder')}
                            />
                        </div>
                    </div>
                </div>
            </div>
            </div>

            <div className="flex shrink-0 items-center justify-end border-t border-border/60 bg-popover px-6 py-4">
                <Button
                    type="button"
                    onClick={submit}
                    variant="brand"
                    disabled={disabled}
                    className="min-w-24 shadow-sm transition hover:shadow-md active:scale-[0.98]"
                >
                    {t('contactDone')}
                </Button>
            </div>
        </div>
    );
}
