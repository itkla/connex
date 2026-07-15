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
import { type ChangeEvent, useId, useRef } from 'react';

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
    available: boolean;
    scanAvailable: boolean;
    file: File | null;
    previewUrl: string | null;
    result: BusinessCardScanResult | null;
    status: BusinessCardScanStatus;
    requestError: BusinessCardRequestErrorKind | null;
    importError: BusinessCardRequestErrorKind | null;
    disabled: boolean;
    onFileSelected: (file: File) => void;
    onCancelScan: () => void;
    onRetryScan: () => void;
    onRemove: () => void;
};

type BusinessCardCompanyChoiceProps = {
    active: boolean;
    canCreateCompany: boolean;
    mode: BusinessCardCompanyActionMode;
    existingCompanyName: string | null;
    companyName: string;
    validationError: BusinessCardCompanyValidationError;
    disabled: boolean;
    onModeChange: (mode: Exclude<BusinessCardCompanyActionMode, null>) => void;
    onCompanyNameChange: (value: string) => void;
};

function confidencePercent(confidence: number): number {
    return Math.round(Math.max(0, Math.min(1, confidence)) * 100);
}

function confidenceItems(result: BusinessCardScanResult): Array<{ field: ConfidenceField; confidence: number }> {
    const items: Array<{ field: ConfidenceField; confidence: number }> = [];
    const candidates: Array<{ field: ConfidenceField; value: string | null; confidence: number | null }> = [
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

export function BusinessCardCapture({
    available,
    scanAvailable,
    file,
    previewUrl,
    result,
    status,
    requestError,
    importError,
    disabled,
    onFileSelected,
    onCancelScan,
    onRetryScan,
    onRemove,
}: BusinessCardCaptureProps) {
    const t = useTranslations('ContactsNewContactDialog');
    const cameraInputId = useId();
    const uploadInputId = useId();
    const cameraInputRef = useRef<HTMLInputElement>(null);
    const uploadInputRef = useRef<HTMLInputElement>(null);

    if (!available) return null;

    const handleFileChange = (event: ChangeEvent<HTMLInputElement>) => {
        const selectedFile = event.currentTarget.files?.[0];
        event.currentTarget.value = '';
        if (selectedFile && isManagedImageFile(selectedFile)) onFileSelected(selectedFile);
    };

    const errorMessage = (kind: BusinessCardRequestErrorKind) => {
        switch (kind) {
            case 'unauthorized':
                return t('cardErrorUnauthorized');
            case 'forbidden':
                return t('cardErrorForbidden');
            case 'tooLarge':
                return t('cardErrorTooLarge');
            case 'unsupportedType':
                return t('cardErrorUnsupportedType');
            case 'unreadable':
                return t('cardErrorUnreadable');
            case 'busy':
                return t('cardErrorBusy');
            case 'timeout':
                return t('cardErrorTimeout');
            case 'unavailable':
                return t('cardErrorUnavailable');
            case 'failed':
                return t('cardErrorFailed');
            case 'aborted':
                return t('cardManualFallback');
        }
    };

    const warningMessage = (warning: string) => {
        const normalizedWarning = warning.trim().toLowerCase().replaceAll('-', '_');
        if (normalizedWarning.startsWith('low_confidence')) {
            return t('cardWarningLowConfidence');
        }
        switch (normalizedWarning) {
            case 'partial_result':
                return t('cardWarningPartial');
            case 'unsupported_orientation':
                return t('cardWarningOrientation');
            case 'no_recognizable_fields':
                return t('cardWarningNoFields');
            default:
                return t('cardWarningReview');
        }
    };

    const confidenceLabel = (field: ConfidenceField) => {
        switch (field) {
            case 'name':
                return t('cardConfidenceName');
            case 'email':
                return t('cardConfidenceEmail');
            case 'phone':
                return t('cardConfidencePhone');
            case 'title':
                return t('cardConfidenceTitle');
            case 'company':
                return t('cardConfidenceCompany');
        }
    };

    const confidences = result ? confidenceItems(result) : [];
    const warnings = result
        ? [...new Set(result.warnings.map(warningMessage))]
        : [];

    return (
        <section
            className="grid gap-3 rounded-xl border bg-muted/30 p-3"
            aria-labelledby={`${cameraInputId}-title`}
            aria-busy={status === 'scanning'}
        >
            <div className="grid gap-1">
                <h3 id={`${cameraInputId}-title`} className="text-sm font-medium">{t('businessCard')}</h3>
                <p className="text-xs leading-relaxed text-muted-foreground">{t('businessCardDescription')}</p>
            </div>

            <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
                <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    disabled={disabled}
                    onClick={() => cameraInputRef.current?.click()}
                >
                    <CameraIcon data-icon="inline-start" />
                    {file ? t('scanAnotherCard') : t('scanCard')}
                </Button>
                <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    disabled={disabled}
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
                    onChange={handleFileChange}
                    aria-label={file ? t('scanAnotherCard') : t('scanCard')}
                    className="sr-only"
                    tabIndex={-1}
                />
                <input
                    ref={uploadInputRef}
                    id={uploadInputId}
                    type="file"
                    accept={MANAGED_IMAGE_ACCEPT}
                    onChange={handleFileChange}
                    aria-label={file ? t('chooseAnotherCard') : t('uploadCard')}
                    className="sr-only"
                    tabIndex={-1}
                />
            </div>

            {file && (
                <div className="flex min-w-0 items-start gap-3 rounded-lg bg-background p-2 ring-1 ring-border">
                    <div className="relative aspect-[8/5] w-24 shrink-0 overflow-hidden rounded-md bg-muted">
                        {previewUrl ? (
                            <Image
                                src={previewUrl}
                                alt={t('cardPreviewAlt')}
                                fill
                                sizes="96px"
                                unoptimized
                                className="object-cover"
                            />
                        ) : (
                            <PhotoIcon className="absolute inset-0 m-auto size-6 text-muted-foreground" aria-hidden="true" />
                        )}
                    </div>
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
                                {status === 'error' && requestError && errorMessage(requestError)}
                            </span>
                        </div>
                        {confidences.length > 0 && (
                            <ul className="mt-2 flex flex-wrap gap-x-3 gap-y-1 text-xs text-muted-foreground" aria-label={t('cardConfidenceSummary')}>
                                {confidences.map(({ field, confidence }) => (
                                    <li key={field}>
                                        {confidenceLabel(field)} {confidencePercent(confidence)}%
                                    </li>
                                ))}
                            </ul>
                        )}
                    </div>
                    <Button
                        type="button"
                        variant="ghost"
                        size="icon-xs"
                        disabled={disabled}
                        onClick={onRemove}
                        aria-label={t('removeCard')}
                    >
                        <XMarkIcon />
                    </Button>
                </div>
            )}

            {status === 'scanning' && (
                <Button type="button" variant="ghost" size="xs" className="w-fit" onClick={onCancelScan}>
                    {t('cancelCardScan')}
                </Button>
            )}

            {scanAvailable && (status === 'manual' || status === 'error') && file && (
                <Button type="button" variant="ghost" size="xs" className="w-fit" disabled={disabled} onClick={onRetryScan}>
                    <ArrowPathIcon data-icon="inline-start" />
                    {t('retryCardScan')}
                </Button>
            )}

            {warnings.length > 0 && (
                <ul className="grid gap-1 text-xs text-muted-foreground" aria-label={t('cardWarnings')}>
                    {warnings.map((warning) => <li key={warning}>{warning}</li>)}
                </ul>
            )}

            {importError && (
                <p className="text-xs leading-relaxed text-destructive" role="alert">{errorMessage(importError)}</p>
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
                            <Label htmlFor={`${groupId}-company-name`}>{t('cardCompanyName')}</Label>
                            <Input
                                id={`${groupId}-company-name`}
                                value={companyName}
                                disabled={disabled}
                                onChange={(event) => onCompanyNameChange(event.target.value)}
                                aria-invalid={validationError === 'companyName'}
                                className={cn(validationError === 'companyName' && 'border-destructive ring-destructive/30')}
                            />
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
