'use client';

import { ResponsiveDialog, ResponsiveDialogContent, ResponsiveDialogTitle, ResponsiveDialogDescription } from '@/components/ui/responsive-dialog';
import { Button } from '@/components/ui/button';
import { Loader2Icon } from 'lucide-react';
import Image from 'next/image';
import { Combobox, ComboboxItem, ComboboxList, ComboboxContent, ComboboxEmpty, ComboboxInput } from '@/components/ui/combobox';
import { InputGroupAddon } from '@/components/ui/input-group';
import { Label } from '@/components/ui/label';
import { cn } from '@/lib/utils';
import { type BusinessCardImportDraft, type Company, type CreateContactPayload } from '@/app/lib/types';
import { ChangeEvent, Dispatch, FormEvent, SetStateAction, useEffect, useState, type WheelEvent } from 'react';
import {
    CameraIcon,
    UserIcon,
    EnvelopeIcon,
    PhoneIcon,
    BriefcaseIcon,
    BuildingOffice2Icon,
} from '@heroicons/react/24/outline';
import { useTranslations } from 'next-intl';
import { initials } from '@/app/lib/utils';
import { isFieldError } from '@/app/lib/api';
import { useFieldErrors } from '@/app/hooks/useFieldErrors';
import { useCompanySearch } from '@/app/hooks/useCompanySearch';
import { toastError } from '@/app/lib/toast';
import { DialogStatusCover, resolveDialogStatus, fieldInputClass, fieldErrorClass, fieldLeadIconClass } from '@/components/ui/dialog-status-cover';
import {
    BusinessCardCapture,
    BusinessCardCompanyChoice,
} from '@/app/components/records/contacts/BusinessCardCapture';
import { useBusinessCardCapture } from '@/app/components/records/contacts/useBusinessCardCapture';

type Props = {
    newContactDialogOpen: boolean;
    setNewContactDialogOpen: (open: boolean) => void;
    newContactPayload: CreateContactPayload;
    setNewContactPayload: Dispatch<SetStateAction<CreateContactPayload>>;
    imageFile: File | null;
    setImageFile: Dispatch<SetStateAction<File | null>>;
    selectedCompany?: Company | null;
    isCreating: boolean;
    isSuccess?: boolean;
    createNewContact: (businessCard?: BusinessCardImportDraft) => ContactCreationOutcome | Promise<ContactCreationOutcome>;
};

export type ContactCreationOutcome = { avatarUploadFailed: boolean } | void;

export default function NewContactDialog({
    newContactDialogOpen,
    setNewContactDialogOpen,
    newContactPayload,
    setNewContactPayload,
    imageFile,
    setImageFile,
    selectedCompany = null,
    isCreating,
    isSuccess = false,
    createNewContact,
}: Props) {
    const t = useTranslations('ContactsNewContactDialog');

    const handleOpenChange = (next: boolean) => {
        if (!next && isCreating) return;
        setNewContactDialogOpen(next);
    };

    return (
        <ResponsiveDialog open={newContactDialogOpen} onOpenChange={handleOpenChange}>
            <ResponsiveDialogContent className="gap-0 overflow-hidden p-0 sm:max-w-xl md:max-h-[calc(100dvh-2rem)] md:overflow-y-auto">
                <ResponsiveDialogTitle className="sr-only">{t('dialogTitle')}</ResponsiveDialogTitle>
                <ResponsiveDialogDescription className="sr-only">{t('description')}</ResponsiveDialogDescription>
                <NewContactForm
                    active={newContactDialogOpen}
                    onCancel={() => setNewContactDialogOpen(false)}
                    newContactPayload={newContactPayload}
                    setNewContactPayload={setNewContactPayload}
                    imageFile={imageFile}
                    setImageFile={setImageFile}
                    selectedCompany={selectedCompany}
                    isCreating={isCreating}
                    isSuccess={isSuccess}
                    createNewContact={createNewContact}
                />
            </ResponsiveDialogContent>
        </ResponsiveDialog>
    );
}

