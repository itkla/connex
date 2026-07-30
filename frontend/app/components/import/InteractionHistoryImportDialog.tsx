"use client";

import { Fragment, useEffect, useMemo, useRef, useState } from "react";
import { useTranslations } from "next-intl";
import {
    ArrowUpTrayIcon,
    CheckCircleIcon,
    DocumentTextIcon,
    ExclamationTriangleIcon,
    XMarkIcon,
} from "@heroicons/react/24/outline";
import { LoaderCircle } from "lucide-react";

import { useWorkspace } from "@/app/hooks/useWorkspace";
import {
    commitInteractionHistoryImport,
    isFieldError,
    previewInteractionHistoryImport,
    search,
} from "@/app/lib/api";
import { columnSamples, parseCsv, type ParsedCsv } from "@/app/lib/import";
import {
    buildHistoryImportMapping,
    historyImportFields,
    historyImportMappingIsComplete,
    suggestHistoryImportField,
} from "@/app/lib/interaction-history-import";
import { toastError, toastSuccess } from "@/app/lib/toast";
import type {
    DuplicateCandidate,
    HistoryImportKind,
    HistoryImportPreviewResult,
    HistoryImportResult,
} from "@/app/lib/types";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
    Combobox,
    ComboboxContent,
    ComboboxEmpty,
    ComboboxInput,
    ComboboxItem,
    ComboboxList,
} from "@/components/ui/combobox";
import {
    ResponsiveDialog,
    ResponsiveDialogContent,
    ResponsiveDialogDescription,
    ResponsiveDialogHeader,
    ResponsiveDialogTitle,
} from "@/components/ui/responsive-dialog";
import {
    Select,
    SelectContent,
    SelectItem,
    SelectTrigger,
    SelectValue,
} from "@/components/ui/select";
import { cn } from "@/lib/utils";

const MAX_ROWS = 5000;
const MAX_BYTES = 5 * 1024 * 1024;
const IGNORE = "ignore";
const KINDS: HistoryImportKind[] = ["activities", "notes", "tasks"];

type Step = "upload" | "map" | "review" | "done";
const STEPS: Step[] = ["upload", "map", "review", "done"];

type ContactOption =
    | { kind: "contact"; id: number; label: string }
    | { kind: "retry"; id: "retry"; label: string };

type ContactSearchState =
    | { key: string; status: "success"; options: ContactOption[] }
    | { key: string; status: "error" }
    | null;

function isHistoryImportKind(value: string): value is HistoryImportKind {
    return value === "activities" || value === "notes" || value === "tasks";
}

function reviewInputKey(
    workspaceId: number | null,
    kind: HistoryImportKind,
    parsed: ParsedCsv,
    targets: Readonly<Record<string, string>>,
    links: Readonly<Record<number, number>>,
): string {
    return JSON.stringify([
        workspaceId,
        kind,
        parsed.rows,
        buildHistoryImportMapping(parsed.headers, targets),
        Object.entries(links).sort(([left], [right]) => Number(left) - Number(right)),
    ]);
}

function firstFieldError(error: unknown, fallback: string): string {
    if (isFieldError(error)) {
        const first = Object.values(error.fieldErrors)[0];
        if (first) return first;
    }
    return fallback;
}

/**
 * Responsive proof-bound CSV wizard for historical activities, notes, and tasks.
 */
