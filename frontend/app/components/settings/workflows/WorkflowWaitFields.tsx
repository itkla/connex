"use client";

import { useTranslations } from "next-intl";

import type { WorkflowFieldProps } from "@/app/components/settings/workflows/workflowDiagnosticFields";
import { useWorkflowValueOptions } from "@/app/components/settings/workflows/WorkflowValuePicker";
import type { WorkflowCatalog, WorkflowDefinition, WorkflowWaitNode } from "@/app/lib/types";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";

/** Waits only for the task produced by a preceding action on every path to this step. */
export default function WorkflowWaitFields({ node, definition, catalog, recordType, disabled, fieldProps, onChange, onCommit }: {
    node: WorkflowWaitNode;
    definition: WorkflowDefinition;
    catalog: WorkflowCatalog | null;
    recordType: string;
    disabled: boolean;
    fieldProps: WorkflowFieldProps;
    onChange: (node: WorkflowWaitNode, mode: "transient" | "commit") => void;
    onCommit: () => void;
}) {
    const t = useTranslations("WorkspaceWorkflows");
    const values = useWorkflowValueOptions(definition, catalog, recordType, node.id)
        .filter((option) => option.ref.source === "step_output" && option.ref.output === "taskId");
    return (
        <div className="space-y-4">
            <div className="space-y-2">
                <Label htmlFor="workflow-wait-source">{t("wait.source")}</Label>
                <Select value={node.config.source.nodeId} disabled={disabled || values.length === 0} onValueChange={(nodeId) => onChange({ ...node, config: { ...node.config, source: { nodeId, output: "taskId" } } }, "commit")}>
                    <SelectTrigger id="workflow-wait-source" {...fieldProps("config.source")} className="w-full"><SelectValue placeholder={t("wait.pickTask")} /></SelectTrigger>
                    <SelectContent>{values.map((option) => option.ref.source === "step_output" ? <SelectItem key={option.ref.nodeId} value={option.ref.nodeId}>{option.label}</SelectItem> : null)}</SelectContent>
                </Select>
                {values.length === 0 ? <p className="text-sm text-muted-foreground">{t("wait.noTask")}</p> : null}
            </div>
            <div className="space-y-2">
                <Label htmlFor="workflow-wait-timeout">{t("wait.timeoutMinutes")}</Label>
                <Input id="workflow-wait-timeout" {...fieldProps("config.timeoutSeconds")} type="number" min={1} max={43200} value={node.config.timeoutSeconds / 60} disabled={disabled}
                    onChange={(event) => onChange({ ...node, config: { ...node.config, timeoutSeconds: Math.round(Number(event.target.value) * 60) } }, "transient")} onBlur={onCommit} />
            </div>
            <p className="text-sm text-muted-foreground">{t("wait.branchesHelp")}</p>
        </div>
    );
}
