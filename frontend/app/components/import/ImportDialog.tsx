'use client';

import { createServerPickerSearch } from '@/lib/server-picker-search';
import { Fragment, useEffect, useId, useMemo, useRef, useState } from 'react';
import { useFormatter, useLocale, useTranslations } from 'next-intl';
import { motion, useReducedMotion } from 'motion/react';
import { ArrowUpTrayIcon, CheckCircleIcon, DocumentTextIcon, ExclamationTriangleIcon, XMarkIcon } from '@heroicons/react/24/outline';
import { LoaderCircle } from 'lucide-react';

import { Input } from '@/components/ui/input';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import {
    Combobox,
    ComboboxContent,
    ComboboxEmpty,
    ComboboxInput,
    ComboboxItem,
    ComboboxList,
} from '@/components/ui/combobox';
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { cn } from '@/lib/utils';
import { ApiError, commitImport, getCustomFieldSchema, isWorkspaceUnavailableError, previewImport, search } from '@/app/lib/api';
import { toastError, toastSuccess } from '@/app/lib/toast';
import { usePermission } from '@/app/hooks/usePermissions';
import { workspaceDefaultCurrency } from '@/app/lib/currencies';
import { useWorkspace } from '@/app/hooks/useWorkspace';
import { useApiErrorToast } from '@/app/hooks/useApiErrorToast';
import {
    columnSamples,
    CsvEncodingError,
    CREATE_CUSTOM_FIELD,
    defaultPersonNameOrder,
    IGNORE_COLUMN,
    importFieldOptions,
    inferColumnType,
    parseCsv,
    prepareImport,
    resolveColumnTarget,
    suggestColumnTargets,
    type InferredType,
    type ParsedCsv,
    type PersonNameOrder,
} from '@/app/lib/import';
import { companyChoiceRows, duplicateMappingColumns, existingCustomTarget, importFailureKey, importMappingErrorColumns, importPresentationProblems, importReviewRows, unavailableCustomColumns, needsImportDecision, REVIEW_FILTERS, reviewPage, type ReviewFilter } from '@/app/lib/importReview';
import type {
    CustomFieldSchemaEntry,
    ImportProblem,
    ImportRowAnalysis,
    DuplicateCandidate,
    ImportColumnMapping,
    ImportDuplicateAction,
    ImportEntity,
    ImportPreviewResult,
    ImportResult,
} from '@/app/lib/types';

const MAX_ROWS = 5000;
const MAX_BYTES = 5 * 1024 * 1024;
const CUSTOM_TYPES: InferredType[] = ['text', 'number', 'date', 'boolean', 'url'];
const DUPLICATE_ACTIONS: ImportDuplicateAction[] = ['fill_empty', 'skip', 'overwrite'];

type Step = 'upload' | 'map' | 'review' | 'done';
const STEPS: Step[] = ['upload', 'map', 'review', 'done'];

type ColumnTarget = { target: string; customType: InferredType };
type RowLinkOption =
    | { kind: 'record'; id: number; label: string }
    | { kind: 'retry'; id: 'retry'; label: string };
type RowLinkSearchState =
    | { key: string; status: 'success'; results: RowLinkOption[] }
    | { key: string; status: 'error' }
    | null;

function reviewInputKey(
    workspaceKey: string,
    entity: ImportEntity,
    rows: ParsedCsv['rows'],
    mapping: ImportColumnMapping[],
    onDuplicate: ImportDuplicateAction,
    links: Record<number, number>,
    companyLinks: Record<number, number>,
    skippedRows: number[],
): string {
    return JSON.stringify([
        workspaceKey,
        entity,
        rows,
        mapping,
        onDuplicate,
        Object.entries(links).sort(([left], [right]) => Number(left) - Number(right)),
        Object.entries(companyLinks).sort(([left], [right]) => Number(left) - Number(right)),
        [...skippedRows].sort((left, right) => left - right),
    ]);
}

function createScopedRequest(requestInit?: RequestInit) {
    const controller = new AbortController();
    const upstreamSignal = requestInit?.signal;
    const abortFromUpstream = () => controller.abort(upstreamSignal?.reason);
    if (upstreamSignal?.aborted) abortFromUpstream();
    else upstreamSignal?.addEventListener('abort', abortFromUpstream, { once: true });
    return {
        controller,
        requestInit: { ...requestInit, signal: controller.signal },
        release: () => upstreamSignal?.removeEventListener('abort', abortFromUpstream),
    };
}

function requestWorkspaceKey(requestInit?: RequestInit): string {
    return new Headers(requestInit?.headers).get('X-Workspace-Id') ?? '';
}

/**
 * Props for {@link ImportDialog}. {@code onImported} fires after a successful commit so the list can refresh.
 */
export type ImportDialogProps = {
    entity: ImportEntity;
    open: boolean;
    onOpenChange: (open: boolean) => void;
    onImported: () => void;
    requestInit?: RequestInit;
};

/**
 * Multi-step CSV import wizard: upload &amp; parse, map columns to Connex fields, review the
 * deduplicated plan, then commit. Parsing is client-side; the backend validates and deduplicates.
 */
