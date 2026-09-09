"use client";

import { useMemo } from "react";
import { useTranslations } from "next-intl";

import type { WorkflowFieldProps } from "@/app/components/settings/workflows/workflowDiagnosticFields";
import type { RuleTrigger } from "@/app/lib/types";
import { Combobox, ComboboxContent, ComboboxEmpty, ComboboxInput, ComboboxItem, ComboboxList } from "@/components/ui/combobox";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";

/** Schedules one deal process around its native expected close date in an explicit time zone. */
export default function WorkflowDateTriggerFields({ trigger, disabled, fieldProps, onChange, onCommit }: {
    trigger: RuleTrigger;
    disabled: boolean;
    fieldProps: WorkflowFieldProps;
    onChange: (trigger: RuleTrigger, mode: "transient" | "commit") => void;
    onCommit: () => void;
}) {
    const t = useTranslations("WorkspaceWorkflows");
    const timezones = useMemo(() => {
        let supported: string[] = [];
        try { supported = Intl.supportedValuesOf("timeZone"); } catch { supported = []; }
        return Array.from(new Set(["UTC", ...(trigger.timezone ? [trigger.timezone] : []), ...supported]));
    }, [trigger.timezone]);
    return (
        <div className="space-y-4">
            <p className="text-sm text-muted-foreground">{t("date.fieldHelp")}</p>
            <div className="space-y-2">
                <Label htmlFor="workflow-date-offset">{t("date.offset")}</Label>
                <Input id="workflow-date-offset" {...fieldProps("config.offsetDays")} type="number" min={-365} max={365} value={trigger.offsetDays ?? -30} disabled={disabled}
                    onChange={(event) => onChange({ ...trigger, offsetDays: Number(event.target.value) }, "transient")} onBlur={onCommit} />
                <p className="text-sm text-muted-foreground">{t("date.offsetHelp")}</p>
            </div>
            <div className="space-y-2">
                <Label htmlFor="workflow-date-time">{t("date.localTime")}</Label>
                <Input id="workflow-date-time" {...fieldProps("config.localTime")} type="time" value={trigger.localTime ?? "09:00"} disabled={disabled}
                    onChange={(event) => onChange({ ...trigger, localTime: event.target.value }, "transient")} onBlur={onCommit} />
            </div>
            <div className="space-y-2">
                <Label htmlFor="workflow-date-timezone">{t("date.timezone")}</Label>
                <Combobox items={timezones} value={trigger.timezone ?? "UTC"} disabled={disabled} onValueChange={(timezone) => {
                    if (timezone) onChange({ ...trigger, timezone }, "commit");
                }}>
                    <ComboboxInput id="workflow-date-timezone" {...fieldProps("config.timezone")} />
                    <ComboboxContent>
                        <ComboboxEmpty>{t("date.timezoneEmpty")}</ComboboxEmpty>
                        <ComboboxList>{(timezone: string) => <ComboboxItem key={timezone} value={timezone}>{timezone}</ComboboxItem>}</ComboboxList>
                    </ComboboxContent>
                </Combobox>
            </div>
            <p className="text-sm text-muted-foreground">{t("date.catchupHelp")}</p>
        </div>
    );
}