type NewContactFormProps = {
    /** Whether the surface is active; gates the company search the way `newContactDialogOpen` did in the dialog. */
    active: boolean;
    newContactPayload: CreateContactPayload;
    setNewContactPayload: Dispatch<SetStateAction<CreateContactPayload>>;
    imageFile: File | null;
    setImageFile: Dispatch<SetStateAction<File | null>>;
    selectedCompany?: Company | null;
    isCreating: boolean;
    isSuccess?: boolean;
    createNewContact: (businessCard?: BusinessCardImportDraft) => ContactCreationOutcome | Promise<ContactCreationOutcome>;
    /** Invoked by the Cancel button — closes the dialog, or steps back to the selector in the morphing launcher. */
    onCancel: () => void;
};

/**
 * The contact quick-create form body — free of any dialog/drawer shell so it can render inside the
 * standalone {@link NewContactDialog} (desktop dialog / mobile drawer) or embedded in the morphing
 * Quick Create drawer. All submit/data ownership stays with the caller; this is a controlled form.
 */
export function NewContactForm({
    active,
    newContactPayload,
    setNewContactPayload,
    imageFile,
    setImageFile,
    selectedCompany = null,
    isCreating,
    isSuccess = false,
    createNewContact,
    onCancel,
}: NewContactFormProps) {
    const t = useTranslations('ContactsNewContactDialog');
    const [imagePreview, setImagePreview] = useState<string | null>(null);
    const [prevActive, setPrevActive] = useState(active);
    const businessCard = useBusinessCardCapture({
        active,
        payload: newContactPayload,
        setPayload: setNewContactPayload,
    });
    const { fieldErrors, reset: resetFieldErrors, clearError, captureFieldErrors } = useFieldErrors();
    const companySearch = useCompanySearch(
        active,
        [newContactPayload.companyId],
        selectedCompany ? [selectedCompany] : [],
    );
    const resolvedCompany = companySearch.companies.find(
        (company) => company.id === newContactPayload.companyId,
    ) ?? (selectedCompany?.id === newContactPayload.companyId ? selectedCompany : null);
    const scanResult = businessCard.result;
    const matchedCompanyName = scanResult?.company.matchedCompanyId === newContactPayload.companyId
        ? scanResult?.company.value ?? null
        : null;

    useEffect(() => {
        if (companySearch.error) toastError(t('companySearchFailed'));
    }, [companySearch.error, t]);

    const handleCreate = async () => {
        resetFieldErrors();
        const businessCardImport = businessCard.prepareImportDraft();
        if (businessCard.file && !businessCardImport) return;
        try {
            const outcome = await createNewContact(businessCardImport);
            if (outcome?.avatarUploadFailed) {
                toastError(t('cardAvatarUploadFailed'));
            }
        } catch (err) {
            captureFieldErrors(err);
            if (isFieldError(err)) {
                const k = Object.keys(err.fieldErrors)[0];
                if (k) requestAnimationFrame(() => document.getElementById(k)?.focus());
            } else if (businessCardImport) {
                businessCard.captureImportError(err);
            }
        }
    };

    const handleListWheel = (e: WheelEvent<HTMLDivElement>) => {
        const lineHeightPx = 16;
        const delta = e.deltaMode === 1 ? e.deltaY * lineHeightPx : e.deltaY;
        e.currentTarget.scrollTop += delta;
    };

    if (active !== prevActive) {
        setPrevActive(active);
        if (!active) {
            setImagePreview(null);
            resetFieldErrors();
        }
    }

    useEffect(() => {
        return () => {
            if (imagePreview) URL.revokeObjectURL(imagePreview);
        };
    }, [imagePreview]);

    const handleImageChange = (e: ChangeEvent<HTMLInputElement>) => {
        const file = e.target.files?.[0];
        if (!file) return;
        if (imagePreview) URL.revokeObjectURL(imagePreview);
        setImagePreview(URL.createObjectURL(file));
        setImageFile(file);
    };

    const hasErrors = Object.keys(fieldErrors).length > 0
        || businessCard.companyValidationError != null
        || businessCard.importError != null;
    const status = resolveDialogStatus({ isLoading: isCreating, hasErrors, isSuccess });
    const contactInitial = initials(newContactPayload.name || '');
    const visibleImagePreview = imageFile ? imagePreview : null;

    return (
        <>
            <DialogStatusCover status={status} />

            <div className="px-6 pb-6">
                <div className="ncd-pop relative -mt-12 mb-4 w-fit">
                    <label
                        htmlFor="imageUrl"
                        className="group relative flex size-20 cursor-pointer items-center justify-center overflow-hidden rounded-full bg-muted shadow-lg ring-4 ring-popover transition hover:ring-brand"
                    >
                        {visibleImagePreview ? (
                            <Image src={visibleImagePreview} alt="" fill sizes="80px" unoptimized className="object-cover" />
                        ) : contactInitial ? (
                            <div className="flex size-full select-none items-center justify-center bg-brand-light text-2xl font-semibold text-brand-dark">
                                {contactInitial}
                            </div>
                        ) : (
                            <div className="flex size-full items-center justify-center bg-brand-light">
                                <UserIcon className="size-7 text-brand-dark/70 transition group-hover:text-brand-dark" />
                            </div>
                        )}

                        {(visibleImagePreview || contactInitial) && (
                            <div className="absolute inset-0 flex items-center justify-center bg-black/45 opacity-0 transition group-hover:opacity-100">
                                <CameraIcon className="size-5 text-white" />
                            </div>
                        )}

                        <input
                            id="imageUrl"
                            type="file"
                            accept="image/*"
                            onChange={handleImageChange}
                            className="sr-only"
                        />
                    </label>
                </div>

                <div className="ncd-rise mb-5 flex flex-col gap-2" style={{ animationDelay: '40ms' }}>
                    <h2 className="font-heading text-xl font-semibold leading-none tracking-tight">{t('dialogTitle')}</h2>
                    <p className="text-sm text-muted-foreground">{t('description')}</p>
                </div>

                <form
                    onSubmit={(e: FormEvent) => {
                        e.preventDefault();
                        if (isCreating || businessCard.isScanning) return;
                        handleCreate();
                    }}
                    className="grid gap-5"
                >
                    <BusinessCardCapture
                        available={businessCard.available}
                        file={businessCard.file}
                        previewUrl={businessCard.previewUrl}
                        result={businessCard.result}
                        status={businessCard.status}
                        requestError={businessCard.requestError}
                        importError={businessCard.importError}
                        disabled={isCreating}
                        onFileSelected={businessCard.selectFile}
                        onCancelScan={businessCard.cancelScan}
                        onRetryScan={businessCard.retryScan}
                        onRemove={businessCard.removeCard}
                    />

                    <div className="ncd-rise grid gap-1.5" style={{ animationDelay: '90ms' }}>
                        <Label htmlFor="name">{t('name')}</Label>
                        <div className="group relative">
                            <UserIcon className={fieldLeadIconClass} />
                            <input
                                id="name"
                                type="text"
                                value={newContactPayload.name}
                                onChange={(e) => {
                                    setNewContactPayload((prev) => ({ ...prev, name: e.target.value }));
                                    clearError('name');
                                }}
                                className={cn(fieldInputClass, 'pl-9 pr-3', fieldErrors.name && fieldErrorClass)}
                                placeholder={t('namePlaceholder')}
                                aria-invalid={Boolean(fieldErrors.name)}
                                aria-describedby={fieldErrors.name ? 'name-error' : undefined}
                                autoFocus
                                required
                            />
                        </div>
                        {fieldErrors.name && <p id="name-error" className="text-sm text-destructive">{fieldErrors.name}</p>}
                    </div>

                    <div className="ncd-rise grid gap-1.5" style={{ animationDelay: '140ms' }}>
                        <Label htmlFor="email">{t('email')}</Label>
                        <div className="group relative">
                            <EnvelopeIcon className={fieldLeadIconClass} />
                            <input
                                id="email"
                                type="email"
                                value={newContactPayload.email}
                                onChange={(e) => {
                                    setNewContactPayload((prev) => ({ ...prev, email: e.target.value }));
                                    clearError('email');
                                }}
                                className={cn(fieldInputClass, 'pl-9 pr-3', fieldErrors.email && fieldErrorClass)}
                                placeholder={t('emailPlaceholder')}
                                aria-invalid={Boolean(fieldErrors.email)}
                                aria-describedby={fieldErrors.email ? 'email-error' : undefined}
                            />
                        </div>
                        {fieldErrors.email && <p id="email-error" className="text-sm text-destructive">{fieldErrors.email}</p>}
                    </div>

                    <div className="ncd-rise grid grid-cols-1 gap-3 sm:grid-cols-2" style={{ animationDelay: '190ms' }}>
                        <div className="grid gap-1.5">
                            <Label htmlFor="phone">{t('phone')}</Label>
                            <div className="group relative">
                                <PhoneIcon className={fieldLeadIconClass} />
                                <input
                                    id="phone"
                                    type="tel"
                                    value={newContactPayload.phone}
                                    onChange={(e) => {
                                        setNewContactPayload((prev) => ({ ...prev, phone: e.target.value }));
                                        clearError('phone');
                                    }}
                                    className={cn(fieldInputClass, 'pl-9 pr-3', fieldErrors.phone && fieldErrorClass)}
                                    placeholder={t('phonePlaceholder')}
                                    aria-invalid={Boolean(fieldErrors.phone)}
                                    aria-describedby={fieldErrors.phone ? 'phone-error' : undefined}
                                />
                            </div>
                            {fieldErrors.phone && <p id="phone-error" className="text-sm text-destructive">{fieldErrors.phone}</p>}
                        </div>
                        <div className="grid gap-1.5">
                            <Label htmlFor="title">{t('title')}</Label>
                            <div className="group relative">
                                <BriefcaseIcon className={fieldLeadIconClass} />
                                <input
                                    id="title"
                                    type="text"
                                    value={newContactPayload.title}
                                    onChange={(e) => setNewContactPayload((prev) => ({ ...prev, title: e.target.value }))}
                                    className={cn(fieldInputClass, 'pl-9 pr-3')}
                                    placeholder={t('titlePlaceholder')}
                                />
                            </div>
                        </div>
                    </div>

                    <div className="ncd-rise grid gap-1.5" style={{ animationDelay: '240ms' }}>
                        <Label htmlFor="company">{t('company')}</Label>
                        <Combobox
                            items={companySearch.companies}
                            filter={null}
                            itemToStringLabel={(c: Company) => c.name}
                            value={resolvedCompany}
                            onInputValueChange={companySearch.onInputValueChange}
                            onValueChange={(company) => businessCard.selectExistingCompany(company?.id)}
                        >
                            <ComboboxInput
                                id="company"
                                placeholder={t('selectCompanyPlaceholder')}
                                className="rounded-lg border-0 bg-muted shadow-none ring-1 ring-border dark:bg-muted has-[[data-slot=input-group-control]:focus-visible]:ring-2 has-[[data-slot=input-group-control]:focus-visible]:ring-brand"
                            >
                                <InputGroupAddon align="inline-start">
                                    <BuildingOffice2Icon className="size-4 text-muted-foreground transition-colors group-focus-within/input-group:text-brand" />
                                </InputGroupAddon>
                            </ComboboxInput>
                            <ComboboxContent className="pointer-events-auto">
                                <ComboboxList onWheel={handleListWheel}>
                                    <ComboboxEmpty>{t('noCompaniesFound')}</ComboboxEmpty>
                                    {companySearch.companies.map((company) => (
                                        <ComboboxItem key={company.id} value={company}>
                                            {company.name}
                                        </ComboboxItem>
                                    ))}
                                </ComboboxList>
                            </ComboboxContent>
                        </Combobox>
                        <BusinessCardCompanyChoice
                            active={businessCard.file != null}
                            mode={businessCard.companyMode}
                            existingCompanyName={resolvedCompany?.name ?? matchedCompanyName}
                            companyName={businessCard.companyName}
                            validationError={businessCard.companyValidationError}
                            disabled={isCreating}
                            onModeChange={businessCard.selectCompanyMode}
                            onCompanyNameChange={businessCard.updateCompanyName}
                        />
                    </div>

                    <div className="ncd-rise mt-5 flex flex-col-reverse gap-2 sm:flex-row sm:justify-end" style={{ animationDelay: '290ms' }}>
                        <Button type="button" variant="outline" disabled={isCreating} onClick={onCancel}>{t('cancel')}</Button>
                        <Button
                            type="submit"
                            variant="brand"
                            disabled={isCreating || isSuccess || businessCard.isScanning}
                            className="min-w-24 shadow-sm transition hover:shadow-md"
                        >
                            {isCreating ? <Loader2Icon className="size-4 animate-spin" /> : businessCard.file ? t('createFromCard') : t('create')}
                        </Button>
                    </div>
                </form>
            </div>
        </>
    );
}