export default function ImportDialog({ entity, open, onOpenChange, onImported, requestInit }: ImportDialogProps) {
    const t = useTranslations('importExport');
    const locale = useLocale();
    const showApiError = useApiErrorToast();
    const reportFailure = (error: unknown) => {
        if (isWorkspaceUnavailableError(error)) showApiError(error);
        else toastError(t(importFailureKey(error)));
    };
    const reduceMotion = useReducedMotion();
    const canCreateCustomField = usePermission('CUSTOM_FIELD_MANAGE');
    const { activeWorkspaceId, activeWorkspace } = useWorkspace();
    const workspaceKey =
        requestWorkspaceKey(requestInit) || activeWorkspaceId?.toString() || '';
    const [step, setStep] = useState<Step>('upload');
    const [parsed, setParsed] = useState<ParsedCsv | null>(null);
    const [fileName, setFileName] = useState<string>('');
    const [parseError, setParseError] = useState<string | null>(null);
    const [parsing, setParsing] = useState(false);
    const [dragActive, setDragActive] = useState(false);
    const [columns, setColumns] = useState<Record<string, ColumnTarget>>({});
    const [nameOrder, setNameOrder] = useState<PersonNameOrder>(() => defaultPersonNameOrder([], locale));
    const [onDuplicate, setOnDuplicate] = useState<ImportDuplicateAction>('fill_empty');
    const [preview, setPreview] = useState<ImportPreviewResult | null>(null);
    const [result, setResult] = useState<ImportResult | null>(null);
    const [busy, setBusy] = useState(false);
    const [previewing, setPreviewing] = useState(false);
    const [reviewedInputKey, setReviewedInputKey] = useState<string | null>(null);
    const [reviewedProof, setReviewedProof] = useState<string | null>(null);
    const [links, setLinks] = useState<Record<number, number>>({});
    const [linkLabels, setLinkLabels] = useState<Record<number, string>>({});
    const [companyLinks, setCompanyLinks] = useState<Record<number, number>>({});
    const [skippedRows, setSkippedRows] = useState<number[]>([]);
    const [commitFailure, setCommitFailure] = useState<string | null>(null);
    const [customFieldsAttempt, setCustomFieldsAttempt] = useState(0);
    const [serverDuplicateColumns, setServerDuplicateColumns] = useState<{
        key: string; columns: Set<string>;
    } | null>(null);
    const [customFieldState, setCustomFieldState] = useState<{
        key: string; fields: CustomFieldSchemaEntry[]; failed: boolean; attempt: number;
    } | null>(null);
    const customFieldKey = `${workspaceKey}:${entity}`;
    const customFields = useMemo(() => customFieldState?.key === customFieldKey ? customFieldState.fields : [], [customFieldState, customFieldKey]);
    const customFieldsFailed = customFieldState?.key === customFieldKey && customFieldState.failed;
    const customFieldsLoading = customFieldState?.key !== customFieldKey || customFieldState.attempt !== customFieldsAttempt;
    useEffect(() => {
        if (!open) return;
        const scoped = createScopedRequest(requestInit);
        const entityType = entity === 'persons' ? 'person' : entity === 'companies' ? 'company' : 'deal';
        getCustomFieldSchema(entityType, scoped.requestInit).then((fields) => {
            if (!scoped.controller.signal.aborted) {
                setCustomFieldState({ key: customFieldKey, fields, failed: false, attempt: customFieldsAttempt });
            }
        }).catch((error: unknown) => {
            if (!scoped.controller.signal.aborted) {
                setCustomFieldState({ key: customFieldKey, fields: [], failed: true, attempt: customFieldsAttempt });
                if (isWorkspaceUnavailableError(error)) showApiError(error);
            }
        });
        return () => { scoped.controller.abort(); scoped.release(); };
    }, [open, entity, customFieldKey, requestInit, customFieldsAttempt, showApiError]);
    const previewControllerRef = useRef<AbortController | null>(null);
    const previousWorkspaceKeyRef = useRef(workspaceKey);
    const previewGenerationRef = useRef(0);

    function reset() {
        previewGenerationRef.current += 1;
        previewControllerRef.current?.abort();
        previewControllerRef.current = null;
        setStep('upload');
        setParsed(null);
        setFileName('');
        setParseError(null);
        setColumns({});
        setNameOrder(defaultPersonNameOrder([], locale));
        setOnDuplicate('fill_empty');
        setPreview(null);
        setResult(null);
        setBusy(false);
        setPreviewing(false);
        setReviewedInputKey(null);
        setReviewedProof(null);
        setLinks({});
        setLinkLabels({});
        setCompanyLinks({});
        setSkippedRows([]);
        setCommitFailure(null);
        setServerDuplicateColumns(null);
    }

    function recoverMappingFailure(error: unknown) {
        const affected = importMappingErrorColumns(error, parsed?.headers ?? []);
        if (affected.size > 0) {
            setServerDuplicateColumns({ key: mappingSnapshotKey, columns: affected });
            setStep('map');
        }
        if (error instanceof ApiError && error.code === 'IMPORT_CUSTOM_UNAVAILABLE') {
            setCustomFieldsAttempt((attempt) => attempt + 1);
        }
    }

    function refreshCustomFields() {
        setServerDuplicateColumns(null);
        setCustomFieldsAttempt((attempt) => attempt + 1);
    }

    function handleOpenChange(next: boolean) {
        if (!next) reset();
        onOpenChange(next);
    }

    async function handleFile(file: File | undefined) {
        if (!file) return;
        setParseError(null);
        if (file.size > MAX_BYTES) {
            setParseError(t('errorTooLarge', { max: '5MB' }));
            return;
        }
        setParsing(true);
        setFileName(file.name);
        try {
            const result = await parseCsv(file);
            if (result.headers.length === 0) {
                setParseError(t('errorNoColumns'));
                return;
            }
            if (result.rows.length === 0) {
                setParseError(t('errorNoRows'));
                return;
            }
            if (result.rows.length > MAX_ROWS) {
                setParseError(t('errorTooManyRows', { count: result.rows.length, max: MAX_ROWS }));
                return;
            }
            const initial: Record<string, ColumnTarget> = {};
            const suggestions = suggestColumnTargets(result.headers, entity);
            for (const header of result.headers) {
                initial[header] = {
                    target: suggestions[header] !== IGNORE_COLUMN && suggestions[header] != null
                        ? suggestions[header] : existingCustomTarget(header, customFields) ?? IGNORE_COLUMN,
                    customType: inferColumnType(columnSamples(result.rows, header)),
                };
            }
            setLinks({});
            setLinkLabels({});
            setCompanyLinks({});
            setSkippedRows([]);
            setPreview(null);
            setReviewedInputKey(null);
            setReviewedProof(null);
            setCommitFailure(null);
            setServerDuplicateColumns(null);
            setParsed(result);
            setColumns(initial);
            setNameOrder(defaultPersonNameOrder(result.headers, locale));
            setStep('map');
        } catch (error) {
            setParseError(t(error instanceof CsvEncodingError ? 'errorEncoding' : 'errorParse'));
        } finally {
            setParsing(false);
        }
    }

    const prepared = useMemo(() => {
        function buildMapping(): ImportColumnMapping[] {
            if (!parsed) return [];
            const mapping: ImportColumnMapping[] = [];
            for (const header of parsed.headers) {
                const col = columns[header];
                const target = resolveColumnTarget(col?.target, canCreateCustomField);
                if (!col || target === IGNORE_COLUMN) continue;
                if (target === CREATE_CUSTOM_FIELD) {
                    const existing = customFields.filter((field) => field.label.trim() === header.trim() && field.fieldType === col.customType);
                    mapping.push(existing.length === 1 ? { column: header, field: `custom:${existing[0].definitionId}` }
                        : { column: header, createCustomField: true, customFieldType: col.customType, customFieldLabel: header });
                } else {
                    mapping.push({ column: header, field: target });
                }
            }
            return mapping;
        }
        return parsed ? prepareImport(parsed, entity, buildMapping(), nameOrder) : null;
    }, [parsed, entity, columns, canCreateCustomField, nameOrder, customFields]);
    const preparedCsv = useMemo(
        () => parsed && prepared ? { ...parsed, rows: prepared.rows } : null,
        [parsed, prepared],
    );

    const mappedCount = useMemo(
        () =>
            Object.values(columns).filter(
                (c) => resolveColumnTarget(c.target, canCreateCustomField) !== IGNORE_COLUMN,
            ).length,
        [columns, canCreateCustomField],
    );
    const mappingSnapshotKey = JSON.stringify([workspaceKey, prepared?.mapping ?? []]);
    const duplicateColumns = new Set([...duplicateMappingColumns(prepared?.mapping ?? []),
        ...(serverDuplicateColumns?.key === mappingSnapshotKey ? serverDuplicateColumns.columns : [])]);
    const unavailableColumns = !customFieldsLoading && !customFieldsFailed
        ? unavailableCustomColumns(prepared?.mapping ?? [], customFields) : new Set<string>();
    const hasName = prepared?.nameColumn != null && !prepared.nameConflict
        && duplicateColumns.size === 0 && unavailableColumns.size === 0;
    const nameColumn = prepared?.nameColumn ?? null;
    const currentReviewInputKey = prepared
        ? reviewInputKey(
            workspaceKey,
            entity,
            prepared.rows,
            prepared.mapping,
            onDuplicate,
            links,
            companyLinks,
            skippedRows,
        )
        : null;
    const reviewStale = currentReviewInputKey !== reviewedInputKey
        || reviewedProof === null;

    useEffect(() => {
        if (previousWorkspaceKeyRef.current === workspaceKey) return;
        previousWorkspaceKeyRef.current = workspaceKey;
        previewGenerationRef.current += 1;
        previewControllerRef.current?.abort();
        previewControllerRef.current = null;
        setBusy(false);
        setPreviewing(false);
        setReviewedInputKey(null);
        setReviewedProof(null);
    }, [workspaceKey]);

    async function goToReview() {
        if (!prepared || !hasName || !currentReviewInputKey) return;
        const inputKey = currentReviewInputKey;
        const generation = previewGenerationRef.current + 1;
        previewGenerationRef.current = generation;
        previewControllerRef.current?.abort();
        const scopedRequest = createScopedRequest(requestInit);
        previewControllerRef.current = scopedRequest.controller;
        setBusy(true);
        try {
            const data = await previewImport(
                entity,
                { rows: prepared.rows, mapping: prepared.mapping, onDuplicate, links, companyLinks, skippedRows },
                scopedRequest.requestInit,
            );
            if (scopedRequest.controller.signal.aborted || generation !== previewGenerationRef.current) return;
            if (!data.duplicateReviewProof) {
                throw new Error('Duplicate review proof is missing');
            }
            setCommitFailure(null);
            setPreview(data);
            setReviewedInputKey(inputKey);
            setReviewedProof(data.duplicateReviewProof ?? null);
            setStep('review');
        } catch (error) {
            if (scopedRequest.controller.signal.aborted || generation !== previewGenerationRef.current) return;
            recoverMappingFailure(error);
            reportFailure(error);
        } finally {
            scopedRequest.release();
            if (previewControllerRef.current === scopedRequest.controller) {
                previewControllerRef.current = null;
                if (!requestInit?.signal?.aborted) setBusy(false);
            }
        }
    }

    async function runPreview() {
        if (!prepared || !hasName || !currentReviewInputKey) return;
        const inputKey = currentReviewInputKey;
        const generation = previewGenerationRef.current + 1;
        previewGenerationRef.current = generation;
        previewControllerRef.current?.abort();
        const scopedRequest = createScopedRequest(requestInit);
        previewControllerRef.current = scopedRequest.controller;
        setPreviewing(true);
        try {
            const data = await previewImport(
                entity,
                { rows: prepared.rows, mapping: prepared.mapping, onDuplicate, links, companyLinks, skippedRows },
                scopedRequest.requestInit,
            );
            if (scopedRequest.controller.signal.aborted || generation !== previewGenerationRef.current) return;
            if (!data.duplicateReviewProof) {
                throw new Error('Duplicate review proof is missing');
            }
            setCommitFailure(null);
            setPreview(data);
            setReviewedInputKey(inputKey);
            setReviewedProof(data.duplicateReviewProof ?? null);
        } catch (error) {
            if (scopedRequest.controller.signal.aborted || generation !== previewGenerationRef.current) return;
            recoverMappingFailure(error);
            reportFailure(error);
        } finally {
            scopedRequest.release();
            if (previewControllerRef.current === scopedRequest.controller) {
                previewControllerRef.current = null;
                if (!requestInit?.signal?.aborted) setPreviewing(false);
            }
        }
    }

    function selectAction(action: ImportDuplicateAction) {
        setOnDuplicate(action);
    }

    function returnToStep(nextStep: 'upload' | 'map') {
        previewGenerationRef.current += 1;
        previewControllerRef.current?.abort();
        previewControllerRef.current = null;
        setBusy(false);
        setPreviewing(false);
        setStep(nextStep);
    }

    function linkRow(rowIndex: number, recordId: number, label: string) {
        const nextLinks = { ...links, [rowIndex]: recordId };
        setLinks(nextLinks);
        setLinkLabels((prev) => ({ ...prev, [rowIndex]: label }));
    }

    function unlinkRow(rowIndex: number) {
        const nextLinks = { ...links };
        delete nextLinks[rowIndex];
        setLinks(nextLinks);
        setLinkLabels((prev) => {
            const next = { ...prev };
            delete next[rowIndex];
            return next;
        });
    }

    async function commit() {
        if (!prepared
                || !hasName
                || !currentReviewInputKey
                || currentReviewInputKey !== reviewedInputKey
                || reviewedProof === null) return;
        setBusy(true);
        try {
            const data = await commitImport(
                entity,
                {
                    rows: prepared.rows,
                    mapping: prepared.mapping,
                    onDuplicate,
                    links,
                    companyLinks,
                    skippedRows,
                    duplicateReviewProof: reviewedProof ?? undefined,
                },
                requestInit,
            );
            if (requestInit?.signal?.aborted) return;
            setResult(data);
            setStep('done');
            toastSuccess(t('toastImported', { created: data.created, updated: data.updated }));
            onImported();
        } catch (error) {
            if (requestInit?.signal?.aborted) return;
            setReviewedProof(null);
            setCommitFailure(importFailureKey(error));
            recoverMappingFailure(error);
            reportFailure(error);
        } finally {
            if (!requestInit?.signal?.aborted) setBusy(false);
        }
    }

    return (
        <Dialog open={open} onOpenChange={handleOpenChange}>
            <DialogContent
                className="max-h-[calc(100dvh-2rem)] grid-rows-[auto_minmax(0,1fr)_auto] gap-0 overflow-hidden p-0 sm:max-w-3xl"
                onEscapeKeyDown={(event) => {
                    const target = event.target instanceof Element ? event.target : null;
                    if (target?.closest('[data-row-linker-input][aria-expanded="true"]')) event.preventDefault();
                }}
            >
                <DialogHeader className="border-b border-border px-6 py-4">
                    <DialogTitle>{t(`title.${entity}`)}</DialogTitle>
                    <DialogDescription className="sr-only">{t('description')}</DialogDescription>
                    <StepRail step={step} />
                    {entity === 'deals' && (
                        <p className="text-sm text-muted-foreground">
                            {t('dealCurrencyDefault', { currency: preview?.defaultCurrency ?? workspaceDefaultCurrency(activeWorkspace) })}
                        </p>
                    )}
                    {parsed && (step === 'map' || step === 'review') && (
                        <p className="mt-2 text-xs text-muted-foreground">
                            {t('detectedEncoding', { encoding: t(`encoding.${parsed.encoding}`) })}
                        </p>
                    )}
                </DialogHeader>

                <div className="max-h-[60vh] min-h-0 min-w-0 overflow-y-auto px-4 py-5 sm:px-6">
                    <motion.div
                        key={step}
                        initial={{ opacity: 0, y: reduceMotion ? 0 : 6 }}
                        animate={{ opacity: 1, y: 0 }}
                        transition={{ duration: 0.2, ease: [0.23, 1, 0.32, 1] }}
                    >
                    {step === 'upload' && (
                        <UploadStep
                            parsing={parsing}
                            dragActive={dragActive}
                            error={parseError}
                            onPick={handleFile}
                            setDragActive={setDragActive}
                        />
                    )}
                    {step === 'map' && parsed && (
                        <MapStep
                            parsed={parsed}
                            entity={entity}
                            columns={columns}
                            setColumns={(updater) => {
                                setServerDuplicateColumns(null);
                                setColumns(updater);
                            }}
                            mappedCount={mappedCount}
                            canCreateCustomField={canCreateCustomField}
                            nameOrder={nameOrder}
                            setNameOrder={setNameOrder}
                            usesNameParts={prepared?.usesNameParts ?? false}
                            nameConflict={prepared?.nameConflict ?? false}
                            nameSamples={prepared && nameColumn ? columnSamples(prepared.rows, nameColumn, 3) : []}
                            customFields={customFields}
                            customFieldsFailed={customFieldsFailed}
                            retryCustomFields={refreshCustomFields}
                            customFieldsLoading={customFieldsLoading}
                            duplicateColumns={duplicateColumns}
                            unavailableColumns={unavailableColumns}
                        />
                    )}
                    {step === 'review' && commitFailure && (
                        <p className="mb-4 break-words text-sm text-destructive" role="alert">{t(commitFailure)}</p>
                    )}
                    {step === 'review' && preview && (
                        <fieldset disabled={busy || previewing} className="min-w-0">
                        <ReviewStep preview={preview} stale={currentReviewInputKey !== reviewedInputKey} onDuplicate={onDuplicate} onSelectAction={selectAction} previewing={previewing} entity={entity} parsed={preparedCsv} nameColumn={nameColumn} links={links} linkLabels={linkLabels} onLink={linkRow} onUnlink={unlinkRow} requestInit={requestInit}
                            source={parsed} companyLinks={companyLinks} skippedRows={skippedRows}
                            onCompanyChoice={(indices, id) => setCompanyLinks((previous) => {
                                const next = { ...previous };
                                for (const index of indices) {
                                    if (id == null) delete next[index];
                                    else next[index] = id;
                                }
                                return next;
                            })}
                            onSkip={(index) => setSkippedRows((previous) => previous.includes(index)
                                ? previous.filter((row) => row !== index) : [...previous, index])}
                            onEdit={(index, column, value) => {
                                setParsed((previous) => previous ? { ...previous,
                                    rows: previous.rows.map((row, rowIndex) => rowIndex === index ? { ...row, [column]: value } : row),
                                } : null);
                                setCompanyLinks((previous) => {
                                    const next = { ...previous }; delete next[index]; return next;
                                });
                            }} />
                        </fieldset>
                    )}
                    {step === 'done' && result && <DoneStep result={result} entity={entity} parsed={parsed} />}
                    </motion.div>
                </div>

                <div className="flex flex-wrap items-center justify-end gap-3 border-t border-border px-4 py-4 sm:justify-between sm:px-6">
                    <span className="w-full truncate text-xs text-muted-foreground sm:w-auto">{fileName && step !== 'done' ? fileName : ''}</span>
                    <div className="flex flex-wrap items-center justify-end gap-2">
                        {step === 'map' && (
                            <>
                                <Button variant="ghost" onClick={() => returnToStep('upload')}>{t('back')}</Button>
                                <Button onClick={goToReview} disabled={busy || !hasName}>
                                    {busy && <LoaderCircle className="size-4 animate-spin" />}
                                    {t('next')}
                                </Button>
                            </>
                        )}
                        {step === 'review' && preview && (
                            <>
                                <Button variant="ghost" disabled={busy || previewing} onClick={() => returnToStep('map')}>{t('back')}</Button>
                                {reviewStale && (
                                    <Button variant="outline" onClick={runPreview} disabled={busy || previewing}>
                                        {previewing && <LoaderCircle className="size-4 animate-spin" />}
                                        {t('refreshReview')}
                                    </Button>
                                )}
                                <Button onClick={commit} disabled={busy || previewing || reviewStale || preview.toCreate + preview.toUpdate === 0}>
                                    {busy && <LoaderCircle className="size-4 animate-spin" />}
                                    {t('import')}
                                </Button>
                            </>
                        )}
                        {step === 'done' && <Button onClick={() => handleOpenChange(false)}>{t('done')}</Button>}
                    </div>
                </div>
            </DialogContent>
        </Dialog>
    );
}

