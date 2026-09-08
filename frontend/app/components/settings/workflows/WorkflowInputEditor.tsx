"use client";

import { PlusIcon, TrashIcon } from "@heroicons/react/24/outline";
import { useTranslations } from "next-intl";

import type { WorkflowFieldProps } from "@/app/components/settings/workflows/workflowDiagnosticFields";
import type { WorkflowInputDefinition, WorkflowInputType, WorkflowInputValue } from "@/app/lib/types";
import { Button } from "@/components/ui/button";
import { IconButton } from "@/components/ui/icon-button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Switch } from "@/components/ui/switch";

/** Declares the named, typed values a person supplies before preparing a manual run. */
export default function WorkflowInputEditor({ inputs, members, disabled, fieldProps, automatic = false, onChange, onCommit }: {
    inputs: WorkflowInputDefinition[];
    members: Array<{ id: number; name: string }>;
    disabled: boolean;
    fieldProps: WorkflowFieldProps;
    automatic?: boolean;
    onChange: (inputs: WorkflowInputDefinition[], mode: "transient" | "commit") => void;
    onCommit: () => void;
}) {
    const t = useTranslations("WorkspaceWorkflows");
    const update = (key: string, change: Partial<WorkflowInputDefinition>, mode: "transient" | "commit") => {
        onChange(inputs.map((input) => input.key === key ? { ...input, ...change } : input), mode);
    };
    return (
        <section className="space-y-4 border-t border-border pt-4" {...fieldProps("inputs")} tabIndex={-1}>
            <div>
                <h3 className="text-sm font-semibold text-foreground">{t("inputs.editorTitle")}</h3>
                <p className="mt-1 text-sm text-muted-foreground">{t("inputs.editorHelp")}</p>
            </div>
            {inputs.map((input, index) => (
                <fieldset key={input.key} {...fieldProps(`inputs[${index}]`)} tabIndex={-1} className="space-y-3 border-b border-border pb-4">
                    <legend className="sr-only">{input.label || t("inputs.newInput")}</legend>
                    <div className="flex items-end gap-2">
                        <div className="min-w-0 flex-1 space-y-2">
                            <Label htmlFor={`input-label-${input.key}`}>{t("inputs.label")}</Label>
                            <Input id={`input-label-${input.key}`} value={input.label} maxLength={80} disabled={disabled}
                                onChange={(event) => update(input.key, { label: event.target.value }, "transient")} onBlur={onCommit} />
                        </div>
                        <IconButton label={t("inputs.remove", { name: input.label })} variant="ghost" disabled={disabled}
                            onClick={() => onChange(inputs.filter((candidate) => candidate.key !== input.key), "commit")}><TrashIcon className="size-4" /></IconButton>
                    </div>
                    <div className="space-y-2">
                        <Label htmlFor={`input-type-${input.key}`}>{t("inputs.typeLabel")}</Label>
                        <Select value={input.type} disabled={disabled} onValueChange={(type) => {
                            if (type === "text" || type === "user" || type === "date") update(input.key, { type, defaultValue: undefined }, "commit");
                        }}>
                            <SelectTrigger id={`input-type-${input.key}`} className="w-full"><SelectValue /></SelectTrigger>
                            <SelectContent>{(["text", "user", "date"] satisfies WorkflowInputType[]).map((type) => <SelectItem key={type} value={type}>{t(`inputs.type.${type}`)}</SelectItem>)}</SelectContent>
                        </Select>
                    </div>
                    <div className="flex items-center justify-between gap-3">
                        <Label htmlFor={`input-required-${input.key}`}>{t("inputs.required")}</Label>
                        <Switch id={`input-required-${input.key}`} checked={input.required} disabled={disabled} onCheckedChange={(required) => update(input.key, { required }, "commit")} />
                    </div>
                    <div className="space-y-2">
                        {automatic && input.required && (input.defaultValue == null || input.defaultValue === "") ? <p role="alert" className="text-sm text-destructive">{t("inputs.automaticDefaultRequired")}</p> : null}
                        <Label htmlFor={`input-default-${input.key}`}>{t("inputs.defaultLabel")}</Label>
                        {input.type === "user" ? (
                            <Select value={input.defaultValue == null ? "none" : String(input.defaultValue)} disabled={disabled}
                                onValueChange={(value) => update(input.key, { defaultValue: value === "none" ? undefined : Number(value) }, "commit")}>
                                <SelectTrigger id={`input-default-${input.key}`} className="w-full"><SelectValue /></SelectTrigger>
                                <SelectContent><SelectItem value="none">{t("inputs.noDefault")}</SelectItem>{members.map((member) => <SelectItem key={member.id} value={String(member.id)}>{member.name}</SelectItem>)}</SelectContent>
                            </Select>
                        ) : (
                            <Input id={`input-default-${input.key}`} type={input.type === "date" ? "date" : "text"}
                                value={typeof input.defaultValue === "string" ? input.defaultValue : ""} maxLength={2_000} disabled={disabled}
                                onChange={(event) => update(input.key, { defaultValue: (event.target.value || undefined) satisfies WorkflowInputValue | undefined }, "transient")} onBlur={onCommit} />
                        )}
                    </div>
                </fieldset>
            ))}
            <Button variant="outline" size="toolbar" disabled={disabled || inputs.length >= 16} onClick={() => {
                const key = `input${globalThis.crypto.randomUUID().replaceAll("-", "")}`;
                onChange([...inputs, { key, label: t("inputs.newInput"), type: "text", required: true }], "commit");
            }}><PlusIcon className="size-4" />{t("inputs.add")}</Button>
        </section>
    );
}
