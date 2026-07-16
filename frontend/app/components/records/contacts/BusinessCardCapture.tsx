'use client';

import {
    ArrowPathIcon,
    ArrowUpTrayIcon,
    CameraIcon,
    CheckCircleIcon,
    ExclamationTriangleIcon,
    PhotoIcon,
    XMarkIcon,
} from '@heroicons/react/24/outline';
import Image from 'next/image';
import { useTranslations } from 'next-intl';
import { type ChangeEvent, useEffect, useId, useLayoutEffect, useRef, useState } from 'react';

import type {
    BusinessCardRequestErrorKind,
    BusinessCardScanResult,
} from '@/app/lib/types';
import type {
    BusinessCardCompanyActionMode,
    BusinessCardCompanyValidationError,
    BusinessCardScanStatus,
} from '@/app/components/records/contacts/useBusinessCardCapture';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { isManagedImageFile, MANAGED_IMAGE_ACCEPT } from '@/app/lib/managed-image';
import { cn } from '@/lib/utils';

type ConfidenceField = 'name' | 'email' | 'phone' | 'title' | 'company';

type BusinessCardCaptureProps = {
    scanAvailable: boolean;
    file: File | null;
    previewUrl: string | null;
    result: BusinessCardScanResult | null;
    status: BusinessCardScanStatus;
    requestError: BusinessCardRequestErrorKind | null;
    importError: BusinessCardRequestErrorKind | null;
    requiresExactImportRetry: boolean;
    disabled: boolean;
    onFileSelected: (file: File) => void;
    onCancelScan: () => void;
    onRetryScan: () => void;
    onSelectionPendingChange: (pending: boolean) => void;
    onRemove?: () => void;
    onDiscardImage?: () => void;
};

type BusinessCardCompanyChoiceProps = {
    active: boolean;
    canCreateCompany: boolean;
    mode: BusinessCardCompanyActionMode;
    existingCompanyName: string | null;
    companyName: string;
    validationError: BusinessCardCompanyValidationError;
    fieldError?: string;
    disabled: boolean;
    onModeChange: (mode: Exclude<BusinessCardCompanyActionMode, null>) => void;
    onCompanyNameChange: (value: string) => void;
};

function confidencePercent(confidence: number): number {
    return Math.round(Math.max(0, Math.min(1, confidence)) * 100);
}

function isDefinitiveImageRejection(kind: BusinessCardRequestErrorKind): boolean {
    return kind === 'tooLarge' || kind === 'unsupportedType' || kind === 'unreadable';
}

function confidenceItems(result: BusinessCardScanResult): Array<{ field: ConfidenceField; confidence: number }> {
    const items: Array<{ field: ConfidenceField; confidence: number }> = [];
    const candidates: Array<{
        field: ConfidenceField;
        value?: string | null;
        confidence?: number | null;
    }> = [
        { field: 'name', ...result.fields.name },
        { field: 'email', ...result.fields.email },
        { field: 'phone', ...result.fields.phone },
        { field: 'title', ...result.fields.title },
        { field: 'company', ...result.company },
    ];
    for (const candidate of candidates) {
        if (candidate.value && candidate.confidence != null) {
            items.push({ field: candidate.field, confidence: candidate.confidence });
        }
    }
    return items;
}

function importErrorMessageKey(kind: BusinessCardRequestErrorKind, canDiscardImage: boolean) {
    switch (kind) {
        case 'unauthorized':
            return 'cardImportErrorUnauthorized';
        case 'forbidden':
            return 'cardImportErrorForbidden';
        case 'tooLarge':
            return canDiscardImage ? 'cardImportErrorTooLarge' : 'cardImportErrorTooLargeRequired';
        case 'unsupportedType':
        case 'unreadable':
            return canDiscardImage ? 'cardImportErrorImage' : 'cardImportErrorImageRequired';
        case 'busy':
            return 'cardImportErrorBusy';
        case 'conflict':
            return 'cardImportErrorConflict';
        case 'gone':
            return 'cardImportRecoveryGone';
        case 'timeout':
        case 'unavailable':
            return 'cardImportErrorUnavailable';
        case 'recoveryStorage':
            return 'cardImportErrorRecoveryStorage';
        case 'rejected':
            return 'cardImportErrorRejected';
        case 'failed':
        case 'aborted':
            return 'cardImportErrorFailed';
    }
}