function StepRail({ step }: { step: Step }) {
    const t = useTranslations('importExport');
    const current = STEPS.indexOf(step);
    return (
        <div className="mt-3 flex items-center gap-2" aria-hidden>
            {STEPS.map((s, i) => (
                <Fragment key={s}>
                    <div className="flex items-center gap-2">
                        <span
                            className={cn(
                                'flex size-5 shrink-0 items-center justify-center rounded-full text-xs font-semibold transition-colors',
                                i < current && 'bg-brand text-brand-foreground',
                                i === current && 'bg-foreground text-background',
                                i > current && 'bg-muted text-muted-foreground',
                            )}
                        >
                            {i + 1}
                        </span>
                        <span className={cn('text-xs', i === current ? 'font-medium text-foreground' : 'text-muted-foreground')}>
                            {t(`step.${s}`)}
                        </span>
                    </div>
                    {i < STEPS.length - 1 && <span className="h-px flex-1 bg-border" />}
                </Fragment>
            ))}
        </div>
    );
}

function UploadStep({
    parsing,
    dragActive,
    error,
    onPick,
    setDragActive,
}: {
    parsing: boolean;
    dragActive: boolean;
    error: string | null;
    onPick: (file: File | undefined) => void;
    setDragActive: (active: boolean) => void;
}) {
    const t = useTranslations('importExport');
    const inputRef = useRef<HTMLInputElement>(null);
    return (
        <div className="space-y-3">
            <button
                type="button"
                onClick={() => inputRef.current?.click()}
                onDragOver={(e) => { e.preventDefault(); setDragActive(true); }}
                onDragLeave={(e) => { e.preventDefault(); setDragActive(false); }}
                onDrop={(e) => { e.preventDefault(); setDragActive(false); onPick(e.dataTransfer.files?.[0]); }}
                disabled={parsing}
                className={cn(
                    'flex w-full cursor-pointer flex-col items-center justify-center gap-3 rounded-xl border-2 border-dashed px-4 py-12 text-sm transition-colors',
                    'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring',
                    dragActive
                        ? 'border-brand bg-brand/5 text-brand-text'
                        : 'border-border text-muted-foreground hover:border-muted-foreground/40 hover:bg-muted/60',
                    'disabled:pointer-events-none disabled:opacity-60',
                )}
            >
                {parsing ? <LoaderCircle className="size-6 animate-spin" /> : <ArrowUpTrayIcon className="size-6" />}
                <span className="font-medium text-foreground">{parsing ? t('parsing') : t('dropPrompt')}</span>
                <span className="text-xs">{t('uploadHint')}</span>
            </button>
            <input
                ref={inputRef}
                type="file"
                accept=".csv,text/csv"
                className="hidden"
                onChange={(e) => { onPick(e.target.files?.[0]); e.target.value = ''; }}
            />
            {error && (
                <p className="flex items-start gap-2 text-sm text-destructive">
                    <ExclamationTriangleIcon className="mt-0.5 size-4 shrink-0" />
                    {error}
                </p>
            )}
        </div>
    );
}

