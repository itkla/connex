'use client';

import { ResponsiveDialog, ResponsiveDialogContent, ResponsiveDialogTitle, ResponsiveDialogDescription } from '@/components/ui/responsive-dialog';
import { Button } from '@/components/ui/button';
import Image from 'next/image';
import { Combobox, ComboboxItem, ComboboxList, ComboboxContent, ComboboxEmpty, ComboboxInput } from '@/components/ui/combobox';
import { InputGroupAddon } from '@/components/ui/input-group';
import { Label } from '@/components/ui/label';
import { cn } from '@/lib/utils';
import {
    type BusinessCardImportDraft,
    type BusinessCardImportResult,
    type Company,
    type CreateContactPayload,
} from '@/app/lib/types';
import {
    ChangeEvent,
    Dispatch,
    type FormEvent,
    SetStateAction,
    useEffect,
    useRef,
    useState,
    type WheelEvent,
} from 'react';
import {
    CameraIcon,
    UserIcon,
    EnvelopeIcon,
    PhoneIcon,
    BriefcaseIcon,
    BuildingOffice2Icon,
    ArrowPathIcon,
} from '@heroicons/react/24/outline';
import { useTranslations } from 'next-intl';
import { useRouter } from 'next/navigation';
import { initials } from '@/app/lib/utils';
import { isFieldError } from '@/app/lib/api';
import { useFieldErrors } from '@/app/hooks/useFieldErrors';
import { useCompanySearch } from '@/app/hooks/useCompanySearch';
import { isManagedImageFile, MANAGED_IMAGE_ACCEPT } from '@/app/lib/managed-image';
import { toastError, toastSuccess } from '@/app/lib/toast';
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
    onRecoveredImport?: (result: BusinessCardImportResult) => void;
    onImportRetryRequiredChange?: (required: boolean) => void;
    onSubmissionPendingChange?: (pending: boolean) => void;
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
    onRecoveredImport,
    onImportRetryRequiredChange,
    onSubmissionPendingChange,
}: Props) {
    const t = useTranslations('ContactsNewContactDialog');
    const [importRetryRequired, setImportRetryRequired] = useState(false);
    const [submissionPending, setSubmissionPending] = useState(false);
    const submissionPendingRef = useRef(false);
    const importRetryRequiredRef = useRef(false);
    const [previousOpen, setPreviousOpen] = useState(newContactDialogOpen);

    if (newContactDialogOpen !== previousOpen) {
        setPreviousOpen(newContactDialogOpen);
        if (!newContactDialogOpen) {
            setImportRetryRequired(false);
            setSubmissionPending(false);
        }
    }

    useEffect(() => {
        if (newContactDialogOpen) return;
        importRetryRequiredRef.current = false;
        submissionPendingRef.current = false;
    }, [newContactDialogOpen]);

    const handleOpenChange = (next: boolean) => {
        if (!next && (
            submissionPendingRef.current
            || importRetryRequiredRef.current
            || isCreating
        )) return;
        setNewContactDialogOpen(next);
    };

    const handleSubmissionPendingChange = (pending: boolean) => {
        submissionPendingRef.current = pending;
        setSubmissionPending(pending);
        onSubmissionPendingChange?.(pending);
    };

    const handleImportRetryRequiredChange = (required: boolean) => {
        importRetryRequiredRef.current = required;
        setImportRetryRequired(required);
        onImportRetryRequiredChange?.(required);
    };

    return (
        <ResponsiveDialog open={newContactDialogOpen} onOpenChange={handleOpenChange}>
            <ResponsiveDialogContent
                className="max-h-[calc(100dvh-1rem)] gap-0 overflow-y-auto p-0 sm:max-w-xl"
                showCloseButton={!submissionPending && !isCreating && !importRetryRequired}
            >
                <ResponsiveDialogTitle className="sr-only">{t('dialogTitle')}</ResponsiveDialogTitle>
                <ResponsiveDialogDescription className="sr-only">{t('description')}</ResponsiveDialogDescription>
                <NewContactForm
                    active={newContactDialogOpen}
                    onCancel={() => handleOpenChange(false)}
                    newContactPayload={newContactPayload}
                    setNewContactPayload={setNewContactPayload}
                    imageFile={imageFile}
                    setImageFile={setImageFile}
                    selectedCompany={selectedCompany}
                    isCreating={isCreating}
                    isSuccess={isSuccess}
                    createNewContact={createNewContact}
                    onRecoveredImport={onRecoveredImport}
                    onImportRetryRequiredChange={handleImportRetryRequiredChange}
                    onSubmissionPendingChange={handleSubmissionPendingChange}
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
    onRecoveredImport?: (result: BusinessCardImportResult) => void;
    onImportRetryRequiredChange?: (required: boolean) => void;
    onSubmissionPendingChange?: (pending: boolean) => void;
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
    onRecoveredImport,
    onCancel,
    onImportRetryRequiredChange,
    onSubmissionPendingChange,
}: NewContactFormProps) {
    const t = useTranslations('ContactsNewContactDialog');
    const router = useRouter();
    const [imagePreview, setImagePreview] = useState<string | null>(null);
    const [submissionPending, setSubmissionPending] = useState(false);
    const [prevActive, setPrevActive] = useState(active);
    const recoveredImportHandledRef = useRef(false);
    const submissionPendingRef = useRef(false);
    const businessCard = useBusinessCardCapture({
        active,
        payload: newContactPayload,
        setPayload: setNewContactPayload,
        onImportRetryRequiredChange,
    });
    const recoveredImport = businessCard.recoveredImport;
    const acknowledgeRecoveredImport = businessCard.acknowledgeRecoveredImport;
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

    useEffect(() => {
        if (!recoveredImport || recoveredImportHandledRef.current) return;
        recoveredImportHandledRef.current = true;
        void (async () => {
            toastSuccess(t('cardImportRecovered'));
            onRecoveredImport?.(recoveredImport.result);
            await acknowledgeRecoveredImport();
            onCancel();
            router.refresh();
        })().catch(() => {
            recoveredImportHandledRef.current = false;
            toastError(t('cardImportRecoveryFailed'));
        });
    }, [acknowledgeRecoveredImport, onCancel, onRecoveredImport, recoveredImport, router, t]);

    const handleCreate = async () => {
        resetFieldErrors();
        let businessCardImport: BusinessCardImportDraft | undefined;
        try {
            businessCardImport = await businessCard.prepareImportDraft();
            if (businessCard.file && !businessCardImport) return;
            const outcome = await createNewContact(businessCardImport);
            businessCard.resolveImportRetry();
            if (outcome?.avatarUploadFailed) {
                toastError(t('cardAvatarUploadFailed'));
            }
        } catch (err) {
            captureFieldErrors(err);
            if (isFieldError(err)) {
                businessCard.resolveImportRetry();
                const k = Object.keys(err.fieldErrors)[0];
                if (k) requestAnimationFrame(() => document.getElementById(k)?.focus());
            } else if (businessCardImport) {
                businessCard.captureImportError(err);
            }
        }
    };

    const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
        event.preventDefault();
        if (submissionPendingRef.current
            || isCreating
            || businessCard.isScanning
            || businessCard.recoveryStatus !== 'ready') return;
        submissionPendingRef.current = true;
        setSubmissionPending(true);
        onSubmissionPendingChange?.(true);
        try {
            await handleCreate();
        } finally {
            submissionPendingRef.current = false;
            setSubmissionPending(false);
            onSubmissionPendingChange?.(false);
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
            setSubmissionPending(false);
            resetFieldErrors();
        }
    }

    useEffect(() => {
        if (active) return;
        recoveredImportHandledRef.current = false;
        submissionPendingRef.current = false;
    }, [active]);

    useEffect(() => {
        return () => {
            if (imagePreview) URL.revokeObjectURL(imagePreview);
        };
    }, [imagePreview]);

    const handleImageChange = (e: ChangeEvent<HTMLInputElement>) => {
        const file = e.target.files?.[0];
        e.target.value = '';
        if (submissionPendingRef.current || isCreating || businessCard.requiresExactImportRetry) return;
        if (!file) return;
        if (!isManagedImageFile(file)) {
            toastError(t('cardSelectionUnsupported'));
            return;
        }
        if (imagePreview) URL.revokeObjectURL(imagePreview);
        setImagePreview(URL.createObjectURL(file));
        setImageFile(file);
    };

    const hasErrors = Object.keys(fieldErrors).length > 0
        || businessCard.companyValidationError != null
        || businessCard.importError != null
        || businessCard.recoveryStatus === 'error';
    const formPending = submissionPending || isCreating;
    const recoveryBlocked = businessCard.recoveryStatus !== 'ready';
    const status = resolveDialogStatus({ isLoading: formPending, hasErrors, isSuccess });
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
                            accept={MANAGED_IMAGE_ACCEPT}
                            disabled={formPending || recoveryBlocked || businessCard.requiresExactImportRetry}
                            onChange={handleImageChange}
                            className="sr-only"
                        />
                    </label>
                </div>

                <div className="ncd-rise mb-5 flex flex-col gap-2" style={{ animationDelay: '40ms' }}>
                    <h2 className="font-heading text-xl font-semibold leading-none tracking-tight">{t('dialogTitle')}</h2>
                    <p className="text-sm text-muted-foreground">{t('description')}</p>
                </div>

                <form onSubmit={handleSubmit} className="grid gap-5">
                    {businessCard.recoveryStatus === 'checking' ? (
                        <BusinessCardRecoveryStatus />
                    ) : businessCard.recoveryStatus === 'error' ? (
                        <BusinessCardRecoveryError onRetry={businessCard.retryRecovery} />
                    ) : !businessCard.availabilityResolved ? (
                        <BusinessCardAvailabilityPlaceholder />
                    ) : businessCard.available ? (
                        <BusinessCardCapture
                            scanAvailable={businessCard.scanAvailable}
                            file={businessCard.file}
                            previewUrl={businessCard.previewUrl}
                            result={businessCard.result}
                            status={businessCard.status}
                            requestError={businessCard.requestError}
                            importError={businessCard.importError}
                            requiresExactImportRetry={businessCard.requiresExactImportRetry}
                            disabled={formPending}
                            onFileSelected={businessCard.selectFile}
                            onCancelScan={businessCard.cancelScan}
                            onRetryScan={businessCard.retryScan}
                            onRemove={businessCard.removeCard}
                            onDiscardImage={businessCard.companyMode === 'create'
                                ? undefined
                                : businessCard.discardCardImage}
                        />
                    ) : null}

                    <ContactDetailsFields
                        payload={newContactPayload}
                        businessCard={businessCard}
                        fieldErrors={fieldErrors}
                        clearError={clearError}
                        companySearch={companySearch}
                        resolvedCompany={resolvedCompany}
                        matchedCompanyName={matchedCompanyName}
                        isCreating={formPending || recoveryBlocked}
                        onListWheel={handleListWheel}
                    />

                    <div className="ncd-rise mt-5 flex flex-col-reverse gap-2 sm:flex-row sm:justify-end" style={{ animationDelay: '290ms' }}>
                        <Button
                            type="button"
                            variant="outline"
                            disabled={formPending}
                            onClick={() => {
                                if (submissionPendingRef.current) return;
                                if (businessCard.requiresExactImportRetry) {
                                    businessCard.deferImportRetry();
                                }
                                onCancel();
                            }}
                        >
                            {t(businessCard.requiresExactImportRetry ? 'cardImportCloseAndReconcile' : 'cancel')}
                        </Button>
                        <Button
                            type="submit"
                            variant="brand"
                            disabled={formPending || recoveryBlocked || isSuccess || businessCard.isScanning}
                            className="min-w-24 shadow-sm transition hover:shadow-md"
                        >
                            {formPending ? <ArrowPathIcon className="size-4 animate-spin motion-reduce:animate-none" /> : businessCard.file ? t('createFromCard') : t('create')}
                        </Button>
                    </div>
                </form>
            </div>
        </>
    );
}