export default function InteractionHistoryImportDialog({
    open,
    onOpenChange,
}: {
    open: boolean;
    onOpenChange: (open: boolean) => void;
}) {
    const t = useTranslations("importExport.history");
    const { activeWorkspaceId } = useWorkspace();
    const [step, setStep] = useState<Step>("upload");
    const [kind, setKind] = useState<HistoryImportKind>("activities");
    const [parsed, setParsed] = useState<ParsedCsv | null>(null);
    const [fileName, setFileName] = useState("");
    const [targets, setTargets] = useState<Record<string, string>>({});
    const [links, setLinks] = useState<Record<number, number>>({});
    const [linkLabels, setLinkLabels] = useState<Record<number, string>>({});
    const [preview, setPreview] = useState<HistoryImportPreviewResult | null>(null);
    const [result, setResult] = useState<HistoryImportResult | null>(null);
    const [reviewedKey, setReviewedKey] = useState<string | null>(null);
    const [reviewProof, setReviewProof] = useState<string | null>(null);
    const [parseError, setParseError] = useState<string | null>(null);
    const [busy, setBusy] = useState(false);
    const [dragActive, setDragActive] = useState(false);
    const operationControllerRef = useRef<AbortController | null>(null);
    const operationGenerationRef = useRef(0);
    const previousWorkspaceRef = useRef(activeWorkspaceId);

    const mappingComplete = parsed
        ? historyImportMappingIsComplete(kind, targets)
        : false;
    const currentKey = parsed
        ? reviewInputKey(activeWorkspaceId ?? null, kind, parsed, targets, links)
        : null;
    const reviewStale = currentKey !== reviewedKey || reviewProof === null;

    function reset() {
        operationGenerationRef.current += 1;
        operationControllerRef.current?.abort();
        operationControllerRef.current = null;
        setStep("upload");
        setKind("activities");
        setParsed(null);
        setFileName("");
        setTargets({});
        setLinks({});
        setLinkLabels({});
        setPreview(null);
        setResult(null);
        setReviewedKey(null);
        setReviewProof(null);
        setParseError(null);
        setBusy(false);
        setDragActive(false);
    }

    function handleOpenChange(next: boolean) {
        if (!next) reset();
        onOpenChange(next);
    }

    useEffect(() => {
        if (previousWorkspaceRef.current === activeWorkspaceId) return;
        previousWorkspaceRef.current = activeWorkspaceId;
        operationGenerationRef.current += 1;
        operationControllerRef.current?.abort();
        operationControllerRef.current = null;
        setStep("upload");
        setParsed(null);
        setFileName("");
        setTargets({});
        setLinks({});
        setLinkLabels({});
        setPreview(null);
        setResult(null);
        setReviewedKey(null);
        setReviewProof(null);
        setParseError(null);
        setBusy(false);
        setDragActive(false);
    }, [activeWorkspaceId]);

    function initializeTargets(csv: ParsedCsv, selectedKind: HistoryImportKind) {
        const next: Record<string, string> = {};
        const taken = new Set<string>();
        for (const header of csv.headers) {
            let suggestion = suggestHistoryImportField(header, selectedKind);
            if (suggestion && taken.has(suggestion)) suggestion = null;
            if (suggestion) taken.add(suggestion);
            next[header] = suggestion ?? IGNORE;
        }
        setTargets(next);
    }

    async function handleFile(file: File | undefined) {
        if (!file) return;
        setParseError(null);
        if (file.size > MAX_BYTES) {
            setParseError(t("errors.tooLarge"));
            return;
        }
        operationControllerRef.current?.abort();
        operationControllerRef.current = null;
        const generation = operationGenerationRef.current + 1;
        operationGenerationRef.current = generation;
        setBusy(true);
        setFileName(file.name);
        try {
            const csv = await parseCsv(file);
            if (operationGenerationRef.current !== generation) return;
            if (csv.headers.length === 0) {
                setParseError(t("errors.noColumns"));
                return;
            }
            if (csv.rows.length === 0) {
                setParseError(t("errors.noRows"));
                return;
            }
            if (csv.rows.length > MAX_ROWS) {
                setParseError(t("errors.tooManyRows", { count: csv.rows.length }));
                return;
            }
            setParsed(csv);
            initializeTargets(csv, kind);
            setStep("map");
        } catch {
            if (operationGenerationRef.current === generation) {
                setParseError(t("errors.parse"));
            }
        } finally {
            if (operationGenerationRef.current === generation) {
                setBusy(false);
            }
        }
    }

    function requestBody(proof?: string) {
        if (!parsed) return null;
        return {
            rows: parsed.rows,
            mapping: buildHistoryImportMapping(parsed.headers, targets),
            links,
            ...(proof ? { duplicateReviewProof: proof } : {}),
        };
    }

    async function runPreview() {
        const body = requestBody();
        if (!body || !currentKey) return;
        operationControllerRef.current?.abort();
        const controller = new AbortController();
        const generation = operationGenerationRef.current + 1;
        operationGenerationRef.current = generation;
        operationControllerRef.current = controller;
        setBusy(true);
        try {
            const analyzed = await previewInteractionHistoryImport(
                kind,
                body,
                { signal: controller.signal },
            );
            if (
                controller.signal.aborted
                || operationGenerationRef.current !== generation
            ) return;
            if (!analyzed.duplicateReviewProof) throw new Error("Review proof missing");
            setPreview(analyzed);
            setReviewedKey(currentKey);
            setReviewProof(analyzed.duplicateReviewProof);
            setStep("review");
        } catch (error) {
            if (
                !controller.signal.aborted
                && operationGenerationRef.current === generation
            ) {
                toastError(firstFieldError(error, t("errors.preview")));
            }
        } finally {
            if (
                operationControllerRef.current === controller
                && operationGenerationRef.current === generation
            ) {
                operationControllerRef.current = null;
                setBusy(false);
            }
        }
    }

    async function commit() {
        if (!reviewProof || reviewStale) return;
        const body = requestBody(reviewProof);
        if (!body) return;
        operationControllerRef.current?.abort();
        const controller = new AbortController();
        const generation = operationGenerationRef.current + 1;
        operationGenerationRef.current = generation;
        operationControllerRef.current = controller;
        setBusy(true);
        try {
            const imported = await commitInteractionHistoryImport(
                kind,
                body,
                { signal: controller.signal },
            );
            if (
                controller.signal.aborted
                || operationGenerationRef.current !== generation
            ) return;
            setResult(imported);
            setStep("done");
            toastSuccess(t("toast", { count: imported.created }));
        } catch (error) {
            if (
                !controller.signal.aborted
                && operationGenerationRef.current === generation
            ) {
                setReviewProof(null);
                setReviewedKey(null);
                toastError(firstFieldError(error, t("errors.commit")));
            }
        } finally {
            if (
                operationControllerRef.current === controller
                && operationGenerationRef.current === generation
            ) {
                operationControllerRef.current = null;
                setBusy(false);
            }
        }
    }

    function linkRow(rowIndex: number, participantId: number, label: string) {
        setLinks((current) => ({ ...current, [rowIndex]: participantId }));
        setLinkLabels((current) => ({ ...current, [rowIndex]: label }));
    }

    function unlinkRow(rowIndex: number) {
        setLinks((current) => {
            const next = { ...current };
            delete next[rowIndex];
            return next;
        });
        setLinkLabels((current) => {
            const next = { ...current };
            delete next[rowIndex];
            return next;
        });
    }

    return (
        <ResponsiveDialog open={open} onOpenChange={handleOpenChange}>
            <ResponsiveDialogContent
                className="flex max-h-[90dvh] flex-col gap-0 overflow-hidden p-0 sm:max-w-3xl"
                scrollable={false}
            >
                <ResponsiveDialogHeader className="shrink-0 border-b border-border px-4 py-4 sm:px-6">
                    <ResponsiveDialogTitle>{t("title")}</ResponsiveDialogTitle>
                    <ResponsiveDialogDescription>{t("description")}</ResponsiveDialogDescription>
                    <StepRail step={step} />
                </ResponsiveDialogHeader>

                <div className="min-h-0 flex-1 overflow-y-auto px-4 py-5 sm:px-6">
                    {step === "upload" && (
                        <UploadStep
                            kind={kind}
                            busy={busy}
                            dragActive={dragActive}
                            error={parseError}
                            onKindChange={setKind}
                            onFile={handleFile}
                            onDragActive={setDragActive}
                        />
                    )}
                    {step === "map" && parsed && (
                        <MapStep
                            kind={kind}
                            parsed={parsed}
                            targets={targets}
                            onTargetsChange={setTargets}
                        />
                    )}
                    {step === "review" && preview && (
                        <ReviewStep
                            preview={preview}
                            stale={reviewStale}
                            links={links}
                            linkLabels={linkLabels}
                            onLink={linkRow}
                            onUnlink={unlinkRow}
                        />
                    )}
                    {step === "done" && result && parsed && (
                        <DoneStep kind={kind} parsed={parsed} result={result} />
                    )}
                </div>

                <div className="flex shrink-0 flex-col-reverse gap-2 border-t border-border px-4 py-4 sm:flex-row sm:items-center sm:justify-between sm:px-6">
                    <span className="min-w-0 truncate text-xs text-muted-foreground">
                        {step !== "done" ? fileName : ""}
                    </span>
                    <div className="flex w-full flex-wrap items-center justify-end gap-2 sm:w-auto">
                        {step === "map" && (
                            <>
                                <Button variant="ghost" onClick={() => setStep("upload")}>{t("back")}</Button>
                                <Button disabled={!mappingComplete || busy} onClick={runPreview}>
                                    {busy && <LoaderCircle className="size-4 animate-spin" />}
                                    {t("review")}
                                </Button>
                            </>
                        )}
                        {step === "review" && preview && (
                            <>
                                <Button variant="ghost" onClick={() => setStep("map")}>{t("back")}</Button>
                                {reviewStale && (
                                    <Button variant="outline" disabled={busy} onClick={runPreview}>
                                        {busy && <LoaderCircle className="size-4 animate-spin" />}
                                        {t("refresh")}
                                    </Button>
                                )}
                                {!reviewStale && (
                                    <Button
                                        disabled={busy || preview.toCreate === 0}
                                        onClick={commit}
                                    >
                                        {busy && <LoaderCircle className="size-4 animate-spin" />}
                                        {t("import")}
                                    </Button>
                                )}
                            </>
                        )}
                        {step === "done" && (
                            <Button onClick={() => handleOpenChange(false)}>{t("done")}</Button>
                        )}
                    </div>
                </div>
            </ResponsiveDialogContent>
        </ResponsiveDialog>
    );
}

