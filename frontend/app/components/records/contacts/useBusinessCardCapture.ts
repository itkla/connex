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

import { useWorkspace } from '@/app/hooks/useWorkspace';
import {
    ApiError,
    businessCardRequestErrorKind,
    clientRecoveryContext,
    getBusinessCardAvailability,
    getEffectivePermissions,
    scanBusinessCard,
} from '@/app/lib/api';
import {
    BusinessCardRecoveryStorageUnavailableError,
    clearBusinessCardImportRecovery,
    markBusinessCardImportAvatarCompleted,
    prepareBusinessCardImportRecovery,
    reconcileBusinessCardImportRecovery,
    registerBusinessCardImportRecovery,
    type RecoveredBusinessCardImport,
} from '@/app/lib/business-card-import-recovery';
import type {
    BusinessCardCompanyAction,
    BusinessCardImportDraft,
    BusinessCardPersonAction,
    BusinessCardRequestErrorKind,
    BusinessCardRecoveryContext,
    BusinessCardScanResult,
    CreateContactPayload,
} from '@/app/lib/types';

export type BusinessCardScanStatus = 'idle' | 'scanning' | 'ready' | 'manual' | 'error';
export type BusinessCardCompanyActionMode = BusinessCardCompanyAction['type'] | null;
export type BusinessCardCompanyValidationError = 'choice' | 'companyName' | null;
export type BusinessCardContactField = 'name' | 'email' | 'phone' | 'title';
export type BusinessCardRecoveryStatus = 'checking' | 'ready' | 'acknowledging' | 'storageUnavailable' | 'error';

const BUSINESS_CARD_CONTACT_FIELDS: BusinessCardContactField[] = ['name', 'email', 'phone', 'title'];

function isAmbiguousImportOutcome(kind: BusinessCardRequestErrorKind): boolean {
    return kind === 'aborted'
        || kind === 'conflict'
        || kind === 'timeout'
        || kind === 'unavailable'
        || kind === 'failed';
}

function requiresLockedImportOutcome(kind: BusinessCardRequestErrorKind): boolean {
    return kind === 'gone' || isAmbiguousImportOutcome(kind);
}

type OcrOwnedField = {
    value: string;
    baseline: string;
};

