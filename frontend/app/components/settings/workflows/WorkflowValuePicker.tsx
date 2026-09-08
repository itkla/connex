"use client";

import { useMemo } from "react";
import { useTranslations } from "next-intl";
import { PlusIcon, XMarkIcon } from "@heroicons/react/24/outline";

import { workflowDominatingNodes, workflowReferenceKey } from "@/app/components/settings/workflows/workflowValues";
import type { WorkflowCatalog, WorkflowDefinition, WorkflowTextTemplate, WorkflowValueRef } from "@/app/lib/types";
import { Button } from "@/components/ui/button";
import { IconButton } from "@/components/ui/icon-button";
import { Input } from "@/components/ui/input";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";

export type WorkflowValueOption = { ref: WorkflowValueRef; label: string; valueType: string };

const MAX_TEMPLATE_PARTS = 64;

/** Produces permitted record/input and path-safe output choices from the server capability catalog. */
export function useWorkflowValueOptions(definition: WorkflowDefinition, catalog: WorkflowCatalog | null, recordType: string, nodeId: string): WorkflowValueOption[] {
    const t = useTranslations("WorkspaceWorkflows");
    return useMemo(() => {
        const preceding = workflowDominatingNodes(definition, nodeId);
        return [
            ...(definition.inputs ?? []).map((input) => ({ ref: { source: "launch_input" as const, key: input.key }, label: t("values.input", { name: input.label }), valueType: input.type })),
            ...(catalog?.recordFields ?? []).filter((field) => field.recordType === recordType).map((field) => ({
                ref: { source: "record_field" as const, field: field.key },
                label: t("values.record", { name: t.has(`values.field.${field.key}`) ? t(`values.field.${field.key}`) : t("values.unavailable") }), valueType: field.valueType,
            })),
            ...definition.nodes.flatMap((node) => {
                if (!preceding.has(node.id) || node.type !== "ACTION") return [];
                const capability = catalog?.actions.find((action) => action.type === node.config.type && action.recordTypes.includes(recordType));
                return (capability?.outputs ?? []).map((output) => ({ ref: { source: "step_output" as const, nodeId: node.id, output: output.key }, label: t("values.output", { name: node.config.title || t("values.createdTask") }), valueType: output.valueType }));
            }),
        ];
    }, [catalog, definition, nodeId, recordType, t]);
}

/** Selects a typed value reference without requiring template syntax or internal identifiers. */
export function WorkflowValuePicker({ options, value, disabled, onChange, label }: {
    options: WorkflowValueOption[];
    value?: WorkflowValueRef;
    disabled?: boolean;
    onChange: (ref: WorkflowValueRef) => void;
    label?: string;
}) {
    const t = useTranslations("WorkspaceWorkflows");
    return (
        <Select value={value ? workflowReferenceKey(value) : ""} disabled={disabled || options.length === 0} onValueChange={(key) => {
            const option = options.find((option) => workflowReferenceKey(option.ref) === key);
            if (option) onChange(option.ref);
        }}>
            <SelectTrigger className="w-full" aria-label={label ?? t("values.insert")}><SelectValue placeholder={t("values.insert")} /></SelectTrigger>
            <SelectContent>{options.map((option) => <SelectItem key={workflowReferenceKey(option.ref)} value={workflowReferenceKey(option.ref)}>{option.label}</SelectItem>)}</SelectContent>
        </Select>
    );
}

/** Edits a text template as ordered text and named values with explicit missing-value behavior. */
export function WorkflowTextValue({ value, template, options, disabled, maximum, label, onChange, onCommit }: {
    value?: string;
    template?: WorkflowTextTemplate;
    options: WorkflowValueOption[];
    disabled: boolean;
    maximum: number;
    label: string;
    onChange: (value: string | undefined, template: WorkflowTextTemplate | undefined, mode: "transient" | "commit") => void;
    onCommit: () => void;
}) {
    const t = useTranslations("WorkspaceWorkflows");
    return (
        <div className="space-y-2">
            {!template ? <Input value={value ?? ""} maxLength={maximum} disabled={disabled} aria-label={label}
                onChange={(event) => onChange(event.target.value, undefined, "transient")} onBlur={onCommit} /> : (
                <div className="space-y-2">
                    {template.parts.map((part, index) => (
                        <div key={index} className="flex items-center gap-2">
                            {"text" in part ? <Input value={part.text} maxLength={maximum} disabled={disabled} aria-label={label}
                                onChange={(event) => onChange(undefined, { ...template, parts: template.parts.map((part, partIndex) => partIndex === index ? { text: event.target.value } : part) }, "transient")} onBlur={onCommit} />
                                : <span className="min-w-0 flex-1 rounded-md bg-muted px-3 py-2 text-sm">{options.find((option) => workflowReferenceKey(option.ref) === workflowReferenceKey(part.ref))?.label ?? t("values.unavailable")}</span>}
                            <IconButton label={t("values.remove")} variant="ghost" disabled={disabled} onClick={() => {
                                const parts = template.parts.filter((_, partIndex) => partIndex !== index);
                                onChange(parts.length === 0 ? "" : undefined, parts.length === 0 ? undefined : { ...template, parts }, "commit");
                            }}><XMarkIcon className="size-4" /></IconButton>
                        </div>
                    ))}
                    <Button variant="ghost" size="inline" disabled={disabled || template.parts.length >= MAX_TEMPLATE_PARTS} onClick={() => onChange(undefined, { ...template, parts: [...template.parts, { text: "" }] }, "commit")}><PlusIcon className="size-3" />{t("values.addText")}</Button>
                    <Select value={template.missingValue} disabled={disabled} onValueChange={(value) => onChange(undefined, { ...template, missingValue: value === "empty" ? "empty" : "fail" }, "commit")}>
                        <SelectTrigger className="w-full" aria-label={t("values.missingLabel")}><SelectValue /></SelectTrigger>
                        <SelectContent><SelectItem value="fail">{t("values.missingFail")}</SelectItem><SelectItem value="empty">{t("values.missingEmpty")}</SelectItem></SelectContent>
                    </Select>
                </div>
            )}
            <WorkflowValuePicker options={options} label={t("values.insertInto", { field: label })} disabled={disabled || (template?.parts.length ?? 0) >= MAX_TEMPLATE_PARTS} onChange={(ref) => onChange(undefined, {
                parts: [...(template?.parts ?? (value ? [{ text: value }] : [])), { ref }], missingValue: template?.missingValue ?? "fail",
            }, "commit")} />
        </div>
    );
}