function StepRail({ step }: { step: Step }) {
    const t = useTranslations("importExport.history.steps");
    const current = STEPS.indexOf(step);
    return (
        <div className="mt-2 flex items-center gap-1.5" aria-label={t("label")}>
            {STEPS.map((item, index) => (
                <Fragment key={item}>
                    <span
                        className={cn(
                            "flex size-5 items-center justify-center rounded-full text-[11px] font-semibold",
                            index < current && "bg-brand text-brand-foreground",
                            index === current && "bg-foreground text-background",
                            index > current && "bg-muted text-muted-foreground",
                        )}
                        aria-current={index === current ? "step" : undefined}
                    >
                        {index + 1}
                    </span>
                    <span className={cn(
                        "hidden text-xs sm:inline",
                        index === current ? "text-foreground" : "text-muted-foreground",
                    )}>
                        {t(item)}
                    </span>
                    {index < STEPS.length - 1 && <span className="h-px w-4 bg-border sm:w-6" aria-hidden />}
                </Fragment>
            ))}
        </div>
    );
}

function UploadStep({
    kind,
    busy,
    dragActive,
    error,
    onKindChange,
    onFile,
    onDragActive,
}: {
    kind: HistoryImportKind;
    busy: boolean;
    dragActive: boolean;
    error: string | null;
    onKindChange: (kind: HistoryImportKind) => void;
    onFile: (file: File | undefined) => void;
    onDragActive: (active: boolean) => void;
}) {
    const t = useTranslations("importExport.history");
    return (
        <div className="space-y-5">
            <div className="space-y-2">
                <label className="text-sm font-medium" htmlFor="history-import-kind">{t("kindLabel")}</label>
                <Select
                    value={kind}
                    onValueChange={(value) => {
                        if (isHistoryImportKind(value)) onKindChange(value);
                    }}
                >
                    <SelectTrigger id="history-import-kind" className="w-full sm:w-64">
                        <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                        {KINDS.map((item) => (
                            <SelectItem key={item} value={item}>{t(`kinds.${item}`)}</SelectItem>
                        ))}
                    </SelectContent>
                </Select>
            </div>

            <label
                className={cn(
                    "flex min-h-44 cursor-pointer flex-col items-center justify-center gap-3 rounded-2xl border border-dashed border-border bg-muted/30 px-5 text-center transition-colors hover:bg-muted/50",
                    dragActive && "border-brand bg-brand/5",
                )}
                onDragEnter={() => onDragActive(true)}
                onDragLeave={() => onDragActive(false)}
                onDragOver={(event) => event.preventDefault()}
                onDrop={(event) => {
                    event.preventDefault();
                    onDragActive(false);
                    void onFile(event.dataTransfer.files[0]);
                }}
            >
                <input
                    className="sr-only"
                    type="file"
                    accept=".csv,text/csv"
                    disabled={busy}
                    onChange={(event) => void onFile(event.target.files?.[0])}
                />
                {busy
                    ? <LoaderCircle className="size-7 animate-spin text-muted-foreground" />
                    : <ArrowUpTrayIcon className="size-7 text-muted-foreground" />}
                <div className="space-y-1">
                    <p className="text-sm font-medium text-foreground">{t("dropPrompt")}</p>
                    <p className="text-xs text-muted-foreground">{t("uploadHint")}</p>
                </div>
            </label>

            {error && (
                <p className="flex items-start gap-2 text-sm text-destructive" role="alert">
                    <ExclamationTriangleIcon className="mt-0.5 size-4 shrink-0" />
                    {error}
                </p>
            )}
        </div>
    );
}

