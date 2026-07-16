'use client';

import {
    type Dispatch,
    type SetStateAction,
    useCallback,
    useEffect,
    useLayoutEffect,
    useRef,
    useState,
} from 'react';

import {
    businessCardRequestErrorKind,
    getCapabilities,
    getEffectivePermissions,
    scanBusinessCard,
} from '@/app/lib/api';
import {
    clearBusinessCardImportRecovery,
    reconcileBusinessCardImportRecovery,
    registerBusinessCardImportRecovery,
    type RecoveredBusinessCardImport,
} from '@/app/lib/business-card-import-recovery';
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
export type BusinessCardContactField = 'name' | 'email' | 'phone' | 'title';

const BUSINESS_CARD_CONTACT_FIELDS: BusinessCardContactField[] = ['name', 'email', 'phone', 'title'];

function isAmbiguousImportOutcome(kind: BusinessCardRequestErrorKind): boolean {
    return kind === 'aborted'
        || kind === 'conflict'
        || kind === 'timeout'
        || kind === 'unavailable'
        || kind === 'failed';
}

type OcrOwnedField = {
    value: string;
    baseline: string;
};

function scannedContactValue(result: BusinessCardScanResult, field: BusinessCardContactField): string {
    return result.fields[field].value?.trim() ?? '';
}

