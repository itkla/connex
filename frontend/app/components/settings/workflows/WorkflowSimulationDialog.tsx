"use client";

import { useState } from "react";
import { BeakerIcon } from "@heroicons/react/24/outline";
import { Loader2Icon } from "lucide-react";
import { useTranslations } from "next-intl";

import RecordSelect, { type RecordSelectOption } from "@/app/components/records/RecordSelect";
import type { WorkflowDiagnosticCode, WorkflowSimulation } from "@/app/lib/types";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import {
    ResponsiveDialog,
    ResponsiveDialogContent,
    ResponsiveDialogDescription,
    ResponsiveDialogFooter,
    ResponsiveDialogHeader,
    ResponsiveDialogTitle,
} from "@/components/ui/responsive-dialog";

/** Selected-record, read-only workflow traversal preview. */
export default function WorkflowSimulationDialog({
    open,
    records,
    loading,
    result,
    diagnosticMessage,
    onOpenChange,
    onSearch,
    onClear,
    onSimulate,
}: {
    open: boolean;
    records: RecordSelectOption[];
    loading: boolean;
    result: WorkflowSimulation | null;
    diagnosticMessage: (diagnostic: { code: WorkflowDiagnosticCode; params: Record<string, string> }) => string;
    onOpenChange: (open: boolean) => void;
    onSearch: (query: string) => void;
    onClear: () => void;
    onSimulate: (recordId: number) => void;
}) {
    const t = useTranslations("WorkspaceWorkflows");
    const [recordId, setRecordId] = useState("");

    const changeOpen = (next: boolean) => {
        if (!next) {
            setRecordId("");
            onClear();
        }
        onOpenChange(next);
    };

    return (
        <ResponsiveDialog open={open} onOpenChange={changeOpen}>
            <ResponsiveDialogContent className="sm:max-w-2xl">
                <ResponsiveDialogHeader className="px-4 pt-4 sm:px-0 sm:pt-0">
                    <ResponsiveDialogTitle className="flex items-center gap-2">
                        <BeakerIcon className="size-5" />
                        {t("simulation.title")}
                    </ResponsiveDialogTitle>
                    <ResponsiveDialogDescription>{t("simulation.description")}</ResponsiveDialogDescription>
                </ResponsiveDialogHeader>
                <div className="space-y-4 px-4 sm:px-0">
                    <div className="rounded-xl border border-brand/30 bg-brand-light p-3 text-sm text-foreground">
                        <p className="font-medium">{t("simulation.previewOnlyTitle")}</p>
                        <p className="mt-1 text-muted-foreground">{t("simulation.previewOnlyBody")}</p>
                    </div>
                    <div className="space-y-1.5">
                        <Label htmlFor="workflow-simulation-record">{t("simulation.recordLabel")}</Label>
                        <RecordSelect
                            id="workflow-simulation-record"
                            options={records}
                            value={recordId}
                            onValueChange={(value) => {
                                setRecordId(value);
                                onClear();
                            }}
                            placeholder={t("simulation.recordPlaceholder")}
                            emptyLabel={t("simulation.recordEmpty")}
                            onInputValueChange={onSearch}
                        />
                    </div>
                    {result ? (
                        <WorkflowSimulationEvidence result={result} diagnosticMessage={diagnosticMessage} />
                    ) : null}
                </div>
                <ResponsiveDialogFooter className="border-t border-border px-4 py-4 sm:border-0 sm:px-0 sm:py-0">
                    <Button variant="outline" onClick={() => changeOpen(false)}>{t("close")}</Button>
                    <Button
                        variant="brand"
                        disabled={!recordId || loading}
                        onClick={() => {
                            onClear();
                            onSimulate(Number(recordId));
                        }}
                    >
                        {loading ? <Loader2Icon className="size-4 animate-spin motion-reduce:animate-none" /> : <BeakerIcon className="size-4" />}
                        {t("simulation.previewButton")}
                    </Button>
                </ResponsiveDialogFooter>
            </ResponsiveDialogContent>
        </ResponsiveDialog>
    );
}

/** Shared localized evidence renderer for saved-draft and curated-recipe previews. */
export function WorkflowSimulationEvidence({
    result,
    diagnosticMessage,
}: {
    result: WorkflowSimulation;
    diagnosticMessage: (diagnostic: { code: WorkflowDiagnosticCode; params: Record<string, string> }) => string;
}) {
    const t = useTranslations("WorkspaceWorkflows");
    const tr = useTranslations("WorkspaceRules");
    return (
        <div className="space-y-3" aria-live="polite">
            <Badge variant="outline">{t(`simulation.result.${result.result}`)}</Badge>
            {result.path.length > 0 ? (
                <ol className="space-y-2" aria-label={t("simulation.pathLabel")}>
                    {result.path.map((step) => (
                        <li key={step.nodeId} className="rounded-xl border border-border bg-muted/25 p-3">
                            <div className="flex flex-wrap items-center gap-2 text-sm">
                                <span className="font-medium text-foreground">{t(`nodeType.${step.nodeType.toLowerCase()}`)}</span>
                                {step.actionType ? <span className="text-foreground">{tr(`action.${step.actionType}`)}</span> : null}
                                {step.outcome ? <Badge variant="secondary">{t(`branch.${step.outcome}`)}</Badge> : null}
                                <span className="text-muted-foreground">{diagnosticMessage({ code: step.code, params: {} })}</span>
                            </div>
                        </li>
                    ))}
                </ol>
            ) : null}
            {result.blockers.length > 0 ? (
                <ul className="space-y-1 text-sm text-destructive">
                    {result.blockers.map((blocker) => (
                        <li key={`${blocker.code}:${blocker.nodeId ?? "global"}:${blocker.fieldPath ?? "no-field"}`}>
                            {diagnosticMessage(blocker)}
                        </li>
                    ))}
                </ul>
            ) : null}
        </div>
    );
}
