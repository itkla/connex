'use client';

import { Fragment, useEffect, useMemo, useRef, useState } from 'react';
import { useTranslations } from 'next-intl';
import { motion, useReducedMotion } from 'motion/react';
import { ArrowUpTrayIcon, CheckCircleIcon, DocumentTextIcon, ExclamationTriangleIcon, XMarkIcon } from '@heroicons/react/24/outline';
import { LoaderCircle } from 'lucide-react';

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
import { commitImport, isFieldError, previewImport, search } from '@/app/lib/api';
import { toastError, toastSuccess } from '@/app/lib/toast';
import { usePermission } from '@/app/hooks/usePermissions';
import { useWorkspace } from '@/app/hooks/useWorkspace';
import {
    columnSamples,
    CREATE_CUSTOM_FIELD,
    IGNORE_COLUMN,
    inferColumnType,
    parseCsv,
    resolveColumnTarget,
    STANDARD_FIELDS,
    suggestField,
    type InferredType,
    type ParsedCsv,
} from '@/app/lib/import';
import type {
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
): string {
    return JSON.stringify([
        workspaceKey,
        entity,
        rows,
        mapping,
        onDuplicate,
        Object.entries(links).sort(([left], [right]) => Number(left) - Number(right)),
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

function firstFieldError(error: unknown, fallback: string): string {
    if (isFieldError(error)) {
        const first = Object.values(error.fieldErrors)[0];
        if (first) return first;
    }
    return fallback;
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
    const reduceMotion = useReducedMotion();
    const canCreateCustomField = usePermission('CUSTOM_FIELD_MANAGE');
    const { activeWorkspaceId } = useWorkspace();
    const workspaceKey =
        requestWorkspaceKey(requestInit) || activeWorkspaceId?.toString() || '';
    const [step, setStep] = useState<Step>('upload');
    const [parsed, setParsed] = useState<ParsedCsv | null>(null);
    const [fileName, setFileName] = useState<string>('');
    const [parseError, setParseError] = useState<string | null>(null);
    const [parsing, setParsing] = useState(false);
    const [dragActive, setDragActive] = useState(false);
    const [columns, setColumns] = useState<Record<string, ColumnTarget>>({});
    const [onDuplicate, setOnDuplicate] = useState<ImportDuplicateAction>('fill_empty');
    const [preview, setPreview] = useState<ImportPreviewResult | null>(null);
    const [result, setResult] = useState<ImportResult | null>(null);
    const [busy, setBusy] = useState(false);
    const [previewing, setPreviewing] = useState(false);
    const [reviewedInputKey, setReviewedInputKey] = useState<string | null>(null);
    const [reviewedProof, setReviewedProof] = useState<string | null>(null);
    const [links, setLinks] = useState<Record<number, number>>({});
    const [linkLabels, setLinkLabels] = useState<Record<number, string>>({});
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
        setOnDuplicate('fill_empty');
        setPreview(null);
        setResult(null);
        setBusy(false);
        setPreviewing(false);
        setReviewedInputKey(null);
        setReviewedProof(null);
        setLinks({});
        setLinkLabels({});
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
            const taken = new Set<string>();
            for (const header of result.headers) {
                let suggested = suggestField(header, entity);
                if (suggested && suggested !== 'tags') {
                    if (taken.has(suggested)) suggested = null;
                    else taken.add(suggested);
                }
                initial[header] = {
                    target: suggested ?? IGNORE_COLUMN,
                    customType: inferColumnType(columnSamples(result.rows, header)),
                };
            }
            setParsed(result);
            setColumns(initial);
            setStep('map');
        } catch {
            setParseError(t('errorParse'));
        } finally {
            setParsing(false);
        }
    }

    function buildMapping(): ImportColumnMapping[] {
        if (!parsed) return [];
        const mapping: ImportColumnMapping[] = [];
        for (const header of parsed.headers) {
            const col = columns[header];
            const target = resolveColumnTarget(col?.target, canCreateCustomField);
            if (!col || target === IGNORE_COLUMN) continue;
            if (target === CREATE_CUSTOM_FIELD) {
                mapping.push({ column: header, createCustomField: true, customFieldType: col.customType, customFieldLabel: header });
            } else {
                mapping.push({ column: header, field: target });
            }
        }
        return mapping;
    }

    const mappedCount = useMemo(
        () =>
            Object.values(columns).filter(
                (c) => resolveColumnTarget(c.target, canCreateCustomField) !== IGNORE_COLUMN,
            ).length,
        [columns, canCreateCustomField],
    );
    const hasName = useMemo(() => Object.values(columns).some((c) => c.target === 'name'), [columns]);
    const nameColumn = useMemo(
        () => Object.entries(columns).find(([, c]) => c.target === 'name')?.[0] ?? null,
        [columns],
    );
    const currentReviewInputKey = parsed
        ? reviewInputKey(
            workspaceKey,
            entity,
            parsed.rows,
            buildMapping(),
            onDuplicate,
            links,
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
        if (!parsed || !currentReviewInputKey) return;
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
                { rows: parsed.rows, mapping: buildMapping(), onDuplicate, links },
                scopedRequest.requestInit,
            );
            if (scopedRequest.controller.signal.aborted || generation !== previewGenerationRef.current) return;
            if (!data.duplicateReviewProof) {
                throw new Error('Duplicate review proof is missing');
            }
            setPreview(data);
            setReviewedInputKey(inputKey);
            setReviewedProof(data.duplicateReviewProof ?? null);
            setStep('review');
        } catch (error) {
            if (scopedRequest.controller.signal.aborted || generation !== previewGenerationRef.current) return;
            toastError(firstFieldError(error, t('errorPreview')));
        } finally {
            scopedRequest.release();
            if (previewControllerRef.current === scopedRequest.controller) {
                previewControllerRef.current = null;
                if (!requestInit?.signal?.aborted) setBusy(false);
            }
        }
    }

    async function runPreview() {
        if (!parsed || !currentReviewInputKey) return;
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
                { rows: parsed.rows, mapping: buildMapping(), onDuplicate, links },
                scopedRequest.requestInit,
            );
            if (scopedRequest.controller.signal.aborted || generation !== previewGenerationRef.current) return;
            if (!data.duplicateReviewProof) {
                throw new Error('Duplicate review proof is missing');
            }
            setPreview(data);
            setReviewedInputKey(inputKey);
            setReviewedProof(data.duplicateReviewProof ?? null);
        } catch (error) {
            if (scopedRequest.controller.signal.aborted || generation !== previewGenerationRef.current) return;
            toastError(firstFieldError(error, t('errorPreview')));
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
        if (!parsed
                || !currentReviewInputKey
                || currentReviewInputKey !== reviewedInputKey
                || reviewedProof === null) return;
        setBusy(true);
        try {
            const data = await commitImport(
                entity,
                {
                    rows: parsed.rows,
                    mapping: buildMapping(),
                    onDuplicate,
                    links,
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
            setReviewedInputKey(null);
            toastError(firstFieldError(error, t('errorImport')));
        } finally {
            if (!requestInit?.signal?.aborted) setBusy(false);
        }
    }

    return (
        <Dialog open={open} onOpenChange={handleOpenChange}>
            <DialogContent
                className="gap-0 overflow-hidden p-0 sm:max-w-3xl"
                onEscapeKeyDown={(event) => {
                    const target = event.target instanceof Element ? event.target : null;
                    if (target?.closest('[data-row-linker-input][aria-expanded="true"]')) event.preventDefault();
                }}
            >
                <DialogHeader className="border-b border-border px-6 py-4">
                    <DialogTitle>{t(`title.${entity}`)}</DialogTitle>
                    <DialogDescription className="sr-only">{t('description')}</DialogDescription>
                    <StepRail step={step} />
                </DialogHeader>

                <div className="max-h-[60vh] overflow-y-auto px-6 py-5">
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
                            setColumns={setColumns}
                            mappedCount={mappedCount}
                            canCreateCustomField={canCreateCustomField}
                        />
                    )}
                    {step === 'review' && preview && (
                        <ReviewStep preview={preview} stale={reviewStale} onDuplicate={onDuplicate} onSelectAction={selectAction} previewing={previewing} entity={entity} parsed={parsed} nameColumn={nameColumn} links={links} linkLabels={linkLabels} onLink={linkRow} onUnlink={unlinkRow} requestInit={requestInit} />
                    )}
                    {step === 'done' && result && <DoneStep result={result} entity={entity} parsed={parsed} />}
                    </motion.div>
                </div>

                <div className="flex items-center justify-between gap-3 border-t border-border px-6 py-4">
                    <span className="truncate text-xs text-muted-foreground">{fileName && step !== 'done' ? fileName : ''}</span>
                    <div className="flex items-center gap-2">
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
                                <Button variant="ghost" onClick={() => returnToStep('map')}>{t('back')}</Button>
                                {reviewStale && (
                                    <Button variant="outline" onClick={runPreview} disabled={previewing}>
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
                        ? 'border-brand bg-brand/5 text-brand'
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
}: {
    parsed: ParsedCsv;
    entity: ImportEntity;
    columns: Record<string, ColumnTarget>;
    setColumns: (updater: (prev: Record<string, ColumnTarget>) => Record<string, ColumnTarget>) => void;
    mappedCount: number;
    canCreateCustomField: boolean;
}) {
    const t = useTranslations('importExport');
    const fieldOptions = useMemo(
        () => [...STANDARD_FIELDS[entity], 'tags'].map((key) => ({ key, label: t(`fields.${key}`) })),
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
            <p className="text-sm text-muted-foreground">{t('mapHint', { mapped: mappedCount, total: parsed.headers.length })}</p>
            <div className="space-y-2">
                {parsed.headers.map((header) => {
                    const col = columns[header];
                    const target = resolveColumnTarget(col?.target, canCreateCustomField);
                    const samples = columnSamples(parsed.rows, header, 3).join(', ');
                    return (
                        <div key={header} className="grid grid-cols-[1fr_auto_1fr] items-center gap-3 rounded-lg border border-border px-3 py-2">
                            <div className="min-w-0">
                                <p className="truncate text-sm font-medium">{header}</p>
                                {samples && <p className="truncate text-xs text-muted-foreground">{samples}</p>}
                            </div>
                            <span className="text-muted-foreground" aria-hidden>→</span>
                            <div className="flex items-center gap-2">
                                <Select value={target} onValueChange={(v) => setTarget(header, v)}>
                                    <SelectTrigger size="sm" className="w-full">
                                        <SelectValue />
                                    </SelectTrigger>
                                    <SelectContent>
                                        <SelectItem value={IGNORE_COLUMN}>{t('ignore')}</SelectItem>
                                        {fieldOptions.map((f) => (
                                            <SelectItem key={f.key} value={f.key}>{f.label}</SelectItem>
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
}: {
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
    const reviewable = preview.rows.filter((row) => row.status !== 'skip');
    const candidateRows = reviewable.filter((row) => (row.candidates?.length ?? 0) > 0);
    const ordinaryRows = reviewable.filter((row) => (row.candidates?.length ?? 0) === 0);
    const shown = [
        ...candidateRows,
        ...ordinaryRows.slice(0, Math.max(0, 100 - candidateRows.length)),
    ].sort((left, right) => left.rowIndex - right.rowIndex);
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
                            size="sm"
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
                <Count label={t('countNew')} value={preview.toCreate} className="text-brand" />
                <Count label={t('countUpdate')} value={preview.toUpdate} className="text-foreground" />
                <Count label={t('countSkip')} value={preview.toSkip} className="text-muted-foreground" />
                <Count label={t('countInvalid')} value={preview.invalid} className="text-destructive" />
            </div>

            {shown.length > 0 && (
                <div className="space-y-2">
                    <p className="text-xs text-muted-foreground">
                        {t('reviewLinkHint', {
                            shown: shown.length,
                            total: reviewable.length,
                            candidates: candidateRows.length,
                        })}
                    </p>
                    <div className="overflow-hidden rounded-lg border border-border">
                        <table className="w-full text-sm">
                            <caption className="sr-only">{t('reviewTableCaption')}</caption>
                            <thead className="sr-only">
                                <tr>
                                    <th scope="col">{t('rowColRow')}</th>
                                    <th scope="col">{t('rowColStatus')}</th>
                                    <th scope="col">{t('rowColDetail')}</th>
                                    <th scope="col">{t('rowColLink')}</th>
                                </tr>
                            </thead>
                            <tbody>
                                {shown.map((row) => (
                                    <tr key={row.rowIndex} className="border-b border-border last:border-0">
                                        <td className="w-12 px-3 py-2 align-middle text-xs text-muted-foreground">#{row.rowIndex + 1}</td>
                                        <td className="w-20 px-3 py-2 align-middle">
                                            <Badge variant={row.status === 'invalid' ? 'destructive' : row.status === 'match' ? 'secondary' : 'outline'}>
                                                {t(`rowStatus.${row.status}`)}
                                            </Badge>
                                        </td>
                                        <td className="max-w-0 px-3 py-2 align-middle">
                                            <div className="min-w-0 space-y-2">
                                                <p className={cn(
                                                    'truncate text-sm',
                                                    row.errors?.length ? 'text-destructive' : 'text-muted-foreground',
                                                )}>
                                                    {row.errors?.length
                                                        ? row.errors.join('; ')
                                                        : row.matchedLabel ?? (nameColumn && parsed ? parsed.rows[row.rowIndex]?.[nameColumn] ?? '' : '')}
                                                </p>
                                                {row.canonicalRowIndex != null && row.mergedRowCount != null && (
                                                    <p className="text-xs text-muted-foreground">
                                                        {row.rowIndex === row.canonicalRowIndex
                                                            ? t('rowMergeRepresentative', { count: row.mergedRowCount })
                                                            : t('rowMergeContributor', { row: row.canonicalRowIndex + 1 })}
                                                    </p>
                                                )}
                                                {row.candidates && row.candidates.length > 0 && (
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
                                                )}
                                            </div>
                                        </td>
                                        <td className="w-56 px-3 py-2 text-right align-middle">
                                            <RowLinker
                                                entity={entity}
                                                linkedLabel={links[row.rowIndex] != null ? linkLabels[row.rowIndex] : undefined}
                                                onLink={(id, label) => onLink(row.rowIndex, id, label)}
                                                onClear={() => onUnlink(row.rowIndex)}
                                                requestInit={requestInit}
                                            />
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

function DuplicateCandidates({
    entity,
    candidates,
    linkedId,
    onLink,
}: {
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
                        className="flex min-w-0 items-start justify-between gap-3 rounded-md border border-border bg-muted/40 px-2.5 py-2"
                    >
                        <div className="min-w-0 space-y-1">
                            <div className="flex min-w-0 flex-wrap items-center gap-1.5">
                                <span className="truncate text-xs font-medium text-foreground">
                                    {candidate.name}
                                </span>
                                <Badge variant="outline" className="h-5 px-1.5 text-[10px]">
                                    {t(`candidateRecordType.${candidate.recordType}`)}
                                </Badge>
                                <Badge
                                    variant={candidate.strength === 'STRONG' ? 'secondary' : 'outline'}
                                    className="h-5 px-1.5 text-[10px]"
                                >
                                    {t(`candidateStrength.${candidate.strength}`)}
                                </Badge>
                                {!candidate.ownedByActiveWorkspace && (
                                    <span className="text-[11px] text-muted-foreground">
                                        {t('candidateShared')}
                                    </span>
                                )}
                            </div>
                            {context.length > 0 && (
                                <p className="truncate text-[11px] text-muted-foreground">
                                    {context.join(' · ')}
                                </p>
                            )}
                            <p className="text-[11px] text-muted-foreground">
                                {candidate.matches
                                    .map((match) => t(`candidateEvidence.${match.kind}`))
                                    .join(' · ')}
                            </p>
                        </div>
                        {candidate.ownedByActiveWorkspace
                                && candidate.recordType === (
                                    entity === 'companies' ? 'company' : entity === 'persons' ? 'person' : ''
                                ) && (
                            <button
                                type="button"
                                disabled={selected}
                                onClick={() => onLink(candidate)}
                                className="shrink-0 rounded-sm px-1.5 py-0.5 text-xs font-medium text-foreground underline-offset-2 transition-colors hover:bg-accent hover:underline disabled:text-muted-foreground disabled:no-underline"
                            >
                                {selected ? t('candidateSelected') : t('candidateUse')}
                            </button>
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
            <button
                ref={actionButtonRef}
                type="button"
                onClick={() => {
                    onClear();
                    window.requestAnimationFrame(() => actionButtonRef.current?.focus());
                }}
                aria-label={t('linkClear', { name: linkedLabel })}
                className="inline-flex max-w-full items-center gap-1 rounded-sm bg-accent px-2 py-0.5 text-xs text-accent-foreground transition-colors hover:bg-accent/70"
            >
                <span className="truncate">{t('linkedTo', { name: linkedLabel })}</span>
                <XMarkIcon className="size-3 shrink-0" />
            </button>
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
            filter={null}
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
                    {options.map((item) => (
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
                    ))}
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
        const lines = [[...parsed.headers, 'error'].map(csvCell).join(',')];
        result.failed.forEach((failure) => {
            const row = parsed.rows[failure.rowIndex];
            if (!row) return;
            lines.push([...parsed.headers.map((h) => row[h] ?? ''), failure.reason].map(csvCell).join(','));
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
                    <Button variant="outline" size="sm" onClick={downloadErrors}>{t('downloadErrors')}</Button>
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
