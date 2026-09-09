"use client";

import { useTranslations } from "next-intl";

import type { WorkflowInputDefinition, WorkflowInputValue } from "@/app/lib/types";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";

/** Typed launch fields shared by real execution and side-effect-free preview. */
export default function WorkflowLaunchInputs({
    definitions,
    values,
    members,
    disabled,
    onChange,
}: {
    definitions: WorkflowInputDefinition[];
    values: Record<string, WorkflowInputValue>;
    members: Array<{ id: number; name: string }>;
    disabled?: boolean;
    onChange: (key: string, value: WorkflowInputValue) => void;
}) {
    const t = useTranslations("WorkspaceWorkflows");
    if (definitions.length === 0) return null;
    return (
        <section className="space-y-4">
            <div>
                <h3 className="text-sm font-semibold text-foreground">{t("inputs.launchTitle")}</h3>
                <p className="mt-1 text-sm text-muted-foreground">{t("inputs.launchHelp")}</p>
            </div>
            {definitions.map((input) => {
                const value = Object.hasOwn(values, input.key) ? values[input.key] : input.defaultValue;
                const id = `workflow-input-${input.key}`;
                return (
                    <div key={input.key} className="space-y-2">
                        <Label htmlFor={id}>{input.label}{input.required ? <span aria-hidden> *</span> : null}</Label>
                        {input.type === "user" ? (
                            <Select
                                value={value == null ? "" : String(value)}
                                onValueChange={(value) => onChange(input.key, value === "none" ? null : Number(value))}
                                disabled={disabled}
                            >
                                <SelectTrigger id={id} className="w-full" aria-required={input.required}><SelectValue placeholder={t("inputs.pickMember")} /></SelectTrigger>
                                <SelectContent>
                                    {!input.required ? <SelectItem value="none">{t("inputs.noValue")}</SelectItem> : null}
                                    {members.map((member) => <SelectItem key={member.id} value={String(member.id)}>{member.name}</SelectItem>)}
                                </SelectContent>
                            </Select>
                        ) : (
                            <Input
                                id={id}
                                type={input.type === "date" ? "date" : "text"}
                                value={typeof value === "string" ? value : ""}
                                required={input.required}
                                maxLength={input.type === "text" ? 2_000 : undefined}
                                onChange={(event) => onChange(input.key, event.target.value || null)}
                                disabled={disabled}
                            />
                        )}
                    </div>
                );
            })}
        </section>
    );
}
