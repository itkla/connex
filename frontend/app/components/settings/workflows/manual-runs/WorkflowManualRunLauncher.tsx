"use client";

import { useEffect, useMemo, useRef, useState, useSyncExternalStore } from "react";
import Link from "next/link";
import { BoltIcon, CheckCircleIcon, ExclamationTriangleIcon } from "@heroicons/react/24/outline";
import { useLocale, useTranslations } from "next-intl";

import {
    cancelWorkflowManualRun,
    confirmWorkflowManualRun,
    getWorkflowManualRun,
    getWorkflowManualOptions,
    getUsers,
    prepareWorkflowManualRun,
} from "@/app/lib/api";
import type {
    WorkflowInvocationResult,
    WorkflowManualOptions,
    WorkflowInputValue,
    WorkflowManualPreparation,
    WorkflowManualScope,
    WorkflowManualSourceSurface,
} from "@/app/lib/types";
import type { RecordType } from "@/app/lib/actions/types";
import RecordSelect from "@/app/components/records/RecordSelect";
import { useWorkflowRecordSearch } from "@/app/components/settings/workflows/useWorkflowRecordSearch";
import WorkflowLaunchInputs from "@/app/components/settings/workflows/WorkflowLaunchInputs";
import { workflowInputsComplete } from "@/app/components/settings/workflows/workflowValues";
import { isWorkflowManualScopeValid } from "@/app/lib/workflowOperations";
import { MANUAL_RUN_RECORD_TYPES } from "@/app/components/settings/workflows/vocabulary";
import {
    formatWorkflowRunDateTime,
    normalizeWorkflowRunDateTime,
} from "@/app/components/settings/workflows/workflowRunStatus";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import {
    ResponsiveDialog,
    ResponsiveDialogClose,
    ResponsiveDialogContent,
    ResponsiveDialogDescription,
    ResponsiveDialogFooter,
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
import { Skeleton } from "@/components/ui/skeleton";

type LauncherPhase = "choose" | "preparing" | "prepared" | "confirming" | "complete";

const subscribeToHydration = () => () => undefined;
const getClientHydrationSnapshot = () => true;
const getServerHydrationSnapshot = () => false;

function scopeCount(scope: WorkflowManualScope | null): number | null {
    if (!scope) return null;
    if (scope.kind === "single_record") return 1;
    if (scope.kind === "page_selection" || scope.kind === "explicit_selection") return scope.recordIds.length;
    if (scope.kind === "command_palette") return scopeCount(scope.resolvedScope);
    return null;
}

/** Extracts an exact single record for discovery without approximating a bulk selection. */
function singleScopeRecordId(scope: WorkflowManualScope | null): number | null {
    if (scope?.kind === "command_palette") return singleScopeRecordId(scope.resolvedScope);
    return scope?.kind === "single_record" ? scope.recordId : null;
}

/** Exact-scope preparation and confirmation surface shared by record actions and bulk actions. */
export default function WorkflowManualRunLauncher({
    open,
    onOpenChange,
    requestInit,
    recordType,
    sourceSurface,
    initialScope,
    initialWorkflowId,
}: {
    open: boolean;
    onOpenChange: (open: boolean) => void;
    requestInit: RequestInit;
    recordType: RecordType | null;
    sourceSurface: WorkflowManualSourceSurface;
    initialScope: WorkflowManualScope | null;
    initialWorkflowId?: number;
}) {
    const t = useTranslations("WorkflowOperations");
    const tr = useTranslations("WorkflowAuthoring");
    const [phase, setPhase] = useState<LauncherPhase>("choose");
    const [manualOptions, setManualOptions] = useState<WorkflowManualOptions | null>(null);
    const [primaryType, setPrimaryType] = useState<string>(recordType ?? "person");
    const [members, setMembers] = useState<Array<{ id: number; name: string }>>([]);
    const [inputs, setInputs] = useState<Record<string, WorkflowInputValue>>({});
    const [workflowId, setWorkflowId] = useState(initialWorkflowId ? String(initialWorkflowId) : "");
    const [recordId, setRecordId] = useState("");
    const [preparation, setPreparation] = useState<WorkflowManualPreparation | null>(null);
    const [result, setResult] = useState<WorkflowInvocationResult | null>(null);
    const [error, setError] = useState<string | null>(null);
    const idempotencyKeyRef = useRef<string | null>(null);
    const enteredRecordId = /^\d+$/.test(recordId) ? Number(recordId) : null;
    const discoveryRecordId = singleScopeRecordId(initialScope) ?? enteredRecordId;

    useEffect(() => {
        if (!open) return;
        const controller = new AbortController();
        void getWorkflowManualOptions(primaryType, discoveryRecordId, { ...requestInit, signal: controller.signal })
            .then((result) => {
                if (controller.signal.aborted) return;
                setManualOptions(result);
                setWorkflowId((current) =>
                    result.options.some((item) => String(item.workflowId) === current) ? current : "");
            })
            .catch(() => {
                if (!controller.signal.aborted) setError(t("manual.errors.workflows"));
            });
        return () => controller.abort();
    }, [discoveryRecordId, open, primaryType, requestInit, t]);

    useEffect(() => {
        if (!open) return;
        const controller = new AbortController();
        void getUsers({ ...requestInit, signal: controller.signal }).then((users) => {
            if (!controller.signal.aborted) setMembers(users.map((user) => ({ id: user.id, name: user.displayName || user.username })));
        }).catch(() => {
            if (!controller.signal.aborted) setError(t("manual.errors.members"));
        });
        return () => controller.abort();
    }, [open, requestInit, t]);

    const workflows = manualOptions?.recordType === primaryType && manualOptions.recordId === discoveryRecordId
        ? manualOptions.options : null;
    const selectedWorkflow = useMemo(
        () => workflows?.find((workflow) => String(workflow.workflowId) === workflowId) ?? null,
        [workflowId, workflows],
    );
    const recordSearch = useWorkflowRecordSearch(primaryType, requestInit, open && initialScope === null);
    const changeWorkflow = (value: string) => {
        setWorkflowId(value);
        setPreparation(null);
        setInputs({});
        idempotencyKeyRef.current = null;
    };
    const enteredScope = enteredRecordId && enteredRecordId > 0
        ? { kind: "single_record" as const, recordId: enteredRecordId }
        : null;
    const scope = initialScope ?? (enteredScope && sourceSurface === "command_palette"
        ? { kind: "command_palette" as const, resolvedScope: enteredScope }
        : enteredScope);

    const prepare = async () => {
        if (!selectedWorkflow?.available || !scope || !isWorkflowManualScopeValid(scope)
            || !workflowInputsComplete(selectedWorkflow.inputs, inputs)) return;
        setPhase("preparing");
        setError(null);
        try {
            const prepared = await prepareWorkflowManualRun(
                selectedWorkflow.workflowId,
                { sourceSurface, scope, inputs },
                requestInit,
            );
            setPreparation(prepared);
            setPhase("prepared");
        } catch {
            setError(t("manual.errors.prepare"));
            setPhase("choose");
        }
    };

    const confirm = async () => {
        if (!preparation) return;
        setPhase("confirming");
        setError(null);
        idempotencyKeyRef.current ??= crypto.randomUUID();
        try {
            const confirmed = await confirmWorkflowManualRun(
                preparation.workflowId,
                preparation.scopeToken,
                preparation.scopeHash,
                idempotencyKeyRef.current,
                requestInit,
            );
            setResult(confirmed);
            setPhase("complete");
        } catch {
            setError(t("manual.errors.confirm"));
            setPhase("prepared");
        }
    };

    const refreshResult = async () => {
        if (!preparation || !result) return;
        setError(null);
        try {
            setResult(await getWorkflowManualRun(preparation.workflowId, result.invocationId, requestInit));
        } catch {
            setError(t("manual.errors.result"));
        }
    };

    const cancelInvocation = async () => {
        if (!preparation || !result) return;
        setError(null);
        try {
            setResult(await cancelWorkflowManualRun(preparation.workflowId, result.invocationId, requestInit));
        } catch {
            setError(t("manual.errors.cancel"));
        }
    };

    const reviewAgain = () => {
        setPreparation(null);
        setResult(null);
        setError(null);
        idempotencyKeyRef.current = null;
        setPhase("choose");
    };

    return (
        <ResponsiveDialog open={open} onOpenChange={onOpenChange}>
            <ResponsiveDialogContent className="sm:max-w-2xl" showCloseButton={false}>
                <ResponsiveDialogHeader className="px-4 pt-4 sm:px-0 sm:pt-0">
                    <ResponsiveDialogTitle>{t("manual.title")}</ResponsiveDialogTitle>
                    <ResponsiveDialogDescription>{t("manual.description")}</ResponsiveDialogDescription>
                </ResponsiveDialogHeader>

                <div className="max-h-[65dvh] space-y-5 overflow-y-auto px-4 py-4 sm:px-0">
                    <Alert>
                        <BoltIcon />
                        <AlertTitle>{t("manual.distinctionTitle")}</AlertTitle>
                        <AlertDescription>{t("manual.distinctionBody")}</AlertDescription>
                    </Alert>

                    {error ? (
                        <Alert variant="destructive">
                            <ExclamationTriangleIcon />
                            <AlertTitle>{t("manual.errors.title")}</AlertTitle>
                            <AlertDescription>{error}</AlertDescription>
                        </Alert>
                    ) : null}

                    {phase === "choose" || phase === "preparing" ? (
                        <div className="space-y-4">
                            {recordType === null ? (
                                <div className="space-y-2">
                                    <Label htmlFor="manual-record-type">{tr("recordType")}</Label>
                                    <Select value={primaryType} disabled={phase === "preparing"} onValueChange={(type) => {
                                        setPrimaryType(type);
                                        setRecordId("");
                                        setManualOptions(null);
                                        changeWorkflow("");
                                        recordSearch.searchRecords("");
                                    }}>
                                        <SelectTrigger id="manual-record-type" className="w-full"><SelectValue /></SelectTrigger>
                                        <SelectContent>{MANUAL_RUN_RECORD_TYPES.map((type) => <SelectItem key={type} value={type}>{tr(`record.${type}`)}</SelectItem>)}</SelectContent>
                                    </Select>
                                </div>
                            ) : null}
                            <div className="space-y-2">
                                <Label htmlFor="manual-workflow">{t("manual.workflowLabel")}</Label>
                                {workflows === null ? (
                                    <Skeleton className="h-9 w-full" />
                                ) : (
                                    <Select value={workflowId} onValueChange={changeWorkflow} disabled={phase === "preparing"}>
                                        <SelectTrigger id="manual-workflow" className="w-full">
                                            <SelectValue placeholder={t("manual.workflowPlaceholder")} />
                                        </SelectTrigger>
                                        <SelectContent>
                                            {workflows.map((workflow) => (
                                                <SelectItem key={workflow.workflowId} value={String(workflow.workflowId)}>
                                                    {workflow.workflowName}{!workflow.available ? ` · ${t("manual.unavailable")}` : ""}
                                                </SelectItem>
                                            ))}
                                        </SelectContent>
                                    </Select>
                                )}
                                {workflows?.length === 0 ? (
                                    <div className="space-y-2">
                                        <p className="text-sm text-muted-foreground">{t("manual.noWorkflows")}</p>
                                        <Button variant="outline" asChild>
                                            <Link href={manualOptions?.createHref.startsWith("/workflows/new?")
                                                ? manualOptions.createHref
                                                : `/workflows/new?start=manual&recordType=${encodeURIComponent(primaryType)}`}>
                                                {t("manual.createWorkflow")}
                                            </Link>
                                        </Button>
                                    </div>
                                ) : null}
                            </div>

                            {initialScope === null ? (
                                <div className="space-y-2">
                                    <Label htmlFor="manual-record">{t("manual.recordLabel")}</Label>
                                    <RecordSelect
                                        id="manual-record"
                                        options={recordSearch.records}
                                        value={recordId}
                                        onValueChange={(value) => {
                                            setRecordId(value);
                                            setError(null);
                                            setPreparation(null);
                                            idempotencyKeyRef.current = null;
                                        }}
                                        onInputValueChange={recordSearch.searchRecords}
                                        placeholder={t("manual.recordPlaceholder")}
                                        emptyLabel={t("manual.recordEmpty")}
                                        disabled={phase === "preparing"}
                                    />
                                    {recordSearch.failed ? <p role="alert" className="text-sm text-destructive">{t("manual.errors.recordSearch")}</p> : null}
                                </div>
                            ) : (
                                <div className="rounded-xl border border-border bg-muted/35 p-4">
                                    <p className="text-sm font-medium text-foreground">{t("manual.scopeTitle")}</p>
                                    <p className="mt-1 text-sm text-muted-foreground">
                                        {t(`manual.scope.${initialScope.kind}`, { count: scopeCount(initialScope) ?? 0 })}
                                    </p>
                                </div>
                            )}
                            {selectedWorkflow ? (
                                <>
                                    <div className="space-y-2 border-t border-border pt-4 text-sm">
                                        <p>{t("manual.runsAs", { actor: selectedWorkflow.execution.mode === "system" ? t("manual.systemActor") : selectedWorkflow.execution.actorLabel ?? t("manual.actorUnavailable") })}</p>
                                        {!selectedWorkflow.available ? (
                                            <Alert>
                                                <ExclamationTriangleIcon />
                                                <AlertTitle>{t("manual.unavailable")}</AlertTitle>
                                                <AlertDescription>
                                                    {selectedWorkflow.reasons.map((reason) => <p key={reason}>{t.has(`manual.availability.${reason}`) ? t(`manual.availability.${reason}`) : t("manual.availability.configuration_unavailable")}</p>)}
                                                </AlertDescription>
                                            </Alert>
                                        ) : null}
                                    </div>
                                    <WorkflowLaunchInputs
                                        definitions={selectedWorkflow.inputs}
                                        values={inputs}
                                        members={members}
                                        disabled={phase === "preparing" || !selectedWorkflow.available}
                                        onChange={(key, value) => {
                                            setInputs((current) => ({ ...current, [key]: value }));
                                            setPreparation(null);
                                            idempotencyKeyRef.current = null;
                                        }}
                                    />
                                </>
                            ) : null}
                        </div>
                    ) : null}

                    {(phase === "prepared" || phase === "confirming") && preparation ? (
                        <PreparationSummary preparation={preparation} />
                    ) : null}

                    {phase === "complete" && result && preparation ? (
                        <InvocationSummary result={result} preparation={preparation} />
                    ) : null}
                </div>

                <ResponsiveDialogFooter className="border-t border-border px-4 py-4 sm:border-0 sm:px-0 sm:py-0">
                    <ResponsiveDialogClose asChild>
                        <Button variant="outline">{t("manual.close")}</Button>
                    </ResponsiveDialogClose>
                    {phase === "choose" || phase === "preparing" ? (
                        <Button
                            variant="brand"
                            disabled={!selectedWorkflow?.available || !scope || !isWorkflowManualScopeValid(scope)
                                || !workflowInputsComplete(selectedWorkflow?.inputs ?? [], inputs) || phase === "preparing"}
                            onClick={() => void prepare()}
                        >
                            {t(phase === "preparing" ? "manual.preparing" : "manual.review")}
                        </Button>
                    ) : null}
                    {phase === "prepared" || phase === "confirming" ? (
                        <>
                            <Button variant="outline" disabled={phase === "confirming"} onClick={reviewAgain}>{t("manual.editSelection")}</Button>
                            <Button
                                variant="brand"
                                disabled={!preparation?.confirmable || phase === "confirming"}
                                onClick={() => void confirm()}
                            >
                                {t(phase === "confirming" ? "manual.confirming" : "manual.confirm")}
                            </Button>
                        </>
                    ) : null}
                    {phase === "complete" && result ? (
                        <>
                            <Button variant="outline" onClick={reviewAgain}>{t("manual.newRun")}</Button>
                            <Button variant="outline" onClick={() => void refreshResult()}>{t("manual.refresh")}</Button>
                            {result.status === "prepared" || result.status === "running" ? (
                                <Button variant="outline" onClick={() => void cancelInvocation()}>{t("manual.cancel")}</Button>
                            ) : null}
                        </>
                    ) : null}
                </ResponsiveDialogFooter>
            </ResponsiveDialogContent>
        </ResponsiveDialog>
    );
}

function PreparationSummary({ preparation }: { preparation: WorkflowManualPreparation }) {
    const t = useTranslations("WorkflowOperations");
    const locale = useLocale();
    const normalizedExpiresAt = useMemo(
        () => normalizeWorkflowRunDateTime(preparation.expiresAt),
        [preparation.expiresAt],
    );
    const hydrated = useSyncExternalStore(
        subscribeToHydration,
        getClientHydrationSnapshot,
        getServerHydrationSnapshot,
    );
    const expiresAtLabel = useMemo(
        () => hydrated ? formatWorkflowRunDateTime(preparation.expiresAt, locale) : normalizedExpiresAt,
        [hydrated, locale, normalizedExpiresAt, preparation.expiresAt],
    );
    const expectedSkips = [
        ["permission", preparation.expectedSkips.permission],
        ["staleState", preparation.expectedSkips.staleState],
        ["missingReference", preparation.expectedSkips.missingReference],
        ["limit", preparation.expectedSkips.limit],
        ["unsupportedContext", preparation.expectedSkips.unsupportedContext],
    ] as const;
    return (
        <div className="space-y-4">
            <div className="flex flex-wrap items-center gap-2">
                <Badge variant="outline">{t("manual.version", { number: preparation.versionNumber })}</Badge>
                <Badge variant="outline">{t("manual.exactCount", { count: preparation.exactCount })}</Badge>
                <Badge variant="outline">{t("manual.readyCount", { count: preparation.readyCount })}</Badge>
            </div>
            <dl className="grid gap-3 rounded-xl border border-border p-4 text-sm sm:grid-cols-2">
                <div>
                    <dt className="text-muted-foreground">{t("manual.actor")}</dt>
                    <dd className="font-medium text-foreground">
                        {preparation.executionMode === "system"
                            ? t("manual.systemActor")
                            : preparation.actorLabel ?? t("manual.actorUnavailable")}
                    </dd>
                </div>
                <div>
                    <dt className="text-muted-foreground">{t("manual.expires")}</dt>
                    <dd className="font-medium text-foreground">
                        <time dateTime={normalizedExpiresAt}>{expiresAtLabel}</time>
                    </dd>
                </div>
            </dl>
            {(preparation.resolvedInputs?.length ?? 0) > 0 ? (
                <section className="space-y-2">
                    <h3 className="text-sm font-semibold text-foreground">{t("manual.resolvedInputsTitle")}</h3>
                    <dl className="divide-y divide-border rounded-xl border border-border px-3">
                        {preparation.resolvedInputs?.map((input) => <div key={input.key} className="grid gap-1 py-3 sm:grid-cols-2">
                            <dt className="text-sm text-muted-foreground">{input.label}</dt>
                            <dd className="break-words text-sm font-medium text-foreground">{input.displayValue}</dd>
                        </div>)}
                    </dl>
                </section>
            ) : null}
            {(preparation.effectSamples?.length ?? 0) > 0 ? (
                <section className="space-y-2">
                    <h3 className="text-sm font-semibold text-foreground">{t("manual.effectSamplesTitle")}</h3>
                    <ul className="divide-y divide-border rounded-xl border border-border px-3">
                        {preparation.effectSamples?.map((effect) => <li key={`${effect.recordId}:${effect.nodeId}`} className="space-y-1 py-3 text-sm">
                            <p className="font-medium text-foreground">{preparation.samples.find((sample) => sample.recordId === effect.recordId)?.label ?? t("manual.recordLabelNotIncluded")}</p>
                            {effect.title ? <p className="text-foreground">{effect.title}</p> : null}
                            {effect.body ? <p className="whitespace-pre-wrap text-muted-foreground">{effect.body}</p> : null}
                            {effect.targetLabel ? <p className="text-muted-foreground">{t("manual.effectAssignee", { name: effect.targetLabel })}</p> : null}
                            {effect.dueDate ? <p className="text-muted-foreground">{t("manual.effectDueDate", { date: effect.dueDate })}</p> : null}
                            {!effect.title && !effect.body && !effect.targetLabel ? <p className="text-muted-foreground">{t("manual.effectUnresolved")}</p> : null}
                        </li>)}
                    </ul>
                </section>
            ) : null}
            <section className="space-y-2">
                <h3 className="text-sm font-semibold text-foreground">{t("manual.actionsTitle")}</h3>
                <ul className="divide-y divide-border rounded-xl border border-border">
                    {preparation.actions.map((action) => (
                        <li key={action.nodeId} className="flex items-center justify-between gap-3 px-3 py-2.5 text-sm">
                            <span className="font-medium text-foreground">
                                {t.has(`sideEffect.${action.actionType}`)
                                    ? t(`sideEffect.${action.actionType}`)
                                    : t("manual.unknownAction")}
                            </span>
                            <Badge variant="outline">{t(`retrySafety.${action.retrySafety}`)}</Badge>
                        </li>
                    ))}
                </ul>
            </section>
            <section className="space-y-2">
                <h3 className="text-sm font-semibold text-foreground">{t("manual.expectedSkipsTitle")}</h3>
                <dl className="grid grid-cols-2 gap-px overflow-hidden rounded-xl border border-border bg-border sm:grid-cols-5">
                    {expectedSkips.map(([reason, count]) => (
                        <div key={reason} className="bg-card p-3">
                            <dt className="text-xs text-muted-foreground">{t(`expectedSkip.${reason}`)}</dt>
                            <dd className="mt-1 font-semibold tabular-nums text-foreground">{count}</dd>
                        </div>
                    ))}
                </dl>
            </section>
            {preparation.samples.length > 0 ? (
                <section className="space-y-2">
                    <h3 className="text-sm font-semibold text-foreground">{t("manual.samplesTitle")}</h3>
                    <ul className="divide-y divide-border overflow-hidden rounded-xl border border-border">
                        {preparation.samples.map((sample) => (
                            <li key={sample.recordId} className="truncate px-3 py-2 text-sm text-foreground">
                                {sample.label}
                            </li>
                        ))}
                    </ul>
                </section>
            ) : null}
            {preparation.blockers.length > 0 ? (
                <Alert variant="destructive">
                    <ExclamationTriangleIcon />
                    <AlertTitle>{t("manual.blockersTitle")}</AlertTitle>
                    <AlertDescription>
                        <ul className="list-disc space-y-1 pl-4">
                            {preparation.blockers.map((blocker) => <li key={blocker}>{t(`blocker.${blocker}`)}</li>)}
                        </ul>
                    </AlertDescription>
                </Alert>
            ) : null}
        </div>
    );
}

function InvocationSummary({
    result,
    preparation,
}: {
    result: WorkflowInvocationResult;
    preparation: WorkflowManualPreparation;
}) {
    const t = useTranslations("WorkflowOperations");
    const sampledLabels = useMemo(
        () => new Map(
            [...preparation.samples, ...preparation.skippedSamples]
                .map((sample) => [sample.recordId, sample.label]),
        ),
        [preparation.samples, preparation.skippedSamples],
    );
    const totals = [
        ["queued", result.queuedCount],
        ["running", result.runningCount],
        ["waiting", result.waitingCount],
        ["succeeded", result.succeededCount],
        ["failed", result.failedCount],
        ["intervention_required", result.interventionRequiredCount],
        ["cancelled", result.cancelledCount],
        ["skipped", result.skippedCount],
    ] as const;
    return (
        <div className="space-y-4">
            <Alert>
                <CheckCircleIcon />
                <AlertTitle>{t("manual.confirmedTitle")}</AlertTitle>
                <AlertDescription>{t("manual.confirmedBody", { count: result.exactCount })}</AlertDescription>
            </Alert>
            <dl className="grid grid-cols-2 gap-px overflow-hidden rounded-xl border border-border bg-border sm:grid-cols-4">
                {totals.map(([status, count]) => (
                    <div key={status} className="bg-card p-3">
                        <dt className="text-xs text-muted-foreground">{t(`status.${status}`)}</dt>
                        <dd className="mt-1 text-lg font-semibold tabular-nums text-foreground">{count}</dd>
                    </div>
                ))}
            </dl>
            {result.records.some((record) => record.reasonCode) ? (
                <p className="text-sm text-muted-foreground">{t("manual.partialNote")}</p>
            ) : null}
            {result.records.length > 0 ? (
                <section className="space-y-2">
                    <h3 className="text-sm font-semibold text-foreground">{t("manual.recordOutcomesTitle")}</h3>
                    <ul className="divide-y divide-border overflow-hidden rounded-xl border border-border">
                        {result.records.slice(0, 50).map((record) => (
                            <li key={record.recordId} className="grid gap-2 px-3 py-2.5 text-sm sm:grid-cols-[auto_minmax(0,1fr)_auto]">
                                <span className="truncate text-muted-foreground">
                                    {sampledLabels.get(record.recordId)
                                        ?? t("manual.recordLabelNotIncluded")}
                                </span>
                                <span className="text-foreground">
                                    {record.reasonCode ? <ManualReason code={record.reasonCode} /> : t(`status.${record.status}`)}
                                </span>
                                {record.runKey ? (
                                    <Link className="text-brand hover:text-brand-hover" href={`/workflows/${preparation.workflowId}/runs/${encodeURIComponent(record.runKey)}`}>
                                        {t("manual.viewRun")}
                                    </Link>
                                ) : null}
                            </li>
                        ))}
                    </ul>
                    {result.records.length > 50 ? (
                        <p className="text-xs text-muted-foreground">{t("manual.moreRecordIssues", { count: result.records.length - 50 })}</p>
                    ) : null}
                </section>
            ) : null}
        </div>
    );
}

function ManualReason({ code }: { code: string }) {
    const t = useTranslations("WorkflowOperations");
    if (t.has(`blocker.${code}`)) return t(`blocker.${code}`);
    if (t.has(`reason.${code}`)) return t(`reason.${code}`);
    return t("reason.unknown");
}