function MapStep({
    parsed,
    entity,
    columns,
    setColumns,
    mappedCount,
    canCreateCustomField,
    nameOrder,
    setNameOrder,
    usesNameParts,
    nameConflict,
    nameSamples,
    customFields,
    customFieldsFailed,
    retryCustomFields,
    customFieldsLoading,
    duplicateColumns,
    unavailableColumns,
}: {
    parsed: ParsedCsv;
    entity: ImportEntity;
    columns: Record<string, ColumnTarget>;
    setColumns: (updater: (prev: Record<string, ColumnTarget>) => Record<string, ColumnTarget>) => void;
    mappedCount: number;
    canCreateCustomField: boolean;
    nameOrder: PersonNameOrder;
    setNameOrder: (order: PersonNameOrder) => void;
    usesNameParts: boolean;
    nameConflict: boolean;
    nameSamples: string[];
    customFields: CustomFieldSchemaEntry[];
    customFieldsFailed: boolean;
    retryCustomFields: () => void;
    customFieldsLoading: boolean;
    duplicateColumns: Set<string>;
    unavailableColumns: Set<string>;
}) {
    const t = useTranslations('importExport');
    const nameOrderId = useId();
    const fieldOptions = useMemo(
        () => importFieldOptions(entity).map((key) => ({ key, label: t(`fields.${key}`) })),
        [entity, t],
    );

    function setTarget(header: string, target: string) {
        setColumns((prev) => ({ ...prev, [header]: { ...prev[header], target } }));
    }
    function setType(header: string, value: string) {
        const customType = CUSTOM_TYPES.find((type) => type === value) ?? 'text';
        setColumns((prev) => ({ ...prev, [header]: { ...prev[header], customType } }));
    }

    return (
        <div className="space-y-4">
            <p className="text-sm text-muted-foreground">{t(entity === 'persons' ? 'mapPersonHint' : 'mapHint', { mapped: mappedCount, total: parsed.headers.length })}</p>
            <p className="text-xs text-muted-foreground">{t('mappingSuggestionHint')}</p>
            {usesNameParts && (
                <div className="space-y-2 rounded-lg border border-border p-3">
                    <p className="text-sm font-medium" id={nameOrderId}>{t('nameOrder')}</p>
                    <Select value={nameOrder} onValueChange={(value) => {
                        if (value === 'given_family' || value === 'family_given') setNameOrder(value);
                    }}>
                        <SelectTrigger aria-labelledby={nameOrderId} className="w-full sm:w-64">
                            <SelectValue />
                        </SelectTrigger>
                        <SelectContent>
                            <SelectItem value="given_family">{t('nameOrderGivenFamily')}</SelectItem>
                            <SelectItem value="family_given">{t('nameOrderFamilyGiven')}</SelectItem>
                        </SelectContent>
                    </Select>
                    {!nameConflict && nameSamples.length > 0 && (
                        <p className="break-words text-sm" role="status">{t('namePreview', { names: nameSamples.join(', ') })}</p>
                    )}
                    <p className="text-xs text-muted-foreground">{t('nameCompositionHint')}</p>
                </div>
            )}
            {nameConflict && <p className="text-sm text-destructive" role="alert">{t('nameMappingConflict')}</p>}
            {entity === 'deals' && <p className="text-xs text-muted-foreground">{t('dealDateHint')}</p>}
            <Button variant="outline" size="toolbar" disabled={customFieldsLoading}
                aria-busy={customFieldsLoading} onClick={retryCustomFields}>
                {customFieldsLoading && <LoaderCircle className="size-4 animate-spin" />}
                {t(customFieldsLoading ? 'refreshingCustomFields' : 'refreshCustomFields')}
            </Button>
            {customFieldsFailed && <p role="alert" className="text-sm text-destructive">{t('customFieldsFailed')}</p>}
            <div className="space-y-2">
                {parsed.headers.map((header) => {
                    const col = columns[header];
                    const target = resolveColumnTarget(col?.target, canCreateCustomField);
                    const samples = columnSamples(parsed.rows, header, 3).join(', ');
                    return (
                        <div key={header} data-import-column={header} className="grid min-w-0 grid-cols-1 items-center gap-3 rounded-lg border border-border px-3 py-2 sm:grid-cols-[1fr_auto_1fr]">
                            <div className="min-w-0">
                                <p className="truncate text-sm font-medium">{header}</p>
                                {samples && <p className="truncate text-xs text-muted-foreground">{samples}</p>}
                            </div>
                            <span className="hidden text-muted-foreground sm:block" aria-hidden>→</span>
                            <div className="flex min-w-0 flex-wrap items-center gap-2">
                                <Select value={target} onValueChange={(v) => setTarget(header, v)}>
                                    <SelectTrigger size="sm" className="w-full" aria-label={t('mapColumn', { column: header })}>
                                        <SelectValue />
                                    </SelectTrigger>
                                    <SelectContent>
                                        <SelectItem value={IGNORE_COLUMN}>{t('ignore')}</SelectItem>
                                        {unavailableColumns.has(header) && <SelectItem value={target} disabled>{t('unavailableCustomField')}</SelectItem>}
                                        {fieldOptions.map((f) => (
                                            <SelectItem key={f.key} value={f.key}>{f.label}</SelectItem>
                                        ))}
                                        {customFields.map((field) => (
                                            <SelectItem key={field.definitionId} value={`custom:${field.definitionId}`}>
                                                {t('existingCustomField', { name: field.label })}
                                            </SelectItem>
                                        ))}
                                        {canCreateCustomField && (
                                            <SelectItem value={CREATE_CUSTOM_FIELD}>{t('createCustomField')}</SelectItem>
                                        )}
                                    </SelectContent>
                                </Select>
                                {col && target === CREATE_CUSTOM_FIELD && (
                                    <Select value={col.customType} onValueChange={(v) => setType(header, v)}>
                                        <SelectTrigger size="sm" className="w-28">
                                            <SelectValue />
                                        </SelectTrigger>
                                        <SelectContent>
                                            {CUSTOM_TYPES.map((type) => (
                                                <SelectItem key={type} value={type}>{t(`type.${type}`)}</SelectItem>
                                            ))}
                                        </SelectContent>
                                    </Select>
                                )}
                                {duplicateColumns.has(header) && <p role="alert" className="w-full text-sm text-destructive">{t('duplicateMapping')}</p>}
                                {unavailableColumns.has(header) && <p role="alert" className="w-full text-sm text-destructive">{t('unavailableCustomFieldHint')}</p>}
                                {target === CREATE_CUSTOM_FIELD && customFields.filter((field) => field.label.trim() === header.trim() && field.fieldType === col?.customType).length === 1 && (
                                    <p className="w-full text-xs text-muted-foreground">{t('reuseCustomField')}</p>
                                )}
                            </div>
                        </div>
                    );
                })}
            </div>
        </div>
    );
}