type BusinessCardAvailabilityState = {
    workspaceId: number;
    available: boolean;
    scanAvailable: boolean;
    canCreateCompany: boolean;
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
    const { activeWorkspaceId } = useWorkspace();
    const [availabilityState, setAvailabilityState] = useState<BusinessCardAvailabilityState | null>(null);
    const currentAvailability = availabilityState?.workspaceId === activeWorkspaceId
        ? availabilityState
        : null;
    const available = currentAvailability?.available ?? false;
    const availabilityResolved = currentAvailability != null;
    const scanAvailable = currentAvailability?.scanAvailable ?? false;
    const canCreateCompany = currentAvailability?.canCreateCompany ?? false;
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
    const [recoveryStatus, setRecoveryStatus] = useState<BusinessCardRecoveryStatus>('checking');
    const [recoveryAttempt, setRecoveryAttempt] = useState(0);
    const [recoveredImport, setRecoveredImport] = useState<RecoveredBusinessCardImport | null>(null);
    const [previousActive, setPreviousActive] = useState(active);
    const activeRef = useRef(active);
    const payloadRef = useRef(payload);
    const controllerRef = useRef<AbortController | null>(null);
    const importControllerRef = useRef<AbortController | null>(null);
    const scanRequestSequenceRef = useRef(0);
    const importRequestIdRef = useRef<string | null>(null);
    const importRevisionRef = useRef<number | null>(null);
    const importRecoveryContextRef = useRef<BusinessCardRecoveryContext | null>(null);
    const recoveryResetPromiseRef = useRef<Promise<void> | null>(null);
    const requiresExactImportRetryRef = useRef(false);
    const hasSelectedCardRef = useRef(false);
    const companyModeRef = useRef<BusinessCardCompanyActionMode>(null);
    const suggestedCompanyIdRef = useRef<number | null>(null);
    const suggestedCompanyNameRef = useRef<string | null>(null);
    const companyNameRef = useRef('');
    const ocrOwnedFieldsRef = useRef<Partial<Record<BusinessCardContactField, OcrOwnedField>>>({});
    const userTouchedFieldsRef = useRef<Set<BusinessCardContactField>>(new Set());
    const userTouchedCompanyNameRef = useRef(false);
    const onImportRetryRequiredChangeRef = useRef(onImportRetryRequiredChange);

    useLayoutEffect(() => {
        activeRef.current = active;
        payloadRef.current = payload;
        if (!active) {
            scanRequestSequenceRef.current += 1;
            controllerRef.current?.abort();
            controllerRef.current = null;
            importControllerRef.current?.abort();
            importControllerRef.current = null;
        }
    }, [active, payload]);

    useLayoutEffect(() => {
        onImportRetryRequiredChangeRef.current = onImportRetryRequiredChange;
    });

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
        if (!active || activeWorkspaceId == null) return;
        const workspaceId = activeWorkspaceId;
        Promise.all([getBusinessCardAvailability(), getEffectivePermissions()])
            .then(([availability, permissions]) => {
                if (cancelled) return;
                setAvailabilityState({
                    workspaceId,
                    available: availability.importing
                        && permissions.includes('PERSON_CREATE')
                        && permissions.includes('ATTACHMENT_CREATE'),
                    scanAvailable: availability.scanning,
                    canCreateCompany: permissions.includes('COMPANY_CREATE'),
                });
            })
            .catch(() => {
                if (cancelled) return;
                setAvailabilityState({
                    workspaceId,
                    available: false,
                    scanAvailable: false,
                    canCreateCompany: false,
                });
            });
        return () => {
            cancelled = true;
        };
    }, [active, activeWorkspaceId]);

    useEffect(() => {
        if (!active) return;
        const controller = new AbortController();
        reconcileBusinessCardImportRecovery(controller.signal, importRequestIdRef.current)
            .then((reconciliation) => {
                if (controller.signal.aborted || !activeRef.current) return;
                if (reconciliation.reusableRequestId) {
                    importRequestIdRef.current = reconciliation.reusableRequestId;
                    importRevisionRef.current = reconciliation.reusableRevision;
                } else if (!reconciliation.recovered) {
                    importRequestIdRef.current = null;
                    importRevisionRef.current = null;
                }
                if (reconciliation.recovered) {
                    importRevisionRef.current = reconciliation.recovered.revision;
                }
                importRecoveryContextRef.current = reconciliation.context;
                setRecoveredImport(reconciliation.recovered);
                if (!reconciliation.recovered
                    && !reconciliation.reusableRequestId
                    && requiresExactImportRetryRef.current) {
                    requiresExactImportRetryRef.current = false;
                    setRequiresExactImportRetry(false);
                    onImportRetryRequiredChangeRef.current?.(false);
                }
                setRecoveryStatus('ready');
            })
            .catch((error: unknown) => {
                if (controller.signal.aborted || !activeRef.current) return;
                setRecoveryStatus(
                    error instanceof BusinessCardRecoveryStorageUnavailableError
                        ? 'storageUnavailable'
                        : 'error',
                );
            });
        return () => {
            controller.abort();
        };
    }, [active, recoveryAttempt]);

    useEffect(() => {
        if (!active) requiresExactImportRetryRef.current = false;
    }, [active]);

    if (active !== previousActive) {
        setPreviousActive(active);
        if (!active) {
            setAvailabilityState(null);
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
            importControllerRef.current?.abort();
            importControllerRef.current = null;
            revertOcrOwnedValues();
            importRequestIdRef.current = null;
            importRevisionRef.current = null;
            importRecoveryContextRef.current = null;
            hasSelectedCardRef.current = false;
            companyModeRef.current = null;
            suggestedCompanyIdRef.current = null;
            suggestedCompanyNameRef.current = null;
            companyNameRef.current = '';
            userTouchedFields.clear();
            userTouchedCompanyNameRef.current = false;
        };
    }, [active, revertOcrOwnedValues]);

    const clearImportRequest = async (signal?: AbortSignal) => {
        const requestId = importRequestIdRef.current;
        if (!requestId) return;
        const context = importRecoveryContextRef.current;
        if (!context) throw new BusinessCardRecoveryStorageUnavailableError();
        await clearBusinessCardImportRecovery(
            requestId,
            context,
            signal,
            importRevisionRef.current,
        );
        if (importRequestIdRef.current === requestId) {
            importRequestIdRef.current = null;
            importRevisionRef.current = null;
        }
    };

    const clearImportRequestBestEffort = () => {
        const pending = clearImportRequest();
        recoveryResetPromiseRef.current = pending;
        void pending
            .catch(() => {
                if (activeRef.current) setRecoveryStatus('storageUnavailable');
            })
            .finally(() => {
                if (recoveryResetPromiseRef.current === pending) {
                    recoveryResetPromiseRef.current = null;
                }
            });
    };

    useEffect(() => {
        return () => {
            if (previewUrl) URL.revokeObjectURL(previewUrl);
        };
    }, [previewUrl]);

    const runScan = async (image: File, shouldScan = true) => {
        const requestSequence = scanRequestSequenceRef.current + 1;
        scanRequestSequenceRef.current = requestSequence;
        controllerRef.current?.abort();
        const controller = new AbortController();
        controllerRef.current = controller;
        setStatus('scanning');
        setRequestError(null);
        setImportError(null);
        setResult(null);

        const canCommit = () => activeRef.current
            && requestSequence === scanRequestSequenceRef.current
            && !controller.signal.aborted;

        try {
            const nextContext = await clientRecoveryContext({ signal: controller.signal });
            if (!nextContext) throw new BusinessCardRecoveryStorageUnavailableError();
            const existingContext = importRecoveryContextRef.current;
            if (existingContext
                && (existingContext.scope !== nextContext.scope
                    || existingContext.workspaceId !== nextContext.workspaceId)) {
                throw new ApiError(
                    'Business-card request context changed',
                    409,
                    'BUSINESS_CARD_CONTEXT_CHANGED',
                );
            }
            if (!canCommit()) return;
            importRecoveryContextRef.current = nextContext;
            if (!shouldScan) {
                controllerRef.current = null;
                setStatus('manual');
                return;
            }
            const nextResult = await scanBusinessCard(
                image,
                nextContext,
                { signal: controller.signal },
            );
            if (!canCommit()) return;
            controllerRef.current = null;
            setResult(nextResult);
            if (!canCommit()) return;
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

            if (!canCommit()) return;

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
                suggestedCompanyIdRef.current = nextResult.company.matchedCompanyId ?? null;
            } else {
                suggestedCompanyIdRef.current = null;
            }
            if (!canCommit()) return;
            ocrOwnedFieldsRef.current = nextOwnedFields;
            commitPayload(next);
            if (!canCommit()) return;
            setStatus('ready');
        } catch (error) {
            if (!canCommit()) return;
            controllerRef.current = null;
            const kind = businessCardRequestErrorKind(error);
            if (kind === 'aborted') return;
            setRequestError(kind);
            setStatus('error');
        }
    };

    const selectFile = (image: File) => {
        if (!activeRef.current || requiresExactImportRetryRef.current) return;
        const hadSelectedCard = hasSelectedCardRef.current;
        revertOcrOwnedValues();
        const preserveExisting = payloadRef.current.companyId != null
            && (!hadSelectedCard || companyModeRef.current === 'existing');
        hasSelectedCardRef.current = true;
        userTouchedFieldsRef.current.clear();
        userTouchedCompanyNameRef.current = false;
        clearImportRequestBestEffort();
        importRecoveryContextRef.current = null;
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
        void runScan(image, scanAvailable);
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
            const workspaceId = activeWorkspaceId;
            if (workspaceId == null) {
                setRequestError('unavailable');
                setStatus('manual');
                return;
            }
            const availability = await getBusinessCardAvailability();
            if (!activeRef.current || requestSequence !== scanRequestSequenceRef.current) return;
            setAvailabilityState((current) => ({
                workspaceId,
                available: current?.workspaceId === workspaceId && current.available,
                scanAvailable: availability.scanning,
                canCreateCompany: current?.workspaceId === workspaceId && current.canCreateCompany,
            }));
            if (!activeRef.current || requestSequence !== scanRequestSequenceRef.current) return;
            if (!availability.scanning) {
                setRequestError('unavailable');
                setStatus('manual');
                return;
            }
            await runScan(file);
        } catch {
            if (!activeRef.current || requestSequence !== scanRequestSequenceRef.current) return;
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
        clearImportRequestBestEffort();
        importRecoveryContextRef.current = null;
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

    const prepareImportDraft = async (
        pendingAvatar: boolean,
        personAction: BusinessCardPersonAction = { type: 'create' },
    ): Promise<BusinessCardImportDraft | undefined> => {
        setImportError(null);
        if (!file) return undefined;
        try {
            await recoveryResetPromiseRef.current;
        } catch {
            setImportError('recoveryStorage');
            return undefined;
        }

        let companyAction: BusinessCardCompanyAction;
        const currentPayload = payloadRef.current;
        const currentCompanyMode = companyModeRef.current;
        const currentCompanyName = companyNameRef.current.trim();
        if (personAction.type === 'existing') {
            companyAction = { type: 'none' };
        } else if (currentCompanyMode === 'existing' && currentPayload.companyId != null) {
            companyAction = { type: 'existing', companyId: currentPayload.companyId };
        } else if (currentCompanyMode === 'create' && canCreateCompany && currentCompanyName) {
            companyAction = { type: 'create', companyName: currentCompanyName };
        } else if (currentCompanyMode === 'none') {
            companyAction = { type: 'none' };
        } else {
            setCompanyValidationError(currentCompanyMode === 'create' ? 'companyName' : 'choice');
            return undefined;
        }

        let requestId: string;
        const controller = new AbortController();
        importControllerRef.current?.abort();
        importControllerRef.current = controller;
        try {
            let recoveryContext = importRecoveryContextRef.current;
            if (!recoveryContext) {
                recoveryContext = await clientRecoveryContext({ signal: controller.signal });
                if (!recoveryContext) throw new BusinessCardRecoveryStorageUnavailableError();
                importRecoveryContextRef.current = recoveryContext;
            }
            if (importRequestIdRef.current) {
                requestId = importRequestIdRef.current;
            } else {
                requestId = await registerBusinessCardImportRecovery(
                    pendingAvatar,
                    recoveryContext,
                    controller.signal,
                );
                importRevisionRef.current = 0;
            }
            importRequestIdRef.current = requestId;
            importRevisionRef.current = await prepareBusinessCardImportRecovery(
                requestId,
                pendingAvatar,
                recoveryContext,
                controller.signal,
            );
        } catch (error) {
            if (controller.signal.aborted) return undefined;
            setImportError(
                error instanceof BusinessCardRecoveryStorageUnavailableError
                    ? 'recoveryStorage'
                    : businessCardRequestErrorKind(error),
            );
            return undefined;
        } finally {
            if (importControllerRef.current === controller) importControllerRef.current = null;
        }
        const contact = companyAction.type === 'existing'
            ? { ...currentPayload, companyId: companyAction.companyId }
            : { ...currentPayload, companyId: undefined };
        const recoveryContext = importRecoveryContextRef.current;
        if (!recoveryContext) return undefined;
        return { requestId, recoveryContext, image: file, contact, personAction, companyAction };
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
        const retryRequired = requiresExactImportRetryRef.current || requiresLockedImportOutcome(kind);
        if (!retryRequired) clearImportRequestBestEffort();
        requiresExactImportRetryRef.current = retryRequired;
        setRequiresExactImportRetry(retryRequired);
        onImportRetryRequiredChangeRef.current?.(retryRequired);
    };

    const resolveImportRetry = async (signal?: AbortSignal) => {
        try {
            await clearImportRequest(signal);
        } catch (error) {
            if (!signal?.aborted && activeRef.current) setRecoveryStatus('storageUnavailable');
            throw error;
        }
        requiresExactImportRetryRef.current = false;
        setRequiresExactImportRetry(false);
        onImportRetryRequiredChangeRef.current?.(false);
    };

    const markImportAvatarCompleted = async (signal?: AbortSignal) => {
        const requestId = importRequestIdRef.current;
        if (!requestId) return;
        const context = importRecoveryContextRef.current;
        if (!context) throw new BusinessCardRecoveryStorageUnavailableError();
        try {
            importRevisionRef.current = await markBusinessCardImportAvatarCompleted(
                requestId,
                context,
                signal,
            );
        } catch (error) {
            if (!signal?.aborted && activeRef.current) setRecoveryStatus('storageUnavailable');
            throw error;
        }
    };

    const deferImportRetry = () => {
        importRequestIdRef.current = null;
        importRevisionRef.current = null;
        importRecoveryContextRef.current = null;
        requiresExactImportRetryRef.current = false;
        setRequiresExactImportRetry(false);
        setImportError(null);
        onImportRetryRequiredChangeRef.current?.(false);
    };

    const continueManually = () => {
        deferImportRetry();
        clearCard(true);
        setRecoveredImport(null);
    };

    const retryRecovery = useCallback(() => {
        setRecoveryStatus('checking');
        setRecoveryAttempt((attempt) => attempt + 1);
    }, []);

    const acknowledgeRecoveredImport = useCallback(async (signal?: AbortSignal) => {
        if (!recoveredImport) return;
        const context = importRecoveryContextRef.current;
        if (!context) throw new BusinessCardRecoveryStorageUnavailableError();
        try {
            setRecoveryStatus('acknowledging');
            await clearBusinessCardImportRecovery(
                recoveredImport.requestId,
                context,
                signal,
                recoveredImport.revision,
            );
            if (signal?.aborted || !activeRef.current) return;
            if (importRequestIdRef.current === recoveredImport.requestId) {
                importRequestIdRef.current = null;
                importRevisionRef.current = null;
            }
            setRecoveredImport(null);
            setRecoveryStatus('ready');
        } catch (error) {
            if (!signal?.aborted && activeRef.current) {
                setRecoveryStatus(
                    error instanceof BusinessCardRecoveryStorageUnavailableError
                        ? 'storageUnavailable'
                        : 'error',
                );
            }
            throw error;
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
        discardCardImage,
        selectExistingCompany,
        selectCompanyMode,
        updateCompanyName,
        updateContactField,
        prepareImportDraft,
        captureImportError,
        resolveImportRetry,
        markImportAvatarCompleted,
        deferImportRetry,
        continueManually,
        retryRecovery,
        acknowledgeRecoveredImport,
    };
}
