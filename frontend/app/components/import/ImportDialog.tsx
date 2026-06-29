'use client';

import { useMemo, useRef, useState } from 'react';
import { useTranslations } from 'next-intl';
import { motion, useReducedMotion } from 'motion/react';
import { ArrowUpTrayIcon, CheckCircleIcon, DocumentTextIcon, ExclamationTriangleIcon } from '@heroicons/react/24/outline';
import { LoaderCircle } from 'lucide-react';

import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { cn } from '@/lib/utils';
import { commitImport, isFieldError, previewImport } from '@/app/lib/api';
import { toastError, toastSuccess } from '@/app/lib/toast';
import {
    columnSamples,
    inferColumnType,
    parseCsv,
    STANDARD_FIELDS,
    suggestField,
    type InferredType,
    type ParsedCsv,
} from '@/app/lib/import';
import type {
    ImportColumnMapping,
    ImportDuplicateAction,
    ImportEntity,
    ImportPreviewResult,
    ImportResult,
} from '@/app/lib/types';

const MAX_ROWS = 5000;
const MAX_BYTES = 5 * 1024 * 1024;
const IGNORE = 'ignore';
const CREATE = '__create__';
const CUSTOM_TYPES: InferredType[] = ['text', 'number', 'date', 'boolean', 'url'];
const DUPLICATE_ACTIONS: ImportDuplicateAction[] = ['fill_empty', 'skip', 'overwrite'];

type Step = 'upload' | 'map' | 'review' | 'done';
const STEPS: Step[] = ['upload', 'map', 'review', 'done'];

type ColumnTarget = { target: string; customType: InferredType };

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
};

/**
 * Multi-step CSV import wizard: upload &amp; parse, map columns to Connex fields, review the
 * deduplicated plan, then commit. Parsing is client-side; the backend validates and deduplicates.
 */