function BusinessCardRecoveryStatus() {
    const t = useTranslations('ContactsNewContactDialog');

    return (
        <section className="grid gap-3 rounded-xl border bg-muted/30 p-3">
            <p className="flex items-center gap-2 text-xs text-muted-foreground" role="status">
                <ArrowPathIcon className="size-3.5 animate-spin motion-reduce:animate-none" aria-hidden="true" />
                {t('cardImportRecoveryChecking')}
            </p>
        </section>
    );
}

function BusinessCardRecoveryError({ onRetry }: { onRetry: () => void }) {
    const t = useTranslations('ContactsNewContactDialog');

    return (
        <section className="grid gap-3 rounded-xl border border-destructive/40 bg-destructive/5 p-3">
            <p className="text-xs leading-relaxed text-destructive" role="alert">
                {t('cardImportRecoveryFailed')}
            </p>
            <Button type="button" variant="outline" size="sm" className="w-fit" onClick={onRetry}>
                <ArrowPathIcon data-icon="inline-start" />
                {t('cardImportRecoveryRetry')}
            </Button>
        </section>
    );
}

function BusinessCardAvailabilityPlaceholder() {
    const t = useTranslations('ContactsNewContactDialog');

    return (
        <section className="grid gap-3 rounded-xl border bg-muted/30 p-3">
            <div className="grid gap-1">
                <h3 className="text-sm font-medium">{t('businessCard')}</h3>
                <p className="text-xs leading-relaxed text-muted-foreground">{t('businessCardDescription')}</p>
            </div>
            <p className="flex items-center gap-2 text-xs text-muted-foreground" role="status">
                <ArrowPathIcon className="size-3.5 animate-spin motion-reduce:animate-none" aria-hidden="true" />
                {t('cardAvailabilityLoading')}
            </p>
        </section>
    );
}