function scanErrorMessageKey(kind: BusinessCardRequestErrorKind) {
    switch (kind) {
        case 'unauthorized':
            return 'cardErrorUnauthorized';
        case 'forbidden':
            return 'cardErrorForbidden';
        case 'tooLarge':
            return 'cardErrorTooLarge';
        case 'unsupportedType':
            return 'cardErrorUnsupportedType';
        case 'unreadable':
            return 'cardErrorUnreadable';
        case 'busy':
            return 'cardErrorBusy';
        case 'conflict':
        case 'gone':
            return 'cardErrorRejected';
        case 'timeout':
            return 'cardErrorTimeout';
        case 'unavailable':
            return 'cardErrorUnavailable';
        case 'recoveryStorage':
            return 'cardErrorUnavailable';
        case 'rejected':
            return 'cardErrorRejected';
        case 'failed':
            return 'cardErrorFailed';
        case 'aborted':
            return 'cardManualFallback';
    }
}

function warningMessageKey(warning: string) {
    const normalizedWarning = warning.trim().toLowerCase().replaceAll('-', '_');
    if (normalizedWarning.startsWith('low_confidence')) {
        return 'cardWarningLowConfidence';
    }
    switch (normalizedWarning) {
        case 'partial_result':
            return 'cardWarningPartial';
        case 'unsupported_orientation':
            return 'cardWarningOrientation';
        case 'no_recognizable_fields':
            return 'cardWarningNoFields';
        default:
            return 'cardWarningReview';
    }
}

function confidenceLabelKey(field: ConfidenceField) {
    switch (field) {
        case 'name':
            return 'cardConfidenceName';
        case 'email':
            return 'cardConfidenceEmail';
        case 'phone':
            return 'cardConfidencePhone';
        case 'title':
            return 'cardConfidenceTitle';
        case 'company':
            return 'cardConfidenceCompany';
    }
}