export default function ImportDialog({ entity, open, onOpenChange, onImported }: ImportDialogProps) {
    const t = useTranslations('importExport');
    const reduceMotion = useReducedMotion();
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

    function reset() {
        setStep('upload');
        setParsed(null);
        setFileName('');
        setParseError(null);
        setColumns({});
        setOnDuplicate('fill_empty');
        setPreview(null);
        setResult(null);
        setBusy(false);
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
            for (const header of result.headers) {
                const suggested = suggestField(header, entity);
                initial[header] = {
                    target: suggested ?? IGNORE,
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
            if (!col || col.target === IGNORE) continue;
            if (col.target === CREATE) {
                mapping.push({ column: header, createCustomField: true, customFieldType: col.customType, customFieldLabel: header });
            } else {
                mapping.push({ column: header, field: col.target });
            }
        }
        return mapping;
    }

    const mappedCount = useMemo(() => Object.values(columns).filter((c) => c.target !== IGNORE).length, [columns]);
    const hasName = useMemo(() => Object.values(columns).some((c) => c.target === 'name'), [columns]);

    async function goToReview() {
        if (!parsed) return;
        setBusy(true);
        try {
            const data = await previewImport(entity, { rows: parsed.rows, mapping: buildMapping(), onDuplicate });
            setPreview(data);
            setStep('review');
        } catch (error) {
            toastError(firstFieldError(error, t('errorPreview')));
        } finally {
            setBusy(false);
        }
    }

    async function selectAction(action: ImportDuplicateAction) {
        setOnDuplicate(action);
        if (!parsed) return;
        setPreviewing(true);
        try {
            const data = await previewImport(entity, { rows: parsed.rows, mapping: buildMapping(), onDuplicate: action });
            setPreview(data);
        } catch (error) {
            toastError(firstFieldError(error, t('errorPreview')));
        } finally {
            setPreviewing(false);
        }
    }

    async function commit() {
        if (!parsed) return;
        setBusy(true);
        try {
            const data = await commitImport(entity, { rows: parsed.rows, mapping: buildMapping(), onDuplicate });
            setResult(data);
            setStep('done');
            toastSuccess(t('toastImported', { created: data.created, updated: data.updated }));
            onImported();
        } catch (error) {
            toastError(firstFieldError(error, t('errorImport')));
        } finally {
            setBusy(false);
        }
    }

    return (
        <Dialog open={open} onOpenChange={handleOpenChange}>
            <DialogContent className="gap-0 overflow-hidden p-0 sm:max-w-3xl">
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
                        <MapStep parsed={parsed} entity={entity} columns={columns} setColumns={setColumns} mappedCount={mappedCount} />
                    )}
                    {step === 'review' && preview && (
                        <ReviewStep preview={preview} onDuplicate={onDuplicate} onSelectAction={selectAction} previewing={previewing} />
                    )}
                    {step === 'done' && result && <DoneStep result={result} entity={entity} parsed={parsed} />}
                    </motion.div>
                </div>

                <div className="flex items-center justify-between gap-3 border-t border-border px-6 py-4">
                    <span className="truncate text-xs text-muted-foreground">{fileName && step !== 'done' ? fileName : ''}</span>
                    <div className="flex items-center gap-2">
                        {step === 'map' && (
                            <>
                                <Button variant="ghost" onClick={() => setStep('upload')}>{t('back')}</Button>
                                <Button onClick={goToReview} disabled={busy || !hasName}>
                                    {busy && <LoaderCircle className="size-4 animate-spin" />}
                                    {t('next')}
                                </Button>
                            </>
                        )}
                        {step === 'review' && preview && (
                            <>
                                <Button variant="ghost" onClick={() => setStep('map')}>{t('back')}</Button>
                                <Button onClick={commit} disabled={busy || previewing || preview.toCreate + preview.toUpdate === 0}>
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
                <div key={s} className="flex flex-1 items-center gap-2">
                    <span
                        className={cn(
                            'flex size-5 shrink-0 items-center justify-center rounded-full text-xs font-semibold transition-colors',
                            i < current && 'bg-brand text-white',
                            i === current && 'bg-foreground text-background',
                            i > current && 'bg-muted text-muted-foreground',
                        )}
                    >
                        {i + 1}
                    </span>
                    <span className={cn('text-xs', i === current ? 'font-medium text-foreground' : 'text-muted-foreground')}>
                        {t(`step.${s}`)}
                    </span>
                    {i < STEPS.length - 1 && <span className="h-px flex-1 bg-border" />}
                </div>
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
}: {
    parsed: ParsedCsv;
    entity: ImportEntity;
    columns: Record<string, ColumnTarget>;
    setColumns: (updater: (prev: Record<string, ColumnTarget>) => Record<string, ColumnTarget>) => void;
    mappedCount: number;
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
                    const samples = columnSamples(parsed.rows, header, 3).join(', ');
                    return (
                        <div key={header} className="grid grid-cols-[1fr_auto_1fr] items-center gap-3 rounded-lg border border-border px-3 py-2">
                            <div className="min-w-0">
                                <p className="truncate text-sm font-medium">{header}</p>
                                {samples && <p className="truncate text-xs text-muted-foreground">{samples}</p>}
                            </div>
                            <span className="text-muted-foreground" aria-hidden>→</span>
                            <div className="flex items-center gap-2">
                                <Select value={col?.target ?? IGNORE} onValueChange={(v) => setTarget(header, v)}>
                                    <SelectTrigger size="sm" className="w-full">
                                        <SelectValue />
                                    </SelectTrigger>
                                    <SelectContent>
                                        <SelectItem value={IGNORE}>{t('ignore')}</SelectItem>
                                        {fieldOptions.map((f) => (
                                            <SelectItem key={f.key} value={f.key}>{f.label}</SelectItem>
                                        ))}
                                        <SelectItem value={CREATE}>{t('createCustomField')}</SelectItem>
                                    </SelectContent>
                                </Select>
                                {col?.target === CREATE && (
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
    onDuplicate,
    onSelectAction,
    previewing,
}: {
    preview: ImportPreviewResult;
    onDuplicate: ImportDuplicateAction;
    onSelectAction: (action: ImportDuplicateAction) => void;
    previewing: boolean;
}) {
    const t = useTranslations('importExport');
    const flagged = preview.rows.filter((r) => r.status === 'invalid' || r.status === 'match').slice(0, 100);
    return (
        <div className="space-y-5">
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

            {flagged.length > 0 && (
                <div className="overflow-hidden rounded-lg border border-border">
                    <table className="w-full text-sm">
                        <caption className="sr-only">{t('reviewTableCaption')}</caption>
                        <thead className="sr-only">
                            <tr>
                                <th scope="col">{t('rowColRow')}</th>
                                <th scope="col">{t('rowColStatus')}</th>
                                <th scope="col">{t('rowColDetail')}</th>
                            </tr>
                        </thead>
                        <tbody>
                            {flagged.map((row) => (
                                <tr key={row.rowIndex} className="border-b border-border last:border-0">
                                    <td className="w-16 px-3 py-2 text-xs text-muted-foreground">#{row.rowIndex + 1}</td>
                                    <td className="w-24 px-3 py-2">
                                        <Badge variant={row.status === 'invalid' ? 'destructive' : 'secondary'}>
                                            {t(`rowStatus.${row.status}`)}
                                        </Badge>
                                    </td>
                                    <td className="px-3 py-2 text-muted-foreground">
                                        {row.errors?.length ? row.errors.join('; ') : (row.matchedLabel ?? '')}
                                    </td>
                                </tr>
                            ))}
                        </tbody>
                    </table>
                </div>
            )}
        </div>
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
