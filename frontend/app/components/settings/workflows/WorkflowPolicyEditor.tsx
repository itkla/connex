"use client";

import { useTranslations } from "next-intl";

import type { WorkflowFieldProps } from "@/app/components/settings/workflows/workflowDiagnosticFields";
import SegmentBuilder from "@/app/components/records/SegmentBuilder";
import { isScheduleEnrollmentNode } from "@/app/components/settings/workflows/workflowGraph";
import type { RuleBuilderOptions, SegmentFields, WorkflowDefinition } from "@/app/lib/types";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Switch } from "@/components/ui/switch";

export type WorkflowPolicies = Pick<WorkflowDefinition, "enrollment" | "stopConditions">;

/** Edits start, repeat, and stop policies using the same record condition builder as graph branches. */
export default function WorkflowPolicyEditor({ definition, recordType, fields, options, disabled, fieldProps, onChange, onCommit }: {
    definition: WorkflowDefinition;
    recordType: string;
    fields: SegmentFields | null;
    options: RuleBuilderOptions | null;
    disabled: boolean;
    fieldProps: WorkflowFieldProps;
    onChange: (policies: WorkflowPolicies, mode: "transient" | "commit") => void;
    onCommit: () => void;
}) {
    const t = useTranslations("WorkspaceWorkflows");
    const enrollment = definition.enrollment ?? { oneActiveRun: false, cooldownMinutes: 0 };
    const compatibilityEnrollment = definition.nodes.some((node) => isScheduleEnrollmentNode(definition, node.id));
    const update = (value: WorkflowPolicies, mode: "transient" | "commit" = "commit") => onChange({ enrollment: definition.enrollment, stopConditions: definition.stopConditions, ...value }, mode);
    return (
        <section className="space-y-4 border-t border-border pt-4" inert={disabled || undefined}>
            <div>
                <h3 className="text-sm font-semibold text-foreground">{t("policies.title")}</h3>
                <p className="mt-1 text-sm text-muted-foreground">{t("policies.help")}</p>
            </div>
            <div className="flex items-center justify-between gap-3">
                <Label htmlFor="workflow-one-active">{t("policies.oneActive")}</Label>
                <Switch id="workflow-one-active" {...fieldProps("enrollment.oneActiveRun")} checked={enrollment.oneActiveRun} onCheckedChange={(oneActiveRun) => update({ enrollment: { ...enrollment, oneActiveRun } })} />
            </div>
            <div className="space-y-2">
                <Label htmlFor="workflow-cooldown">{t("policies.cooldown")}</Label>
                <Input id="workflow-cooldown" {...fieldProps("enrollment.cooldownMinutes")} type="number" min={0} max={525600} step={1} value={enrollment.cooldownMinutes}
                    onChange={(event) => update({ enrollment: { ...enrollment, cooldownMinutes: Number(event.target.value) } }, "transient")} onBlur={onCommit} />
                <p className="text-sm text-muted-foreground">{t("policies.cooldownHelp")}</p>
            </div>
            {compatibilityEnrollment ? <p className="text-sm text-muted-foreground">{t("policies.compatibilityEnrollment")}</p> : (
                <div className="space-y-3" {...fieldProps("enrollment.condition")} tabIndex={-1}>
                    <div className="flex items-center justify-between gap-3">
                        <Label htmlFor="workflow-entry-condition">{t("policies.entry")}</Label>
                        <Switch id="workflow-entry-condition" checked={enrollment.condition !== undefined}
                            onCheckedChange={(checked) => update({ enrollment: { ...enrollment, condition: checked ? { match: "all", conditions: [] } : undefined } })} />
                    </div>
                    {enrollment.condition ? <SegmentBuilder definition={enrollment.condition} fields={fields} options={options} recordType={recordType} advanced
                        onChange={(condition) => update({ enrollment: { ...enrollment, condition } })}
                        onTransientChange={(condition) => update({ enrollment: { ...enrollment, condition } }, "transient")} onCommitTransient={onCommit} /> : null}
                </div>
            )}
            <div className="space-y-3" {...fieldProps("stopConditions")} tabIndex={-1}>
                <div className="flex items-center justify-between gap-3">
                    <Label htmlFor="workflow-stop-condition">{t("policies.stop")}</Label>
                    <Switch id="workflow-stop-condition" checked={definition.stopConditions !== undefined}
                        onCheckedChange={(checked) => update({ stopConditions: checked ? { match: "all", conditions: [] } : undefined })} />
                </div>
                <p className="text-sm text-muted-foreground">{t("policies.stopHelp")}</p>
                {definition.stopConditions ? <>
                    {definition.stopConditions.conditions.length === 0 && !definition.stopConditions.groups?.length && !definition.stopConditions.negate
                        ? <p role="alert" className="text-sm text-destructive">{t("policies.stopAllWarning")}</p> : null}
                    <SegmentBuilder definition={definition.stopConditions} fields={fields} options={options} recordType={recordType} advanced
                        onChange={(stopConditions) => update({ stopConditions })}
                        onTransientChange={(stopConditions) => update({ stopConditions }, "transient")} onCommitTransient={onCommit} />
                </> : null}
            </div>
        </section>
    );
}
