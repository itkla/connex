"use client";

import { useState } from "react";
import { useTranslations } from "next-intl";

import {
    applyWorkflowMergeChoice,
    type WorkflowEditorDocument,
    type WorkflowMergeConflict,
} from "@/app/components/settings/workflows/workflowEditorReducer";
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
import {
    Select,
    SelectContent,
    SelectItem,
    SelectTrigger,
    SelectValue,
} from "@/components/ui/select";

function workflowConflictKey(conflict: WorkflowMergeConflict): string {
    return `${conflict.kind}:${conflict.key}`;
}

/** Per-item revision conflict recovery that never overwrites the retained local draft implicitly. */
export default function WorkflowConflictDialog({
    open,
    document,
    conflicts,
    onCancel,
    onResolve,
}: {
    open: boolean;
    document: WorkflowEditorDocument;
    conflicts: WorkflowMergeConflict[];
    onCancel: () => void;
    onResolve: (document: WorkflowEditorDocument) => void;
}) {
    const t = useTranslations("WorkspaceWorkflows");
    const [choices, setChoices] = useState<Record<string, "local" | "server">>({});

    const valueLabel = (conflict: WorkflowMergeConflict, side: "local" | "server") => {
        const value = side === "local" ? conflict.localValue : conflict.serverValue;
        if (value == null || value === "") return t("conflict.empty");
        if (typeof value === "string") return value;
        const serialized = JSON.stringify(value);
        return serialized.length > 240 ? `${serialized.slice(0, 237)}…` : serialized;
    };
    const complete = conflicts.every((conflict) => choices[workflowConflictKey(conflict)] != null);
    const resolve = () => {
        let resolved = document;
        for (const conflict of conflicts) {
            const choice = choices[workflowConflictKey(conflict)];
            if (choice) resolved = applyWorkflowMergeChoice(resolved, conflict, choice);
        }
        setChoices({});
        onResolve(resolved);
    };
    const cancel = () => {
        setChoices({});
        onCancel();
    };

    return (
        <ResponsiveDialog open={open} onOpenChange={(next) => {
            if (!next) cancel();
        }}>
            <ResponsiveDialogContent className="sm:max-w-2xl" showCloseButton={false}>
                <ResponsiveDialogHeader className="px-4 pt-4 sm:px-0 sm:pt-0">
                    <ResponsiveDialogTitle>{t("conflict.title")}</ResponsiveDialogTitle>
                    <ResponsiveDialogDescription>{t("conflict.description")}</ResponsiveDialogDescription>
                </ResponsiveDialogHeader>
                <div className="max-h-[55dvh] space-y-3 overflow-y-auto px-4 sm:px-0">
                    {conflicts.map((conflict) => {
                        const key = workflowConflictKey(conflict);
                        return (
                            <div key={key} className="rounded-xl border border-border p-3">
                                <Label htmlFor={`conflict-${key}`}>{t(`conflict.kind.${conflict.kind}`)}</Label>
                                <p className="mt-1 text-xs text-muted-foreground">{t("conflict.item", { key: conflict.key })}</p>
                                <dl className="mt-2 grid gap-2 text-xs sm:grid-cols-2">
                                    <div className="min-w-0 rounded-lg bg-muted/50 p-2">
                                        <dt className="font-medium text-foreground">{t("conflict.localValue")}</dt>
                                        <dd className="mt-1 break-words font-mono text-muted-foreground">{valueLabel(conflict, "local")}</dd>
                                    </div>
                                    <div className="min-w-0 rounded-lg bg-muted/50 p-2">
                                        <dt className="font-medium text-foreground">{t("conflict.serverValue")}</dt>
                                        <dd className="mt-1 break-words font-mono text-muted-foreground">{valueLabel(conflict, "server")}</dd>
                                    </div>
                                </dl>
                                <Select
                                    value={choices[key]}
                                    onValueChange={(choice) => setChoices((current) => ({
                                        ...current,
                                        [key]: choice === "local" ? "local" : "server",
                                    }))}
                                >
                                    <SelectTrigger id={`conflict-${key}`} size="sm" className="mt-3 w-full">
                                        <SelectValue placeholder={t("conflict.choose")} />
                                    </SelectTrigger>
                                    <SelectContent>
                                        <SelectItem value="local">{t("conflict.keepLocal")}</SelectItem>
                                        <SelectItem value="server">{t("conflict.useServer")}</SelectItem>
                                    </SelectContent>
                                </Select>
                            </div>
                        );
                    })}
                </div>
                <ResponsiveDialogFooter className="border-t border-border px-4 py-4 sm:border-0 sm:px-0 sm:py-0">
                    <Button variant="outline" onClick={cancel}>{t("conflict.keepEditing")}</Button>
                    <Button variant="brand" disabled={!complete} onClick={resolve}>{t("conflict.apply")}</Button>
                </ResponsiveDialogFooter>
            </ResponsiveDialogContent>
        </ResponsiveDialog>
    );
}