function MapStep({
    kind,
    parsed,
    targets,
    onTargetsChange,
}: {
    kind: HistoryImportKind;
    parsed: ParsedCsv;
    targets: Record<string, string>;
    onTargetsChange: (targets: Record<string, string>) => void;
}) {
    const t = useTranslations("importExport.history");
    const available = historyImportFields(kind);
    const complete = historyImportMappingIsComplete(kind, targets);
    return (
        <div className="space-y-4">
            <p className={cn("text-sm", complete ? "text-muted-foreground" : "text-destructive")}>
                {complete ? t("mapReady") : t("mapRequired")}
            </p>
            <div className="divide-y divide-border overflow-hidden rounded-xl border border-border">
                {parsed.headers.map((header) => {
                    const samples = columnSamples(parsed.rows, header, 3);
                    return (
                        <div
                            key={header}
                            className="grid gap-3 bg-card px-3 py-3 sm:grid-cols-[minmax(0,1fr)_16rem] sm:items-center"
                        >
                            <div className="min-w-0">
                                <p className="truncate text-sm font-medium text-foreground">{header}</p>
                                <p className="truncate text-xs text-muted-foreground">
                                    {samples.length > 0 ? samples.join(" · ") : t("emptySample")}
                                </p>
                            </div>
                            <Select
                                value={targets[header] ?? IGNORE}
                                onValueChange={(field) => onTargetsChange({ ...targets, [header]: field })}
                            >
                                <SelectTrigger aria-label={t("mapColumn", { column: header })}>
                                    <SelectValue />
                                </SelectTrigger>
                                <SelectContent>
                                    <SelectItem value={IGNORE}>{t("ignore")}</SelectItem>
                                    {available.map((field) => (
                                        <SelectItem
                                            key={field.key}
                                            value={field.key}
                                            disabled={Object.entries(targets).some(
                                                ([column, target]) => column !== header && target === field.key,
                                            )}
                                        >
                                            {t(`fields.${field.key}`)}
                                            {field.required ? ` · ${t("required")}` : ""}
                                        </SelectItem>
                                    ))}
                                </SelectContent>
                            </Select>
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
    links,
    linkLabels,
    onLink,
    onUnlink,
}: {
    preview: HistoryImportPreviewResult;
    stale: boolean;
    links: Record<number, number>;
    linkLabels: Record<number, string>;
    onLink: (rowIndex: number, participantId: number, label: string) => void;
    onUnlink: (rowIndex: number) => void;
}) {
    const t = useTranslations("importExport.history");
    const attention = preview.rows.filter((row) =>
        row.status === "needs_review" || row.status === "invalid",
    );
    const settled = preview.rows.filter((row) =>
        row.status === "ready" || row.status === "already_imported",
    );
    const shown = [
        ...attention,
        ...settled.slice(0, Math.max(0, 100 - attention.length)),
    ].sort((left, right) => left.rowIndex - right.rowIndex);
    return (
        <div className="space-y-5">
            {stale && (
                <p className="rounded-lg border border-warning/40 bg-warning/5 px-3 py-2 text-sm" role="status">
                    {t("stale")}
                </p>
            )}
            <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
                <ReviewCount label={t("counts.ready")} value={preview.toCreate} />
                <ReviewCount label={t("counts.imported")} value={preview.alreadyImported} />
                <ReviewCount label={t("counts.review")} value={preview.needsReview} />
                <ReviewCount label={t("counts.invalid")} value={preview.invalid} destructive />
            </div>

            {shown.length > 0 && (
                <div className="space-y-2">
                    <p className="text-xs text-muted-foreground">
                        {t("reviewRows", { shown: shown.length, total: preview.rows.length })}
                    </p>
                    <ul className="space-y-2">
                        {shown.map((row) => (
                            <li key={row.rowIndex} className="space-y-3 rounded-xl border border-border bg-card p-3">
                                <div className="flex items-start justify-between gap-3">
                                    <div className="min-w-0 space-y-1">
                                        <div className="flex items-center gap-2">
                                            <span className="text-xs text-muted-foreground">#{row.rowIndex + 1}</span>
                                            <Badge variant={row.status === "invalid" ? "destructive" : "secondary"}>
                                                {t(`statuses.${row.status}`)}
                                            </Badge>
                                        </div>
                                        {row.errors && row.errors.length > 0 && (
                                            <p className="text-sm text-destructive">{row.errors.join("; ")}</p>
                                        )}
                                        {row.participantLabel && (
                                            <p className="truncate text-sm text-muted-foreground">
                                                {t("matchedContact", { name: row.participantLabel })}
                                            </p>
                                        )}
                                    </div>
                                    {row.status === "needs_review" && (
                                        <ContactLinker
                                            linkedLabel={links[row.rowIndex] ? linkLabels[row.rowIndex] : undefined}
                                            onLink={(id, label) => onLink(row.rowIndex, id, label)}
                                            onClear={() => onUnlink(row.rowIndex)}
                                        />
                                    )}
                                </div>
                                {row.candidates && row.candidates.length > 0 && (
                                    <CandidateList
                                        candidates={row.candidates}
                                        linkedId={links[row.rowIndex]}
                                        onUse={(candidate) => onLink(
                                            row.rowIndex,
                                            candidate.recordId,
                                            candidate.name,
                                        )}
                                    />
                                )}
                            </li>
                        ))}
                    </ul>
                </div>
            )}
        </div>
    );
}

function ReviewCount({
    label,
    value,
    destructive = false,
}: {
    label: string;
    value: number;
    destructive?: boolean;
}) {
    return (
        <div
            className="rounded-xl border border-border bg-card px-3 py-3"
            aria-label={`${label}: ${value}`}
        >
            <p className={cn(
                "text-xl font-semibold tabular-nums",
                destructive ? "text-destructive" : "text-foreground",
            )}>
                {value}
            </p>
            <p className="text-xs text-muted-foreground">{label}</p>
        </div>
    );
}

function CandidateList({
    candidates,
    linkedId,
    onUse,
}: {
    candidates: DuplicateCandidate[];
    linkedId?: number;
    onUse: (candidate: DuplicateCandidate) => void;
}) {
    const t = useTranslations("importExport.history");
    return (
        <ul className="space-y-1.5" aria-label={t("candidateLabel")}>
            {candidates.map((candidate) => (
                <li
                    key={`${candidate.recordType}-${candidate.recordId}`}
                    className="flex items-start justify-between gap-3 rounded-lg bg-muted/50 px-3 py-2"
                >
                    <div className="min-w-0">
                        <p className="truncate text-sm font-medium text-foreground">{candidate.name}</p>
                        <p className="text-xs text-muted-foreground">
                            {candidate.matches.map((match) => t(`evidence.${match.kind}`)).join(" · ")}
                            {!candidate.ownedByActiveWorkspace ? ` · ${t("shared")}` : ""}
                        </p>
                    </div>
                    {candidate.ownedByActiveWorkspace && candidate.recordType === "person" && (
                        <Button
                            variant="ghost"
                            size="sm"
                            disabled={linkedId === candidate.recordId}
                            onClick={() => onUse(candidate)}
                        >
                            {linkedId === candidate.recordId ? t("selected") : t("useContact")}
                        </Button>
                    )}
                </li>
            ))}
        </ul>
    );
}

function ContactLinker({
    linkedLabel,
    onLink,
    onClear,
}: {
    linkedLabel?: string;
    onLink: (id: number, label: string) => void;
    onClear: () => void;
}) {
    const t = useTranslations("importExport.history");
    const [open, setOpen] = useState(false);
    const [query, setQuery] = useState("");
    const [attempt, setAttempt] = useState(0);
    const [state, setState] = useState<ContactSearchState>(null);
    const inputRef = useRef<HTMLInputElement>(null);
    const queryText = query.trim();
    const requestKey = queryText.length >= 2 ? `${queryText}\u0000${attempt}` : null;

    useEffect(() => {
        if (!open || !requestKey) return;
        const controller = new AbortController();
        const handle = window.setTimeout(() => {
            search(queryText, { signal: controller.signal })
                .then((response) => {
                    if (controller.signal.aborted) return;
                    setState({
                        key: requestKey,
                        status: "success",
                        options: response.people.slice(0, 6).map((person) => ({
                            kind: "contact",
                            id: person.id,
                            label: person.name || `#${person.id}`,
                        })),
                    });
                })
                .catch(() => {
                    if (!controller.signal.aborted) setState({ key: requestKey, status: "error" });
                });
        }, 250);
        return () => {
            window.clearTimeout(handle);
            controller.abort();
        };
    }, [open, queryText, requestKey]);

    if (linkedLabel) {
        return (
            <button
                type="button"
                onClick={onClear}
                className="inline-flex max-w-48 items-center gap-1 rounded-md bg-accent px-2 py-1 text-xs text-accent-foreground"
                aria-label={t("clearLink", { name: linkedLabel })}
            >
                <span className="truncate">{linkedLabel}</span>
                <XMarkIcon className="size-3 shrink-0" />
            </button>
        );
    }

    if (!open) {
        return (
            <Button variant="outline" size="sm" onClick={() => setOpen(true)}>
                {t("findContact")}
            </Button>
        );
    }

    const current = state?.key === requestKey ? state : null;
    const searchState = queryText.length < 2 ? "idle" : current?.status ?? "loading";
    const options: ContactOption[] = current?.status === "success"
        ? current.options
        : current?.status === "error"
          ? [{ kind: "retry", id: "retry", label: t("searchRetry") }]
          : [];
    const status = searchState === "idle"
        ? t("searchPrompt")
        : searchState === "loading"
          ? t("searching")
          : searchState === "error"
            ? t("searchError")
            : options.length === 0
              ? t("searchEmpty")
              : t("searchResults", { count: options.length });

    return (
        <Combobox
            items={options}
            value={null}
            inputValue={query}
            open
            filter={null}
            itemToStringLabel={(item: ContactOption) => item.label}
            onOpenChange={(next) => {
                setOpen(next);
                if (!next) {
                    setQuery("");
                    setState(null);
                }
            }}
            onInputValueChange={(value, details) => {
                if (details.reason !== "item-press") setQuery(value);
            }}
            onValueChange={(item, details) => {
                if (!item) return;
                if (item.kind === "retry") {
                    details.cancel();
                    setAttempt((currentAttempt) => currentAttempt + 1);
                    inputRef.current?.focus();
                    return;
                }
                onLink(item.id, item.label);
                setOpen(false);
                setQuery("");
                setState(null);
            }}
        >
            <ComboboxInput
                ref={inputRef}
                autoFocus
                showTrigger={false}
                placeholder={t("searchPlaceholder")}
                aria-label={t("searchLabel")}
                className="h-8 w-48 text-xs"
            />
            <ComboboxContent className="w-56 min-w-56">
                <ComboboxEmpty className="justify-start px-2 py-2 text-xs">{status}</ComboboxEmpty>
                <ComboboxList>
                    {options.map((option) => (
                        <ComboboxItem key={option.id} value={option} className="text-xs">
                            {option.label}
                        </ComboboxItem>
                    ))}
                </ComboboxList>
            </ComboboxContent>
        </Combobox>
    );
}

function DoneStep({
    kind,
    parsed,
    result,
}: {
    kind: HistoryImportKind;
    parsed: ParsedCsv;
    result: HistoryImportResult;
}) {
    const t = useTranslations("importExport.history");

    function downloadErrors() {
        const lines = [[...parsed.headers, "error"].map(csvCell).join(",")];
        for (const failure of result.failed) {
            const row = parsed.rows[failure.rowIndex];
            if (!row) continue;
            lines.push([
                ...parsed.headers.map((header) => row[header] ?? ""),
                failure.reason,
            ].map(csvCell).join(","));
        }
        const blob = new Blob(
            [String.fromCharCode(0xfeff) + lines.join("\r\n")],
            { type: "text/csv;charset=utf-8" },
        );
        const url = URL.createObjectURL(blob);
        const anchor = document.createElement("a");
        anchor.href = url;
        anchor.download = `history-import-errors-${kind}.csv`;
        anchor.click();
        URL.revokeObjectURL(url);
    }

    return (
        <div className="space-y-4">
            <div className="flex items-start gap-3">
                <CheckCircleIcon className="size-8 shrink-0 text-brand" />
                <div>
                    <p className="font-medium text-foreground">{t("doneTitle")}</p>
                    <p className="text-sm text-muted-foreground">
                        {t("doneSummary", { created: result.created, skipped: result.skipped })}
                    </p>
                </div>
            </div>
            {result.failed.length > 0 && (
                <div className="flex items-center justify-between gap-3 rounded-xl border border-border bg-card px-3 py-3">
                    <span className="flex items-center gap-2 text-sm text-muted-foreground">
                        <DocumentTextIcon className="size-4" />
                        {t("failed", { count: result.failed.length })}
                    </span>
                    <Button variant="outline" size="sm" onClick={downloadErrors}>
                        {t("downloadErrors")}
                    </Button>
                </div>
            )}
        </div>
    );
}

function csvCell(value: string): string {
    let cell = value ?? "";
    if (cell && /^[=+\-@\t\r]/.test(cell)) cell = `'${cell}`;
    if (/[",\n\r]/.test(cell)) cell = `"${cell.replace(/"/g, '""')}"`;
    return cell;
}