function ReviewStep({
    preview,
    stale,
    onDuplicate,
    onSelectAction,
    previewing,
    entity,
    parsed,
    nameColumn,
    links,
    linkLabels,
    onLink,
    onUnlink,
    requestInit,
    source,
    companyLinks,
    skippedRows,
    onCompanyChoice,
    onSkip,
    onEdit,
}: {
    source: ParsedCsv | null;
    companyLinks: Record<number, number>;
    skippedRows: number[];
    onCompanyChoice: (indices: number[], id: number | null) => void;
    onSkip: (index: number) => void;
    onEdit: (index: number, column: string, value: string) => void;
    preview: ImportPreviewResult;
    stale: boolean;
    onDuplicate: ImportDuplicateAction;
    onSelectAction: (action: ImportDuplicateAction) => void;
    previewing: boolean;
    entity: ImportEntity;
    parsed: ParsedCsv | null;
    nameColumn: string | null;
    links: Record<number, number>;
    linkLabels: Record<number, string>;
    onLink: (rowIndex: number, recordId: number, label: string) => void;
    onUnlink: (rowIndex: number) => void;
    requestInit?: RequestInit;
}) {
    const t = useTranslations('importExport');
    const formatter = useFormatter();
    const missingCloseDates = preview.rows.filter((row) => row.dealDates?.usesImportTime).length;
    const skipped = useMemo(() => new Set(skippedRows), [skippedRows]);
    const [filter, setFilter] = useState<ReviewFilter>('all');
    const [page, setPage] = useState(0);
    const rows = useMemo(() => importReviewRows(preview), [preview]);
    const pagination = reviewPage(rows, filter, page);
    const shown = pagination.rows;
    return (
        <div className="space-y-5">
            {stale && (
                <div className="rounded-lg border border-warning/40 bg-warning/5 px-3 py-2 text-sm text-foreground" role="status">
                    {t('reviewStale')}
                </div>
            )}
            <div className="space-y-2">
                <p className="text-sm font-medium">{t('onMatch')}</p>
                <div className="flex flex-wrap gap-2">
                    {DUPLICATE_ACTIONS.map((action) => (
                        <Button
                            key={action}
                            size="toolbar"
                            variant={onDuplicate === action ? 'default' : 'outline'}
                            disabled={previewing}
                            onClick={() => onSelectAction(action)}
                        >
                            {t(`action.${action}`)}
                        </Button>
                    ))}
                </div>
            </div>

            <div className="flex flex-wrap gap-x-6 gap-y-2 text-sm">
                <Count label={t('countNew')} value={preview.toCreate} className="text-brand-text" />
                <Count label={t('countUpdate')} value={preview.toUpdate} className="text-foreground" />
                <Count label={t('countSkip')} value={preview.toSkip} className="text-muted-foreground" />
                <Count label={t('countInvalid')} value={preview.invalid} className="text-destructive" />
            </div>

            {missingCloseDates > 0 && (
                <div className="rounded-lg border border-warning/40 bg-warning/5 px-3 py-2 text-sm text-foreground" role="status">
                    {t('missingCloseDates', { count: missingCloseDates })}
                </div>
            )}

            <p className="text-xs text-muted-foreground">{t('reviewTotal', { count: preview.total })}</p>
            <Select value={filter} onValueChange={(value) => {
                const next = REVIEW_FILTERS.find((item) => item === value);
                if (next) { setFilter(next); setPage(0); }
            }}>
                <SelectTrigger className="w-full sm:w-64" aria-label={t('reviewFilter')}><SelectValue /></SelectTrigger>
                <SelectContent>
                    {REVIEW_FILTERS.map((value) => <SelectItem key={value} value={value}>
                        {t(`reviewFilters.${value}`)} ({value === 'all' ? rows.length
                            : rows.filter((row) => value === 'decision' ? needsImportDecision(row) : row.status === value).length})
                    </SelectItem>)}
                </SelectContent>
            </Select>
            <nav aria-label={t('reviewPagination')} className="flex flex-wrap items-center justify-between gap-2">
                <Button size="toolbar" variant="outline" disabled={pagination.current === 0} onClick={() => setPage(pagination.current - 1)}>{t('previousPage')}</Button>
                <span className="text-xs text-muted-foreground" role="status">{t('reviewPage', { page: pagination.current + 1, pages: pagination.pages, total: pagination.total })}</span>
                <Button size="toolbar" variant="outline" disabled={pagination.current + 1 >= pagination.pages} onClick={() => setPage(pagination.current + 1)}>{t('nextPage')}</Button>
            </nav>
            {shown.length === 0 && <p className="text-sm text-muted-foreground">{t('reviewEmpty')}</p>}
            {shown.length > 0 && (
                <div className="space-y-2">
                    <div className="overflow-hidden rounded-lg border border-border">
                        <table role="table" className="block w-full text-sm sm:table">
                            <caption className="sr-only">{t('reviewTableCaption')}</caption>
                            <thead className="sr-only">
                                <tr>
                                    <th scope="col">{t('rowColRow')}</th>
                                    <th scope="col">{t('rowColStatus')}</th>
                                    <th scope="col">{t('rowColDetail')}</th>
                                    <th scope="col">{t('rowColLink')}</th>
                                </tr>
                            </thead>
                            <tbody role="rowgroup" className="block sm:table-row-group">
                                {shown.map((row) => (
                                    <tr key={row.rowIndex} role="row" data-import-row={row.rowIndex} className="grid grid-cols-[auto_1fr] border-b border-border last:border-0 sm:table-row">
                                        <td role="cell" className="px-3 pt-3 text-xs text-muted-foreground sm:w-12 sm:py-2 sm:align-middle">#{row.rowIndex + 1}</td>
                                        <td role="cell" className="px-3 pt-3 sm:w-20 sm:py-2 sm:align-middle">
                                            <Badge variant={row.status === 'invalid' ? 'destructive' : row.status === 'match' ? 'secondary' : 'outline'}>
                                                {t(`rowStatus.${row.status}`)}
                                            </Badge>
                                        </td>
                                        <td role="cell" className="col-span-2 min-w-0 px-3 py-2 sm:max-w-0 sm:align-middle">
                                            <div className="min-w-0 space-y-2">
                                                <p className="break-words text-sm font-medium">
                                                    {nameColumn && parsed ? parsed.rows[row.rowIndex]?.[nameColumn] ?? '' : row.matchedLabel}
                                                </p>
                                                {row.matchedId != null && <p className="break-words text-xs text-muted-foreground">{t('linkedTo', { name: row.matchedLabel ?? '' })}</p>}
                                                {importPresentationProblems(row).map((problem) => <p key={`${row.rowIndex}:${problem.code}:${problem.field ?? ''}`} className="break-words text-sm text-destructive">
                                                    {problemText(t, problem)}
                                                </p>)}
                                                {row.dealDates && (
                                                    <div className="space-y-1 text-xs text-muted-foreground">
                                                        {(['closedAt', 'createdAt'] as const).map((field) => {
                                                            const value = row.dealDates?.[field];
                                                            if (!value) return null;
                                                            return (
                                                                <p key={field}>
                                                                    {t(`fields.${field}`)}: {formatter.dateTime(new Date(value), {
                                                                        year: 'numeric', month: '2-digit', day: '2-digit',
                                                                        hour: '2-digit', minute: '2-digit', hourCycle: 'h23',
                                                                        timeZone: row.dealDates?.timezone,
                                                                    })} ({row.dealDates?.timezone})
                                                                </p>
                                                            );
                                                        })}
                                                        {row.dealDates.usesImportTime && <p>{t('rowUsesImportTime')}</p>}
                                                        {row.dealDates.futureClosedAt && (
                                                            <p className="text-warning">{t('futureCloseDate')}</p>
                                                        )}
                                                    </div>
                                                )}
                                                {row.canonicalRowIndex != null && row.mergedRowCount != null && (
                                                    <p className="text-xs text-muted-foreground">
                                                        {row.rowIndex === row.canonicalRowIndex
                                                            ? t('rowMergeRepresentative', { count: row.mergedRowCount })
                                                            : t('rowMergeContributor', { row: row.canonicalRowIndex + 1 })}
                                                    </p>
                                                )}
                                                {row.candidates && row.candidates.length > 0 && row.status !== 'match' && row.status !== 'skip' && (
                                                    <details>
                                                        <summary className="cursor-pointer py-2 text-xs font-medium">{t('showCandidates', { count: row.candidates.length })}</summary>
                                                    <DuplicateCandidates
                                                        entity={entity}
                                                        candidates={row.candidates}
                                                        linkedId={links[row.rowIndex]}
                                                        onLink={(candidate) => onLink(
                                                            row.rowIndex,
                                                            candidate.recordId,
                                                            candidate.name,
                                                        )}
                                                    />
                                                    </details>
                                                )}
                                                {row.companyDependency && <CompanyChoices row={row} allRows={rows}
                                                    selectedId={companyLinks[row.rowIndex]} onChoice={onCompanyChoice} />}
                                                {source && <details>
                                                    <summary className="cursor-pointer py-2 text-xs font-medium">{t('editRow')}</summary>
                                                    <div className="space-y-3 py-2">
                                                        <p className="text-xs text-muted-foreground">{t('editRowHint')}</p>
                                                        {source.headers.map((column) => <label key={column} className="block space-y-1 text-xs">
                                                            <span className="break-words">{column}</span>
                                                            <Input value={source.rows[row.rowIndex]?.[column] ?? ''}
                                                                onChange={(event) => {
                                                                    if (event.target.value !== (source.rows[row.rowIndex]?.[column] ?? '')) onEdit(row.rowIndex, column, event.target.value);
                                                                }} />
                                                        </label>)}
                                                    </div>
                                                </details>}
                                            </div>
                                        </td>
                                        <td role="cell" className="col-span-2 min-w-0 px-3 pb-3 text-left sm:w-56 sm:py-2 sm:text-right sm:align-middle">
                                            <div className="flex flex-wrap items-center gap-2 sm:justify-end">
                                            <Button size="toolbar" variant="outline" onClick={() => onSkip(row.rowIndex)}>
                                                {t(skipped.has(row.rowIndex) ? 'includeRow' : 'skipRow')}
                                            </Button>
                                            {!skipped.has(row.rowIndex) && <RowLinker
                                                entity={entity}
                                                linkedLabel={links[row.rowIndex] != null ? linkLabels[row.rowIndex] : undefined}
                                                onLink={(id, label) => onLink(row.rowIndex, id, label)}
                                                onClear={() => onUnlink(row.rowIndex)}
                                                requestInit={requestInit}
                                            />}
                                            </div>
                                        </td>
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                    </div>
                </div>
            )}
        </div>
    );
}

function problemText(t: ReturnType<typeof useTranslations>, problem: ImportProblem): string {
    const key = `problems.${problem.code}`;
    return t(t.has(key) ? key : 'problems.ROW_INVALID', { field: problem.field ?? '' });
}

function CompanyChoices({ row, allRows, selectedId, onChoice }: {
    row: ImportRowAnalysis;
    allRows: ImportRowAnalysis[];
    selectedId?: number;
    onChoice: (indices: number[], id: number | null) => void;
}) {
    const t = useTranslations('importExport');
    const dependency = row.companyDependency;
    if (!dependency) return null;
    const selected = dependency.candidates?.find((candidate) => candidate.recordId === selectedId);
    const indices = selectedId == null ? [] : companyChoiceRows(allRows, row, selectedId);
    return <details open={selectedId == null}>
        <summary className="cursor-pointer py-2 text-xs font-medium">
            {t('companyChoice', { name: dependency.name })}
            {selected && <span className="block break-words font-normal text-muted-foreground">
                {t('linkedTo', { name: [selected.name, selected.industry, selected.website].filter(Boolean).join(' · ') })}
            </span>}
        </summary>
        <p className="mb-2 text-xs text-muted-foreground">{t(dependency.truncated ? 'problems.COMPANY_TRUNCATED' : 'companyChoiceHint')}</p>
        {!dependency.truncated && <DuplicateCandidates entity="companies" candidates={dependency.candidates ?? []}
            linkedId={selectedId} allowShared onLink={(candidate) => onChoice([row.rowIndex], candidate.recordId)} />}
        {selected && <div className="mt-2 space-y-2">
            <p className="break-words text-xs">{t('bulkCompanyReview', { name: selected.name, count: indices.length })}</p>
            <div className="flex flex-wrap gap-2">
                {indices.length > 1 && <Button size="toolbar" variant="outline" onClick={() => onChoice(indices, selected.recordId)}>
                    {t('applyCompanyChoice', { count: indices.length })}
                </Button>}
                <Button size="toolbar" variant="ghost" onClick={() => onChoice([row.rowIndex], null)}>{t('clearCompanyChoice')}</Button>
            </div>
        </div>}
    </details>;
}

function DuplicateCandidates({
    entity,
    candidates,
    linkedId,
    onLink,
    allowShared = false,
}: {
    allowShared?: boolean;
    entity: ImportEntity;
    candidates: DuplicateCandidate[];
    linkedId?: number;
    onLink: (candidate: DuplicateCandidate) => void;
}) {
    const t = useTranslations('importExport');
    return (
        <div className="space-y-1.5" aria-label={t('candidateListLabel')}>
            {candidates.map((candidate) => {
                const context = [
                    candidate.title,
                    candidate.companyName,
                    candidate.industry,
                    candidate.website,
                ].filter((value): value is string => Boolean(value));
                const selected = linkedId === candidate.recordId;
                return (
                    <div
                        key={`${candidate.recordType}-${candidate.recordId}`}
                        data-import-candidate={candidate.recordId}
                        className="flex min-w-0 items-start justify-between gap-3 rounded-md border border-border bg-muted/40 px-2.5 py-2"
                    >
                        <div className="min-w-0 space-y-1">
                            <div className="flex min-w-0 flex-wrap items-center gap-1.5">
                                <span className="break-words text-xs font-medium text-foreground">
                                    {candidate.name}
                                </span>
                                <Badge variant="outline" className="h-5 px-1.5 text-[10px]">
                                    {t(`candidateRecordType.${candidate.recordType}`)}
                                </Badge>
                                <Badge
                                    variant={candidate.strength === 'STRONG' ? 'secondary' : 'outline'}
                                    className="h-5 px-1.5 text-[10px]"
                                >
                                    {t(candidate.recordType === 'company' && candidate.strength === 'WEAK'
                                        ? 'candidateCompanyNameStrength' : `candidateStrength.${candidate.strength}`)}
                                </Badge>
                                {!candidate.ownedByActiveWorkspace && (
                                    <span className="text-[11px] text-muted-foreground">
                                        {t(allowShared ? 'dependencyShared' : 'candidateShared')}
                                    </span>
                                )}
                            </div>
                            {context.length > 0 && (
                                <p className="break-words text-[11px] text-muted-foreground">
                                    {context.join(' · ')}
                                </p>
                            )}
                            <p className="text-[11px] text-muted-foreground">
                                {candidate.matches
                                    .map((match) => t(candidate.recordType === 'company' && match.kind === 'NAME'
                                        ? 'candidateCompanyNameEvidence' : `candidateEvidence.${match.kind}`))
                                    .join(' · ')}
                            </p>
                        </div>
                        {(candidate.ownedByActiveWorkspace || allowShared)
                                && candidate.recordType === (
                                    entity === 'companies' ? 'company' : entity === 'persons' ? 'person' : ''
                                ) && (
                            <Button
                                size="toolbar"
                                variant="outline"
                                disabled={selected}
                                onClick={() => onLink(candidate)}
                                className="shrink-0"
                            >
                                {selected ? t('candidateSelected') : t('candidateUse')}
                            </Button>
                        )}
                    </div>
                );
            })}
        </div>
    );
}

function RowLinker({
    entity,
    linkedLabel,
    onLink,
    onClear,
    requestInit,
}: {
    entity: ImportEntity;
    linkedLabel?: string;
    onLink: (recordId: number, label: string) => void;
    onClear: () => void;
    requestInit?: RequestInit;
}) {
    const t = useTranslations('importExport');
    const [open, setOpen] = useState(false);
    const [query, setQuery] = useState('');
    const [remoteSearch, setRemoteSearch] = useState<RowLinkSearchState>(null);
    const [searchAttempt, setSearchAttempt] = useState(0);
    const inputRef = useRef<HTMLInputElement>(null);
    const actionButtonRef = useRef<HTMLButtonElement>(null);
    const queryText = query.trim();
    const queryReady = queryText.length >= 2;
    const requestKey = queryReady
        ? [requestWorkspaceKey(requestInit), entity, queryText, searchAttempt].join('\u0000')
        : null;

    useEffect(() => {
        if (!open || requestKey === null) return;
        const scopedRequest = createScopedRequest(requestInit);
        const upstreamSignal = requestInit?.signal;
        const closeFromUpstream = () => {
            setOpen(false);
            setRemoteSearch(null);
        };
        if (upstreamSignal?.aborted) {
            scopedRequest.controller.abort(upstreamSignal.reason);
            closeFromUpstream();
            return;
        }
        upstreamSignal?.addEventListener('abort', closeFromUpstream, { once: true });
        const handle = setTimeout(() => {
            search(queryText, scopedRequest.requestInit)
                .then((r) => {
                    if (scopedRequest.controller.signal.aborted) return;
                    const items = entity === 'persons' ? r.people : entity === 'companies' ? r.companies : r.deals;
                    setRemoteSearch({
                        key: requestKey,
                        status: 'success',
                        results: items.slice(0, 6).map((item) => ({
                            kind: 'record',
                            id: item.id,
                            label: item.name || `#${item.id}`,
                        })),
                    });
                })
                .catch(() => {
                    if (scopedRequest.controller.signal.aborted) return;
                    setRemoteSearch({ key: requestKey, status: 'error' });
                });
        }, 250);
        return () => {
            clearTimeout(handle);
            upstreamSignal?.removeEventListener('abort', closeFromUpstream);
            scopedRequest.release();
            scopedRequest.controller.abort();
        };
    }, [entity, open, queryText, requestInit, requestKey]);

    const currentSearch = remoteSearch?.key === requestKey ? remoteSearch : null;
    const results = currentSearch?.status === 'success' ? currentSearch.results : [];
    const searchState = !queryReady
        ? 'idle'
        : currentSearch?.status ?? 'loading';

    function resetSearch() {
        setQuery('');
        setRemoteSearch(null);
        setSearchAttempt(0);
    }

    function handleOpenChange(next: boolean, restoreFocus: boolean) {
        setOpen(next);
        if (!next) resetSearch();
        if (restoreFocus) window.requestAnimationFrame(() => actionButtonRef.current?.focus());
    }

    function handleQueryChange(value: string) {
        setQuery(value);
    }

    function retrySearch() {
        inputRef.current?.focus();
        setSearchAttempt((attempt) => attempt + 1);
    }

    const stateLabel = !queryReady
        ? t('linkSearchPrompt')
        : searchState === 'loading'
          ? t('linkSearching')
          : searchState === 'error'
            ? t('linkSearchError')
            : results.length === 0
              ? t('linkNoResults')
              : t('linkResults', { count: results.length });
    const options: RowLinkOption[] = searchState === 'error'
        ? [{ kind: 'retry', id: 'retry', label: `${stateLabel} ${t('linkRetry')}` }]
        : results;

    if (linkedLabel) {
        return (
            <Button
                ref={actionButtonRef}
                size="toolbar"
                variant="outline"
                onClick={() => {
                    onClear();
                    window.requestAnimationFrame(() => actionButtonRef.current?.focus());
                }}
                aria-label={t('linkClear', { name: linkedLabel })}
                className="max-w-full"
            >
                <span className="truncate">{t('linkedTo', { name: linkedLabel })}</span>
                <XMarkIcon className="size-3 shrink-0" />
            </Button>
        );
    }
    if (!open) {
        return (
            <button
                ref={actionButtonRef}
                type="button"
                onClick={() => setOpen(true)}
                className="text-xs text-muted-foreground underline-offset-2 transition-colors hover:text-foreground hover:underline"
            >
                {t('linkAction')}
            </button>
        );
    }
    return (
        <Combobox
            items={options}
            value={null}
            inputValue={query}
            open
            serverSearch={createServerPickerSearch(query, currentSearch || !queryReady ? { query, items: options } : null)}
            autoHighlight
            itemToStringLabel={(item: RowLinkOption) => item.label}
            onOpenChange={(next, details) => {
                if (!next && details.reason === 'none') {
                    details.cancel();
                    return;
                }
                handleOpenChange(next, details.reason === 'escape-key');
            }}
            onInputValueChange={(value, details) => {
                if (details.reason !== 'item-press') handleQueryChange(value);
            }}
            onValueChange={(item, details) => {
                if (!item) return;
                if (item.kind === 'retry') {
                    details.cancel();
                    retrySearch();
                    return;
                }
                onLink(item.id, item.label);
                handleOpenChange(false, true);
            }}
        >
            <ComboboxInput
                ref={inputRef}
                autoFocus
                showTrigger={false}
                placeholder={t('linkSearchPlaceholder')}
                aria-label={t('linkSearchLabel')}
                data-row-linker-input
                className="h-7 w-52 text-xs"
            />
            <ComboboxContent className="pointer-events-auto w-52 min-w-52 duration-0 data-open:animate-none data-closed:animate-none">
                <ComboboxEmpty className="justify-start px-2 py-2 text-left text-xs">
                    {stateLabel}
                </ComboboxEmpty>
                {options.length > 0 && (
                    <p
                        className="sr-only"
                        role={searchState === 'error' ? 'alert' : 'status'}
                        aria-live={searchState === 'error' ? 'assertive' : 'polite'}
                        aria-atomic="true"
                    >
                        {stateLabel}
                    </p>
                )}
                <ComboboxList>
                    {(item: (typeof options)[number]) => (
                        <ComboboxItem
                            key={item.id}
                            value={item}
                            className="py-1 text-xs"
                        >
                            {item.kind === 'retry' ? (
                                <>
                                    <span className="min-w-0 flex-1 truncate">{stateLabel}</span>
                                    <span className="shrink-0 font-medium text-foreground">{t('linkRetry')}</span>
                                </>
                            ) : (
                                <span className="truncate">{item.label}</span>
                            )}
                        </ComboboxItem>
                    )}
                </ComboboxList>
            </ComboboxContent>
        </Combobox>
    );
}

function DoneStep({ result, entity, parsed }: { result: ImportResult; entity: ImportEntity; parsed: ParsedCsv | null }) {
    const t = useTranslations('importExport');
    const reduceMotion = useReducedMotion();
    function downloadErrors() {
        if (!parsed) return;
        const lines = [[...parsed.headers, t('errorColumn')].map(csvCell).join(',')];
        result.failed.forEach((failure) => {
            const row = parsed.rows[failure.rowIndex];
            if (!row) return;
            lines.push([...parsed.headers.map((h) => row[h] ?? ''), (importPresentationProblems(failure).map((problem) => problemText(t, problem)).join('; ') || t('problems.ROW_INVALID'))].map(csvCell).join(','));
        });
        const blob = new Blob([String.fromCharCode(0xfeff) + lines.join('\r\n')], { type: 'text/csv;charset=utf-8' });
        const url = URL.createObjectURL(blob);
        const anchor = document.createElement('a');
        anchor.href = url;
        anchor.download = `import-errors-${entity}.csv`;
        anchor.click();
        URL.revokeObjectURL(url);
    }
    return (
        <div className="space-y-4">
            <div className="flex items-center gap-3">
                <motion.span
                    initial={{ scale: reduceMotion ? 1 : 0.8, opacity: 0 }}
                    animate={{ scale: 1, opacity: 1 }}
                    transition={{ type: 'spring', duration: 0.4, bounce: 0.25 }}
                >
                    <CheckCircleIcon className="size-8 text-brand" />
                </motion.span>
                <div>
                    <p className="font-medium">{t('doneTitle')}</p>
                    <p className="text-sm text-muted-foreground">
                        {t('doneSummary', { created: result.created, updated: result.updated, skipped: result.skipped })}
                    </p>
                </div>
            </div>
            {result.failed.length > 0 && (
                <div className="flex items-center justify-between gap-3 rounded-lg border border-border px-3 py-2">
                    <span className="flex items-center gap-2 text-sm text-muted-foreground">
                        <DocumentTextIcon className="size-4" />
                        {t('failedCount', { count: result.failed.length })}
                    </span>
                    <Button variant="outline" size="toolbar" onClick={downloadErrors}>{t('downloadErrors')}</Button>
                </div>
            )}
        </div>
    );
}

function Count({ label, value, className }: { label: string; value: number; className?: string }) {
    return (
        <span className="flex items-baseline gap-1.5">
            <span className={cn('text-lg font-semibold tabular-nums', className)}>{value}</span>
            <span className="text-muted-foreground">{label}</span>
        </span>
    );
}

function csvCell(value: string): string {
    let cell = value ?? '';
    if (cell && /^[=+\-@\t\r]/.test(cell)) cell = `'${cell}`;
    if (/[",\n\r]/.test(cell)) cell = `"${cell.replace(/"/g, '""')}"`;
    return cell;
}