export function BusinessCardCapture({
    scanAvailable,
    file,
    previewUrl,
    result,
    status,
    requestError,
    importError,
    requiresExactImportRetry,
    disabled,
    onFileSelected,
    onCancelScan,
    onRetryScan,
    onSelectionPendingChange,
    onRemove,
    onDiscardImage,
}: BusinessCardCaptureProps) {
    const t = useTranslations('ContactsNewContactDialog');
    const cameraInputId = useId();
    const uploadInputId = useId();
    const cameraInputRef = useRef<HTMLInputElement>(null);
    const uploadInputRef = useRef<HTMLInputElement>(null);
    const selectionSequenceRef = useRef(0);
    const activeRef = useRef(true);
    const onSelectionPendingChangeRef = useRef(onSelectionPendingChange);
    const [selectionError, setSelectionError] = useState(false);
    const [selectionPending, setSelectionPending] = useState(false);
    const cardControlsDisabled = disabled || requiresExactImportRetry || selectionPending;

    useLayoutEffect(() => {
        onSelectionPendingChangeRef.current = onSelectionPendingChange;
    });

    useEffect(() => () => {
        activeRef.current = false;
        selectionSequenceRef.current += 1;
        onSelectionPendingChangeRef.current(false);
    }, []);

    const setPending = (pending: boolean) => {
        setSelectionPending(pending);
        onSelectionPendingChangeRef.current(pending);
    };

    const handleFileChange = async (event: ChangeEvent<HTMLInputElement>) => {
        const selectionSequence = selectionSequenceRef.current + 1;
        selectionSequenceRef.current = selectionSequence;
        const selectedFile = event.currentTarget.files?.[0];
        event.currentTarget.value = '';
        if (!selectedFile) return;
        setPending(true);
        try {
            const supported = await isManagedImageFile(selectedFile);
            if (!activeRef.current || selectionSequence !== selectionSequenceRef.current) return;
            if (!supported) {
                setSelectionError(true);
                return;
            }
            setSelectionError(false);
            onFileSelected(selectedFile);
        } finally {
            if (activeRef.current && selectionSequence === selectionSequenceRef.current) {
                setPending(false);
            }
        }
    };

    const handleRemove = () => {
        selectionSequenceRef.current += 1;
        setPending(false);
        setSelectionError(false);
        onRemove?.();
    };

    const confidences = result ? confidenceItems(result) : [];
    const warnings = result
        ? [...new Set(result.warnings.map(warningMessageKey))]
        : [];

    return (
        <section
            className="grid gap-3 rounded-xl border bg-muted/30 p-3"
            aria-labelledby={`${cameraInputId}-title`}
        >
            <div className="grid gap-1">
                <h3 id={`${cameraInputId}-title`} className="text-sm font-medium">{t('businessCard')}</h3>
                <p className="text-xs leading-relaxed text-muted-foreground">{t('businessCardDescription')}</p>
            </div>

            <div className="grid grid-cols-1 gap-2">
                <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    className="hidden pointer-coarse:inline-flex"
                    disabled={cardControlsDisabled}
                    onClick={() => cameraInputRef.current?.click()}
                >
                    <CameraIcon data-icon="inline-start" />
                    {file ? t('scanAnotherCard') : t('scanCard')}
                </Button>
                <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    disabled={cardControlsDisabled}
                    onClick={() => uploadInputRef.current?.click()}
                >
                    <ArrowUpTrayIcon data-icon="inline-start" />
                    {file ? t('chooseAnotherCard') : t('uploadCard')}
                </Button>
                <input
                    ref={cameraInputRef}
                    id={cameraInputId}
                    type="file"
                    accept={MANAGED_IMAGE_ACCEPT}
                    capture="environment"
                    disabled={cardControlsDisabled}
                    onChange={handleFileChange}
                    hidden
                />
                <input
                    ref={uploadInputRef}
                    id={uploadInputId}
                    type="file"
                    accept={MANAGED_IMAGE_ACCEPT}
                    disabled={cardControlsDisabled}
                    onChange={handleFileChange}
                    hidden
                />
            </div>

            {selectionError && (
                <p className="text-xs leading-relaxed text-destructive" role="alert">
                    {t('cardSelectionUnsupported')}
                </p>
            )}

            {file && (
                <div className="grid min-w-0 gap-2 rounded-lg bg-background p-2 ring-1 ring-border">
                    <div className="relative aspect-[8/5] w-full overflow-hidden rounded-md bg-muted">
                        {previewUrl ? (
                            <Image
                                src={previewUrl}
                                alt={t('cardPreviewAlt')}
                                fill
                                sizes="(max-width: 640px) calc(100vw - 5rem), 32rem"
                                unoptimized
                                className="object-contain"
                            />
                        ) : (
                            <PhotoIcon className="absolute inset-0 m-auto size-6 text-muted-foreground" aria-hidden="true" />
                        )}
                    </div>
                    <div className="flex min-w-0 items-start gap-2">
                        <div className="min-w-0 flex-1 py-0.5">
                            <p className="truncate text-sm font-medium">{file.name}</p>
                            <div className="mt-1 flex items-start gap-1.5 text-xs leading-relaxed" role="status" aria-live="polite">
                                {status === 'scanning' && <ArrowPathIcon className="mt-0.5 size-3.5 shrink-0 animate-spin text-brand-dark motion-reduce:animate-none" aria-hidden="true" />}
                                {status === 'ready' && <CheckCircleIcon className="mt-0.5 size-3.5 shrink-0 text-brand-dark" aria-hidden="true" />}
                                {(status === 'manual' || status === 'error') && <ExclamationTriangleIcon className="mt-0.5 size-3.5 shrink-0 text-muted-foreground" aria-hidden="true" />}
                                <span className={cn(requestError ? 'text-destructive' : 'text-muted-foreground')}>
                                    {status === 'scanning' && t('cardScanning')}
                                    {status === 'ready' && t('cardScanReady')}
                                    {status === 'manual' && t(scanAvailable ? 'cardManualFallback' : 'cardManualOnly')}
                                    {status === 'error' && requestError && t(scanErrorMessageKey(requestError))}
                                </span>
                            </div>
                            {confidences.length > 0 && (
                                <ul className="mt-2 flex flex-wrap gap-x-3 gap-y-1 text-xs text-muted-foreground" aria-label={t('cardConfidenceSummary')}>
                                    {confidences.map(({ field, confidence }) => (
                                        <li key={field}>
                                            {t(confidenceLabelKey(field))} {confidencePercent(confidence)}%
                                        </li>
                                    ))}
                                </ul>
                            )}
                        </div>
                        {onRemove && (
                            <Button
                                type="button"
                                variant="ghost"
                                size="icon-xs"
                                disabled={cardControlsDisabled}
                                onClick={handleRemove}
                                aria-label={t('removeCard')}
                            >
                                <XMarkIcon />
                            </Button>
                        )}
                    </div>
                </div>
            )}

            {status === 'scanning' && (
                <Button type="button" variant="ghost" size="xs" className="w-fit" onClick={onCancelScan}>
                    {t('cancelCardScan')}
                </Button>
            )}

            {(status === 'manual' || status === 'error') && file && (
                <Button type="button" variant="ghost" size="xs" className="w-fit" disabled={cardControlsDisabled} onClick={onRetryScan}>
                    <ArrowPathIcon data-icon="inline-start" />
                    {t(scanAvailable ? 'retryCardScan' : 'checkCardScanner')}
                </Button>
            )}

            {warnings.length > 0 && (
                <ul className="grid gap-1 text-xs text-muted-foreground" aria-label={t('cardWarnings')}>
                    {warnings.map((warning) => <li key={warning}>{t(warning)}</li>)}
                </ul>
            )}

            {importError && (
                <div className="grid gap-2">
                    <p className="text-xs leading-relaxed text-destructive" role="alert">
                        {t(importErrorMessageKey(importError, onDiscardImage != null))}
                    </p>
                    {onDiscardImage && !requiresExactImportRetry && isDefinitiveImageRejection(importError) && (
                        <Button
                            type="button"
                            variant="outline"
                            size="xs"
                            className="w-fit"
                            disabled={disabled}
                            onClick={() => {
                                selectionSequenceRef.current += 1;
                                setPending(false);
                                setSelectionError(false);
                                onDiscardImage();
                            }}
                        >
                            {t('continueWithoutCardImage')}
                        </Button>
                    )}
                </div>
            )}
        </section>
    );
}

