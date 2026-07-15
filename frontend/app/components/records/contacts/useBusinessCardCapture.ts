'use client';

import { type Dispatch, type SetStateAction, useEffect, useRef, useState } from 'react';

import { businessCardRequestErrorKind, getCapabilities, scanBusinessCard } from '@/app/lib/api';
import type {
    BusinessCardCompanyAction,
    BusinessCardImportDraft,
    BusinessCardRequestErrorKind,
    BusinessCardScanResult,
    CreateContactPayload,
} from '@/app/lib/types';

export type BusinessCardScanStatus = 'idle' | 'scanning' | 'ready' | 'manual' | 'error';
export type BusinessCardCompanyActionMode = BusinessCardCompanyAction['type'] | null;
export type BusinessCardCompanyValidationError = 'choice' | 'companyName' | null;

/** Manages the cancellable business-card scan and reviewed import draft for the contact form. */
export function useBusinessCardCapture({
    active,
    payload,
    setPayload,
}: {
    active: boolean;
    payload: CreateContactPayload;
    setPayload: Dispatch<SetStateAction<CreateContactPayload>>;
}) {
    const [available, setAvailable] = useState(false);
    const [file, setFile] = useState<File | null>(null);
    const [previewUrl, setPreviewUrl] = useState<string | null>(null);
    const [result, setResult] = useState<BusinessCardScanResult | null>(null);
    const [status, setStatus] = useState<BusinessCardScanStatus>('idle');
    const [requestError, setRequestError] = useState<BusinessCardRequestErrorKind | null>(null);
    const [importError, setImportError] = useState<BusinessCardRequestErrorKind | null>(null);
    const [companyMode, setCompanyMode] = useState<BusinessCardCompanyActionMode>(null);
    const [companyName, setCompanyName] = useState('');
    const [companyValidationError, setCompanyValidationError] = useState<BusinessCardCompanyValidationError>(null);
    const [previousActive, setPreviousActive] = useState(active);
    const controllerRef = useRef<AbortController | null>(null);
    const requestIdRef = useRef(0);
    const hasSelectedCardRef = useRef(false);
    const companyModeRef = useRef<BusinessCardCompanyActionMode>(null);
    const suggestedCompanyIdRef = useRef<number | null>(null);

    useEffect(() => {
        if (!active) return;
        let cancelled = false;
        getCapabilities()
            .then((capabilities) => {
                if (!cancelled) setAvailable(capabilities.businessCardScanning);
            })
            .catch(() => {
                if (!cancelled) setAvailable(false);
            });
        return () => {
            cancelled = true;
        };
    }, [active]);

    if (active !== previousActive) {
        setPreviousActive(active);
        if (!active) {
            setFile(null);
            setPreviewUrl(null);
            setResult(null);
            setStatus('idle');
            setRequestError(null);
            setImportError(null);
            setCompanyMode(null);
            setCompanyName('');
            setCompanyValidationError(null);
        }
    }

    useEffect(() => {
        if (!active) return;
        return () => {
            requestIdRef.current += 1;
            controllerRef.current?.abort();
            controllerRef.current = null;
            hasSelectedCardRef.current = false;
            companyModeRef.current = null;
            suggestedCompanyIdRef.current = null;
        };
    }, [active]);

    useEffect(() => {
        return () => {
            if (previewUrl) URL.revokeObjectURL(previewUrl);
        };
    }, [previewUrl]);

    const runScan = async (image: File) => {
        const requestId = requestIdRef.current + 1;
        requestIdRef.current = requestId;
        controllerRef.current?.abort();
        const controller = new AbortController();
        controllerRef.current = controller;
        setStatus('scanning');
        setRequestError(null);
        setImportError(null);
        setResult(null);

        try {
            const nextResult = await scanBusinessCard(image, { signal: controller.signal });
            if (requestId !== requestIdRef.current) return;
            controllerRef.current = null;
            setResult(nextResult);
            setCompanyName((current) => current.trim() ? current : nextResult.company.value?.trim() ?? '');
            suggestedCompanyIdRef.current = companyModeRef.current == null
                ? nextResult.company.matchedCompanyId
                : null;
            setPayload((current) => ({
                ...current,
                name: current.name.trim() ? current.name : nextResult.fields.name.value?.trim() ?? '',
                email: current.email.trim() ? current.email : nextResult.fields.email.value?.trim() ?? '',
                phone: current.phone.trim() ? current.phone : nextResult.fields.phone.value?.trim() ?? '',
                title: current.title.trim() ? current.title : nextResult.fields.title.value?.trim() ?? '',
                companyId: current.companyId
                    ?? (companyModeRef.current == null ? nextResult.company.matchedCompanyId ?? undefined : undefined),
            }));
            setStatus('ready');
        } catch (error) {
            if (requestId !== requestIdRef.current) return;
            controllerRef.current = null;
            const kind = businessCardRequestErrorKind(error);
            if (kind === 'aborted') return;
            setRequestError(kind);
            setStatus('error');
        }
    };

    const selectFile = (image: File) => {
        const hadSelectedCard = hasSelectedCardRef.current;
        const previousSuggestion = suggestedCompanyIdRef.current;
        const preserveExisting = payload.companyId != null
            && (!hadSelectedCard || companyModeRef.current === 'existing');
        if (hadSelectedCard && companyModeRef.current == null && payload.companyId === previousSuggestion) {
            setPayload((current) => ({ ...current, companyId: undefined }));
        }
        hasSelectedCardRef.current = true;
        suggestedCompanyIdRef.current = null;
        setFile(image);
        setPreviewUrl(URL.createObjectURL(image));
        const nextCompanyMode = preserveExisting ? 'existing' : null;
        companyModeRef.current = nextCompanyMode;
        setCompanyMode(nextCompanyMode);
        setCompanyName('');
        setCompanyValidationError(null);
        void runScan(image);
    };

    const cancelScan = () => {
        requestIdRef.current += 1;
        controllerRef.current?.abort();
        controllerRef.current = null;
        setRequestError(null);
        setStatus('manual');
    };

    const retryScan = () => {
        if (file) void runScan(file);
    };

    const removeCard = () => {
        requestIdRef.current += 1;
        controllerRef.current?.abort();
        controllerRef.current = null;
        setFile(null);
        setPreviewUrl(null);
        setResult(null);
        setStatus('idle');
        setRequestError(null);
        setImportError(null);
        if (payload.companyId === suggestedCompanyIdRef.current) {
            setPayload((current) => ({ ...current, companyId: undefined }));
        }
        companyModeRef.current = null;
        suggestedCompanyIdRef.current = null;
        setCompanyMode(null);
        setCompanyName('');
        setCompanyValidationError(null);
    };

    const selectExistingCompany = (companyId: number | undefined) => {
        setPayload((current) => ({ ...current, companyId }));
        const nextCompanyMode = companyId == null ? null : 'existing';
        companyModeRef.current = nextCompanyMode;
        suggestedCompanyIdRef.current = null;
        setCompanyMode(nextCompanyMode);
        setCompanyValidationError(null);
        setImportError(null);
    };

    const selectCompanyMode = (mode: Exclude<BusinessCardCompanyActionMode, null>) => {
        if (mode !== 'existing') {
            setPayload((current) => ({ ...current, companyId: undefined }));
        }
        companyModeRef.current = mode;
        suggestedCompanyIdRef.current = null;
        setCompanyMode(mode);
        setCompanyValidationError(null);
        setImportError(null);
    };

    const updateCompanyName = (value: string) => {
        setCompanyName(value);
        if (value.trim()) setCompanyValidationError(null);
        setImportError(null);
    };

    const prepareImportDraft = (): BusinessCardImportDraft | undefined => {
        if (!file) return undefined;

        let companyAction: BusinessCardCompanyAction;
        if (companyMode === 'existing' && payload.companyId != null) {
            companyAction = { type: 'existing', companyId: payload.companyId };
        } else if (companyMode === 'create' && companyName.trim()) {
            companyAction = { type: 'create', companyName: companyName.trim() };
        } else if (companyMode === 'none') {
            companyAction = { type: 'none' };
        } else {
            setCompanyValidationError(companyMode === 'create' ? 'companyName' : 'choice');
            return undefined;
        }

        const contact = companyAction.type === 'existing'
            ? { ...payload, companyId: companyAction.companyId }
            : { ...payload, companyId: undefined };
        return { image: file, contact, companyAction };
    };

    const captureImportError = (error: unknown) => {
        const kind = businessCardRequestErrorKind(error);
        if (kind !== 'aborted') setImportError(kind);
    };

    return {
        available,
        file,
        previewUrl,
        result,
        status,
        requestError,
        importError,
        companyMode,
        companyName,
        companyValidationError,
        isScanning: status === 'scanning',
        selectFile,
        cancelScan,
        retryScan,
        removeCard,
        selectExistingCompany,
        selectCompanyMode,
        updateCompanyName,
        prepareImportDraft,
        captureImportError,
    };
}