/** Manages the cancellable business-card scan and reviewed import draft for the contact form. */
export function useBusinessCardCapture({
    active,
    payload,
    setPayload,
    onImportRetryRequiredChange,
}: {
    active: boolean;
    payload: CreateContactPayload;
    setPayload: Dispatch<SetStateAction<CreateContactPayload>>;
    onImportRetryRequiredChange?: (required: boolean) => void;
}) {
    const [available, setAvailable] = useState(false);
    const [availabilityResolved, setAvailabilityResolved] = useState(false);
    const [scanAvailable, setScanAvailable] = useState(false);
    const [canCreateCompany, setCanCreateCompany] = useState(false);
    const [file, setFile] = useState<File | null>(null);
    const [previewUrl, setPreviewUrl] = useState<string | null>(null);
    const [result, setResult] = useState<BusinessCardScanResult | null>(null);
    const [status, setStatus] = useState<BusinessCardScanStatus>('idle');
    const [requestError, setRequestError] = useState<BusinessCardRequestErrorKind | null>(null);
    const [importError, setImportError] = useState<BusinessCardRequestErrorKind | null>(null);
    const [requiresExactImportRetry, setRequiresExactImportRetry] = useState(false);
    const [companyMode, setCompanyMode] = useState<BusinessCardCompanyActionMode>(null);
    const [companyName, setCompanyName] = useState('');
    const [companyValidationError, setCompanyValidationError] = useState<BusinessCardCompanyValidationError>(null);
    const [recoveryStatus, setRecoveryStatus] = useState<'checking' | 'ready' | 'error'>('checking');
    const [recoveryAttempt, setRecoveryAttempt] = useState(0);
    const [recoveredImport, setRecoveredImport] = useState<RecoveredBusinessCardImport | null>(null);
    const [previousActive, setPreviousActive] = useState(active);
    const payloadRef = useRef(payload);
    const controllerRef = useRef<AbortController | null>(null);
    const scanRequestSequenceRef = useRef(0);
    const importRequestIdRef = useRef<string | null>(null);
    const requiresExactImportRetryRef = useRef(false);
    const hasSelectedCardRef = useRef(false);
    const companyModeRef = useRef<BusinessCardCompanyActionMode>(null);
    const suggestedCompanyIdRef = useRef<number | null>(null);
    const suggestedCompanyNameRef = useRef<string | null>(null);
    const companyNameRef = useRef('');
    const ocrOwnedFieldsRef = useRef<Partial<Record<BusinessCardContactField, OcrOwnedField>>>({});
    const userTouchedFieldsRef = useRef<Set<BusinessCardContactField>>(new Set());
    const userTouchedCompanyNameRef = useRef(false);

    useLayoutEffect(() => {
        payloadRef.current = payload;
    }, [payload]);

    const commitPayload = useCallback((next: CreateContactPayload) => {
        payloadRef.current = next;
        setPayload(next);
    }, [setPayload]);

    const revertOcrOwnedValues = useCallback(() => {
        const ownedFields = ocrOwnedFieldsRef.current;
        const suggestedCompanyId = suggestedCompanyIdRef.current;
        const current = payloadRef.current;
        const next = { ...current };
        let changed = false;
        for (const field of BUSINESS_CARD_CONTACT_FIELDS) {
            const ownership = ownedFields[field];
            if (ownership && current[field] === ownership.value) {
                next[field] = ownership.baseline;
                changed = true;
            }
        }
        if (suggestedCompanyId != null
            && companyModeRef.current == null
            && current.companyId === suggestedCompanyId) {
            next.companyId = undefined;
            changed = true;
        }
        ocrOwnedFieldsRef.current = {};
        suggestedCompanyIdRef.current = null;
        if (changed) commitPayload(next);
    }, [commitPayload]);

    useEffect(() => {
        let cancelled = false;
        if (!active) return;
        Promise.all([getCapabilities(), getEffectivePermissions()])
            .then(([capabilities, permissions]) => {
                if (cancelled) return;
                setAvailable(
                    capabilities.businessCardImport
                    && permissions.includes('PERSON_CREATE')
                    && permissions.includes('ATTACHMENT_CREATE'),
                );
                setScanAvailable(capabilities.businessCardScanning);
                setCanCreateCompany(permissions.includes('COMPANY_CREATE'));
                setAvailabilityResolved(true);
            })
            .catch(() => {
                if (cancelled) return;
                setAvailable(false);
                setScanAvailable(false);
                setCanCreateCompany(false);
                setAvailabilityResolved(true);
            });
        return () => {
            cancelled = true;
        };
    }, [active]);

    useEffect(() => {
        if (!active) return;
        let cancelled = false;
        reconcileBusinessCardImportRecovery()
            .then((recovered) => {
                if (cancelled) return;
                setRecoveredImport(recovered);
                setRecoveryStatus('ready');
            })
            .catch(() => {
                if (!cancelled) setRecoveryStatus('error');
            });
        return () => {
            cancelled = true;
        };
    }, [active, recoveryAttempt]);

    useEffect(() => {
        if (!active) requiresExactImportRetryRef.current = false;
    }, [active]);

    if (active !== previousActive) {
        setPreviousActive(active);
        if (!active) {
            setAvailable(false);
            setAvailabilityResolved(false);
            setScanAvailable(false);
            setCanCreateCompany(false);
            setFile(null);
            setPreviewUrl(null);
            setResult(null);
            setStatus('idle');
            setRequestError(null);
            setImportError(null);
            setRequiresExactImportRetry(false);
            setCompanyMode(null);
            setCompanyName('');
            setCompanyValidationError(null);
            setRecoveryStatus('checking');
            setRecoveredImport(null);
        }
    }

    useEffect(() => {
        if (!active) return;
        const userTouchedFields = userTouchedFieldsRef.current;
        return () => {
            scanRequestSequenceRef.current += 1;
            controllerRef.current?.abort();
            controllerRef.current = null;
            revertOcrOwnedValues();
            importRequestIdRef.current = null;
            hasSelectedCardRef.current = false;
            companyModeRef.current = null;
            suggestedCompanyIdRef.current = null;
            suggestedCompanyNameRef.current = null;
            companyNameRef.current = '';
            userTouchedFields.clear();
            userTouchedCompanyNameRef.current = false;
        };
    }, [active, revertOcrOwnedValues]);

    const clearImportRequest = () => {
        const requestId = importRequestIdRef.current;
        importRequestIdRef.current = null;
        if (requestId) void clearBusinessCardImportRecovery(requestId);
    };

    useEffect(() => {
        return () => {
            if (previewUrl) URL.revokeObjectURL(previewUrl);
        };
    }, [previewUrl]);

    const runScan = async (image: File) => {
        const requestSequence = scanRequestSequenceRef.current + 1;
        scanRequestSequenceRef.current = requestSequence;
        controllerRef.current?.abort();
        const controller = new AbortController();
        controllerRef.current = controller;
        setStatus('scanning');
        setRequestError(null);
        setImportError(null);
        setResult(null);

        try {
            const nextResult = await scanBusinessCard(image, { signal: controller.signal });
            if (requestSequence !== scanRequestSequenceRef.current) return;
            controllerRef.current = null;
            setResult(nextResult);
            const previousCompanySuggestion = suggestedCompanyNameRef.current;
            const currentCompanyName = companyNameRef.current;
            const nextCompanySuggestion = nextResult.company.value?.trim() ?? '';
            if (!userTouchedCompanyNameRef.current
                && (!currentCompanyName.trim()
                || (previousCompanySuggestion != null && currentCompanyName === previousCompanySuggestion))) {
                companyNameRef.current = nextCompanySuggestion;
                suggestedCompanyNameRef.current = nextCompanySuggestion || null;
                setCompanyName(nextCompanySuggestion);
            } else {
                suggestedCompanyNameRef.current = null;
            }

            const current = payloadRef.current;
            const previousOwnedFields = ocrOwnedFieldsRef.current;
            const nextOwnedFields: Partial<Record<BusinessCardContactField, OcrOwnedField>> = {};
            const next = { ...current };
            for (const field of BUSINESS_CARD_CONTACT_FIELDS) {
                const previousOwnership = previousOwnedFields[field];
                const isUntouchedOcrValue = previousOwnership != null && current[field] === previousOwnership.value;
                if (!userTouchedFieldsRef.current.has(field)
                    && (!current[field].trim() || isUntouchedOcrValue)) {
                    const nextValue = scannedContactValue(nextResult, field);
                    next[field] = nextValue;
                    if (isUntouchedOcrValue && previousOwnership) {
                        nextOwnedFields[field] = { value: nextValue, baseline: previousOwnership.baseline };
                    } else if (nextValue !== current[field]) {
                        nextOwnedFields[field] = { value: nextValue, baseline: current[field] };
                    }
                }
            }

            const previousSuggestedCompanyId = suggestedCompanyIdRef.current;
            const canApplyCompanySuggestion = companyModeRef.current == null
                && (current.companyId == null || current.companyId === previousSuggestedCompanyId);
            if (canApplyCompanySuggestion) {
                next.companyId = nextResult.company.matchedCompanyId ?? undefined;
                suggestedCompanyIdRef.current = nextResult.company.matchedCompanyId;
            } else {
                suggestedCompanyIdRef.current = null;
            }
            ocrOwnedFieldsRef.current = nextOwnedFields;
            commitPayload(next);
            setStatus('ready');
        } catch (error) {
            if (requestSequence !== scanRequestSequenceRef.current) return;
            controllerRef.current = null;
            const kind = businessCardRequestErrorKind(error);
            if (kind === 'aborted') return;
            setRequestError(kind);
            setStatus('error');
        }
    };

    const selectFile = (image: File) => {
        if (requiresExactImportRetryRef.current) return;
        const hadSelectedCard = hasSelectedCardRef.current;
        revertOcrOwnedValues();
        const preserveExisting = payloadRef.current.companyId != null
            && (!hadSelectedCard || companyModeRef.current === 'existing');
        hasSelectedCardRef.current = true;
        userTouchedFieldsRef.current.clear();
        userTouchedCompanyNameRef.current = false;
        clearImportRequest();
        suggestedCompanyIdRef.current = null;
        setFile(image);
        setPreviewUrl(URL.createObjectURL(image));
        const nextCompanyMode = preserveExisting ? 'existing' : null;
        companyModeRef.current = nextCompanyMode;
        setCompanyMode(nextCompanyMode);
        setCompanyName('');
        companyNameRef.current = '';
        suggestedCompanyNameRef.current = null;
        setCompanyValidationError(null);
        setImportError(null);
        if (scanAvailable) {
            void runScan(image);
        } else {
            scanRequestSequenceRef.current += 1;
            controllerRef.current?.abort();
            controllerRef.current = null;
            setResult(null);
            setRequestError(null);
            setStatus('manual');
        }
    };

    const cancelScan = () => {
        if (requiresExactImportRetryRef.current) return;
        scanRequestSequenceRef.current += 1;
        controllerRef.current?.abort();
        controllerRef.current = null;
        setRequestError(null);
        setStatus('manual');
    };

    const retryScan = async () => {
        if (!file || requiresExactImportRetryRef.current) return;
        if (scanAvailable) {
            await runScan(file);
            return;
        }
        const requestSequence = scanRequestSequenceRef.current + 1;
        scanRequestSequenceRef.current = requestSequence;
        controllerRef.current?.abort();
        controllerRef.current = null;
        setStatus('scanning');
        setRequestError(null);
        try {
            const capabilities = await getCapabilities();
            if (requestSequence !== scanRequestSequenceRef.current) return;
            setScanAvailable(capabilities.businessCardScanning);
            if (!capabilities.businessCardScanning) {
                setRequestError('unavailable');
                setStatus('manual');
                return;
            }
            await runScan(file);
        } catch {
            if (requestSequence !== scanRequestSequenceRef.current) return;
            setRequestError('unavailable');
            setStatus('manual');
        }
    };

    const clearCard = (restoreOcrValues: boolean) => {
        if (requiresExactImportRetryRef.current) return;
        scanRequestSequenceRef.current += 1;
        controllerRef.current?.abort();
        controllerRef.current = null;
        if (restoreOcrValues) {
            revertOcrOwnedValues();
        } else {
            ocrOwnedFieldsRef.current = {};
            suggestedCompanyIdRef.current = null;
        }
        clearImportRequest();
        hasSelectedCardRef.current = false;
        setFile(null);
        setPreviewUrl(null);
        setResult(null);
        setStatus('idle');
        setRequestError(null);
        setImportError(null);
        setRequiresExactImportRetry(false);
        companyModeRef.current = null;
        suggestedCompanyIdRef.current = null;
        suggestedCompanyNameRef.current = null;
        userTouchedFieldsRef.current.clear();
        userTouchedCompanyNameRef.current = false;
        setCompanyMode(null);
        setCompanyName('');
        companyNameRef.current = '';
        setCompanyValidationError(null);
    };

    const removeCard = () => clearCard(true);

    const discardCardImage = () => clearCard(false);

    const selectExistingCompany = (companyId: number | undefined) => {
        if (requiresExactImportRetryRef.current) return;
        commitPayload({ ...payloadRef.current, companyId });
        const nextCompanyMode = companyId == null ? null : 'existing';
        companyModeRef.current = nextCompanyMode;
        suggestedCompanyIdRef.current = null;
        setCompanyMode(nextCompanyMode);
        setCompanyValidationError(null);
        setImportError(null);
    };

    const selectCompanyMode = (mode: Exclude<BusinessCardCompanyActionMode, null>) => {
        if (requiresExactImportRetryRef.current) return;
        if (mode !== 'existing') {
            commitPayload({ ...payloadRef.current, companyId: undefined });
        }
        companyModeRef.current = mode;
        suggestedCompanyIdRef.current = null;
        setCompanyMode(mode);
        setCompanyValidationError(null);
        setImportError(null);
    };

    const updateCompanyName = (value: string) => {
        if (requiresExactImportRetryRef.current) return;
        userTouchedCompanyNameRef.current = true;
        suggestedCompanyNameRef.current = null;
        companyNameRef.current = value;
        setCompanyName(value);
        if (value.trim()) setCompanyValidationError(null);
        setImportError(null);
    };

    const prepareImportDraft = async (): Promise<BusinessCardImportDraft | undefined> => {
        setImportError(null);
        if (!file) return undefined;

        let companyAction: BusinessCardCompanyAction;
        const currentPayload = payloadRef.current;
        const currentCompanyMode = companyModeRef.current;
        const currentCompanyName = companyNameRef.current.trim();
        if (currentCompanyMode === 'existing' && currentPayload.companyId != null) {
            companyAction = { type: 'existing', companyId: currentPayload.companyId };
        } else if (currentCompanyMode === 'create' && canCreateCompany && currentCompanyName) {
            companyAction = { type: 'create', companyName: currentCompanyName };
        } else if (currentCompanyMode === 'none') {
            companyAction = { type: 'none' };
        } else {
            setCompanyValidationError(currentCompanyMode === 'create' ? 'companyName' : 'choice');
            return undefined;
        }

        let requestId = importRequestIdRef.current;
        if (!requestId) {
            try {
                requestId = await registerBusinessCardImportRecovery();
            } catch {
                setImportError('recoveryStorage');
                return undefined;
            }
            importRequestIdRef.current = requestId;
        }
        const contact = companyAction.type === 'existing'
            ? { ...currentPayload, companyId: companyAction.companyId }
            : { ...currentPayload, companyId: undefined };
        return { requestId, image: file, contact, companyAction };
    };

    const updateContactField = (field: BusinessCardContactField, value: string) => {
        if (requiresExactImportRetryRef.current) return;
        userTouchedFieldsRef.current.add(field);
        delete ocrOwnedFieldsRef.current[field];
        commitPayload({ ...payloadRef.current, [field]: value });
        setImportError(null);
    };

    const captureImportError = (error: unknown) => {
        const kind = businessCardRequestErrorKind(error);
        setImportError(kind);
        const retryRequired = isAmbiguousImportOutcome(kind);
        if (!retryRequired) clearImportRequest();
        requiresExactImportRetryRef.current = retryRequired;
        setRequiresExactImportRetry(retryRequired);
        onImportRetryRequiredChange?.(retryRequired);
    };

    const resolveImportRetry = () => {
        clearImportRequest();
        requiresExactImportRetryRef.current = false;
        setRequiresExactImportRetry(false);
        onImportRetryRequiredChange?.(false);
    };

    const deferImportRetry = () => {
        importRequestIdRef.current = null;
        requiresExactImportRetryRef.current = false;
        setRequiresExactImportRetry(false);
        setImportError(null);
        onImportRetryRequiredChange?.(false);
    };

    const retryRecovery = useCallback(() => {
        setRecoveryStatus('checking');
        setRecoveryAttempt((attempt) => attempt + 1);
    }, []);

    const acknowledgeRecoveredImport = useCallback(async () => {
        if (!recoveredImport) return;
        try {
            await clearBusinessCardImportRecovery(recoveredImport.requestId);
        } finally {
            setRecoveredImport(null);
        }
    }, [recoveredImport]);

    return {
        available,
        availabilityResolved,
        scanAvailable,
        canCreateCompany,
        file,
        previewUrl,
        result,
        status,
        requestError,
        importError,
        requiresExactImportRetry,
        companyMode,
        companyName,
        companyValidationError,
        recoveryStatus,
        recoveredImport,
        isScanning: status === 'scanning',
        selectFile,
        cancelScan,
        retryScan,
        removeCard,
        discardCardImage,
        selectExistingCompany,
        selectCompanyMode,
        updateCompanyName,
        updateContactField,
        prepareImportDraft,
        captureImportError,
        resolveImportRetry,
        deferImportRetry,
        retryRecovery,
        acknowledgeRecoveredImport,
    };
}