type ContactDetailsFieldsProps = {
    payload: CreateContactPayload;
    businessCard: ReturnType<typeof useBusinessCardCapture>;
    fieldErrors: ReturnType<typeof useFieldErrors>['fieldErrors'];
    clearError: ReturnType<typeof useFieldErrors>['clearError'];
    companySearch: ReturnType<typeof useCompanySearch>;
    resolvedCompany: Company | null;
    matchedCompanyName: string | null;
    isCreating: boolean;
    onListWheel: (event: WheelEvent<HTMLDivElement>) => void;
};

function ContactDetailsFields({
    payload,
    businessCard,
    fieldErrors,
    clearError,
    companySearch,
    resolvedCompany,
    matchedCompanyName,
    isCreating,
    onListWheel,
}: ContactDetailsFieldsProps) {
    const t = useTranslations('ContactsNewContactDialog');
    const disabled = isCreating || businessCard.requiresExactImportRetry;

    return (
        <>
            <div className="ncd-rise grid gap-1.5" style={{ animationDelay: '90ms' }}>
                <Label htmlFor="name">{t('name')}</Label>
                <div className="group relative">
                    <UserIcon className={fieldLeadIconClass} />
                    <input
                        id="name"
                        type="text"
                        autoFocus
                        data-autofocus=""
                        value={payload.name}
                        disabled={disabled}
                        maxLength={255}
                        onChange={(event) => {
                            businessCard.updateContactField('name', event.target.value);
                            clearError('name');
                        }}
                        className={cn(fieldInputClass, 'pl-9 pr-3', fieldErrors.name && fieldErrorClass)}
                        placeholder={t('namePlaceholder')}
                        aria-invalid={Boolean(fieldErrors.name)}
                        aria-describedby={fieldErrors.name ? 'name-error' : undefined}
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
                        value={payload.email}
                        disabled={disabled}
                        maxLength={255}
                        onChange={(event) => {
                            businessCard.updateContactField('email', event.target.value);
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
                            value={payload.phone}
                            disabled={disabled}
                            maxLength={64}
                            onChange={(event) => {
                                businessCard.updateContactField('phone', event.target.value);
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
                            value={payload.title}
                            disabled={disabled}
                            maxLength={128}
                            onChange={(event) => {
                                businessCard.updateContactField('title', event.target.value);
                                clearError('title');
                            }}
                            className={cn(fieldInputClass, 'pl-9 pr-3', fieldErrors.title && fieldErrorClass)}
                            placeholder={t('titlePlaceholder')}
                            aria-invalid={Boolean(fieldErrors.title)}
                            aria-describedby={fieldErrors.title ? 'title-error' : undefined}
                        />
                    </div>
                    {fieldErrors.title && <p id="title-error" className="text-sm text-destructive">{fieldErrors.title}</p>}
                </div>
            </div>

            <div className="ncd-rise grid gap-1.5" style={{ animationDelay: '240ms' }}>
                <Label htmlFor="company">{t('company')}</Label>
                <Combobox
                    items={companySearch.companies}
                    disabled={disabled}
                    filter={null}
                    itemToStringLabel={(company: Company) => company.name}
                    value={resolvedCompany}
                    onInputValueChange={companySearch.onInputValueChange}
                    onValueChange={(company) => businessCard.selectExistingCompany(company?.id)}
                >
                    <ComboboxInput
                        id="company"
                        disabled={disabled}
                        placeholder={t('selectCompanyPlaceholder')}
                        className="rounded-lg border-0 bg-muted shadow-none ring-1 ring-border dark:bg-muted has-[[data-slot=input-group-control]:focus-visible]:ring-2 has-[[data-slot=input-group-control]:focus-visible]:ring-brand"
                    >
                        <InputGroupAddon align="inline-start">
                            <BuildingOffice2Icon className="size-4 text-muted-foreground transition-colors group-focus-within/input-group:text-brand" />
                        </InputGroupAddon>
                    </ComboboxInput>
                    <ComboboxContent className="pointer-events-auto">
                        <ComboboxList onWheel={onListWheel}>
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
                    canCreateCompany={businessCard.canCreateCompany}
                    mode={businessCard.companyMode}
                    existingCompanyName={resolvedCompany?.name ?? matchedCompanyName}
                    companyName={businessCard.companyName}
                    validationError={businessCard.companyValidationError}
                    fieldError={fieldErrors.companyName}
                    disabled={disabled}
                    onModeChange={businessCard.selectCompanyMode}
                    onCompanyNameChange={(value) => {
                        businessCard.updateCompanyName(value);
                        clearError('companyName');
                    }}
                />
            </div>
        </>
    );
}