export function BusinessCardCompanyChoice({
    active,
    canCreateCompany,
    mode,
    existingCompanyName,
    companyName,
    validationError,
    fieldError,
    disabled,
    onModeChange,
    onCompanyNameChange,
}: BusinessCardCompanyChoiceProps) {
    const t = useTranslations('ContactsNewContactDialog');
    const groupId = useId();

    if (!active) return null;

    return (
        <fieldset
            id={`${groupId}-group`}
            className={cn('mt-2 grid gap-2 rounded-lg border p-3', validationError && 'border-destructive')}
            aria-describedby={validationError ? `${groupId}-error` : undefined}
        >
            <legend className="px-1 text-sm font-medium">{t('cardCompanyChoice')}</legend>
            <label className={cn('flex items-start gap-2 text-sm', !existingCompanyName && 'text-muted-foreground')}>
                <input
                    type="radio"
                    name={groupId}
                    value="existing"
                    checked={mode === 'existing'}
                    disabled={disabled || !existingCompanyName}
                    onChange={() => onModeChange('existing')}
                    className="mt-0.5 size-4 accent-brand"
                />
                <span>{existingCompanyName ? t('cardUseExistingCompany', { name: existingCompanyName }) : t('cardSelectExistingCompany')}</span>
            </label>
            {canCreateCompany && (
                <>
                    <label className="flex items-start gap-2 text-sm">
                        <input
                            type="radio"
                            name={groupId}
                            value="create"
                            checked={mode === 'create'}
                            disabled={disabled}
                            onChange={() => onModeChange('create')}
                            className="mt-0.5 size-4 accent-brand"
                        />
                        <span>{t('cardCreateCompany')}</span>
                    </label>
                    {mode === 'create' && (
                        <div className="ml-6 grid gap-1.5">
                            <Label htmlFor="companyName">{t('cardCompanyName')}</Label>
                            <Input
                                id="companyName"
                                value={companyName}
                                disabled={disabled}
                                maxLength={255}
                                onChange={(event) => onCompanyNameChange(event.target.value)}
                                aria-invalid={validationError === 'companyName' || Boolean(fieldError)}
                                aria-describedby={fieldError ? 'companyName-error' : undefined}
                                className={cn((validationError === 'companyName' || fieldError) && 'border-destructive ring-destructive/30')}
                            />
                            {fieldError && (
                                <p id="companyName-error" className="text-xs text-destructive">{fieldError}</p>
                            )}
                        </div>
                    )}
                </>
            )}
            <label className="flex items-start gap-2 text-sm">
                <input
                    type="radio"
                    name={groupId}
                    value="none"
                    checked={mode === 'none'}
                    disabled={disabled}
                    onChange={() => onModeChange('none')}
                    className="mt-0.5 size-4 accent-brand"
                />
                <span>{t('cardNoCompany')}</span>
            </label>
            {validationError && (
                <p id={`${groupId}-error`} className="text-xs text-destructive" role="alert">
                    {validationError === 'companyName' ? t('cardCompanyNameRequired') : t('cardCompanyChoiceRequired')}
                </p>
            )}
        </fieldset>
    );
}
