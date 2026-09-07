"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { useTranslations } from "next-intl";
import { ArrowRightIcon, CalendarDaysIcon, BoltIcon, CheckCircleIcon, ClockIcon, CursorArrowRaysIcon } from "@heroicons/react/24/outline";

import ConfirmDiscardDialog from "@/app/components/ConfirmDiscardDialog";
import { PageHeader } from "@/app/components/PageHeader";
import { PageShell } from "@/app/components/PageShell";
import { useUnsavedChangesGuard } from "@/app/hooks/useUnsavedChangesGuard";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Textarea } from "@/components/ui/textarea";

export type WorkflowSetupValue = {
    name: string;
    purpose: string;
    recordType: "person" | "company" | "deal" | "task" | "document";
    start: "manual" | "entity_change" | "schedule" | "date";
};

const STARTS = [
    { value: "manual", icon: CursorArrowRaysIcon },
    { value: "entity_change", icon: BoltIcon },
    { value: "schedule", icon: ClockIcon },
    { value: "date", icon: CalendarDaysIcon },
] as const;

/** Defines a new workflow's purpose and primary context before opening its shared graph editor. */
export default function WorkflowSetup({
    initialRecordType = "person",
    initialStart = "manual",
    supportsDateStart = false,
    onContinue,
}: {
    initialRecordType?: WorkflowSetupValue["recordType"];
    supportsDateStart?: boolean;
    initialStart?: WorkflowSetupValue["start"];
    onContinue: (value: WorkflowSetupValue) => void;
}) {
    const t = useTranslations("WorkspaceWorkflows");
    const tr = useTranslations("WorkflowAuthoring");
    const router = useRouter();
    const startingKind = initialRecordType === "task" || initialRecordType === "document" ? "entity_change" : initialStart === "date" && (!supportsDateStart || initialRecordType !== "deal") ? "manual" : initialStart;
    const [value, setValue] = useState<WorkflowSetupValue>({
        name: "", purpose: "", recordType: initialRecordType, start: startingKind,
    });
    const eventOnly = value.recordType === "task" || value.recordType === "document";
    const [destination, setDestination] = useState("/workflows");
    const dirty = value.name.trim().length > 0 || value.purpose.trim().length > 0
        || value.recordType !== initialRecordType || value.start !== startingKind;
    const guard = useUnsavedChangesGuard({
        isDirty: dirty,
        onClose: () => router.push(destination),
    });
    const navigate = (path: string) => {
        setDestination(path);
        if (dirty) guard.requestClose();
        else router.push(path);
    };

    return (
        <PageShell>
            <PageHeader
                title={t("setup.title")}
                description={t("setup.description")}
                actions={<Button variant="outline" onClick={() => navigate("/workflows/recipes")}>{t("setup.browseRecipes")}</Button>}
            />
            <form
                className="grid gap-8 lg:grid-cols-[minmax(0,1fr)_minmax(0,1fr)]"
                onSubmit={(event) => {
                    event.preventDefault();
                    if (value.name.trim()) onContinue({ ...value, name: value.name.trim(), purpose: value.purpose.trim() });
                }}
            >
                <div className="space-y-6">
                    <fieldset className="space-y-3">
                        <legend className="text-base font-semibold text-foreground">{t("setup.startQuestion")}</legend>
                        <div className="flex flex-col gap-2">
                            {STARTS.filter((start) => (!eventOnly || start.value === "entity_change") && (start.value !== "date" || value.recordType === "deal" && supportsDateStart)).map(({ value: start, icon: Icon }) => (
                                <div key={start} className="space-y-1.5">
                                    <Button
                                        type="button"
                                        variant={value.start === start ? "secondary" : "outline"}
                                        aria-pressed={value.start === start}
                                        size="page"
                                        className="w-full justify-start gap-3"
                                        aria-describedby={`workflow-start-${start}-help`}
                                        onClick={() => setValue((current) => ({ ...current, start }))}
                                    >
                                        <Icon aria-hidden className="size-5 shrink-0" />
                                        <span className="flex-1">
                                            <span className="block text-sm font-semibold">{t(`setup.start.${start}.title`)}</span>
                                        </span>
                                        {value.start === start ? <CheckCircleIcon aria-hidden className="size-5 shrink-0" /> : null}
                                    </Button>
                                    <p id={`workflow-start-${start}-help`} className="px-3 text-sm text-muted-foreground">{t(`setup.start.${start}.body`)}</p>
                                </div>
                            ))}
                        </div>
                    </fieldset>
                    <div className="space-y-2">
                        <Label htmlFor="workflow-setup-record">{t("setup.recordQuestion")}</Label>
                        <Select
                            value={value.recordType}
                            onValueChange={(recordType) => {
                                if (recordType === "person" || recordType === "company" || recordType === "deal" || recordType === "task" || recordType === "document") {
                                    setValue((current) => ({ ...current, recordType, start: recordType === "task" || recordType === "document" || current.start === "date" && recordType !== "deal" ? "entity_change" : current.start }));
                                }
                            }}
                        >
                            <SelectTrigger id="workflow-setup-record" className="w-full"><SelectValue /></SelectTrigger>
                            <SelectContent>
                                {["person", "company", "deal", "task", "document"].map((recordType) => <SelectItem key={recordType} value={recordType}>{tr(`record.${recordType}`)}</SelectItem>)}
                            </SelectContent>
                        </Select>
                        <p className="text-sm text-muted-foreground">{t(eventOnly ? "setup.eventOnlyHelp" : "setup.recordHelp")}</p>
                    </div>
                </div>
                <div className="space-y-6">
                    <div className="space-y-2">
                        <Label htmlFor="workflow-setup-name">{t("nameLabel")}</Label>
                        <Input
                            id="workflow-setup-name"
                            required
                            maxLength={128}
                            value={value.name}
                            placeholder={t("setup.namePlaceholder")}
                            onChange={(event) => setValue((current) => ({ ...current, name: event.target.value }))}
                        />
                    </div>
                    <div className="space-y-2">
                        <Label htmlFor="workflow-setup-purpose">{t("setup.purposeLabel")}</Label>
                        <Textarea
                            id="workflow-setup-purpose"
                            maxLength={512}
                            value={value.purpose}
                            placeholder={t("setup.purposePlaceholder")}
                            onChange={(event) => setValue((current) => ({ ...current, purpose: event.target.value }))}
                        />
                        <p className="text-sm text-muted-foreground">{t("setup.purposeHelp")}</p>
                    </div>
                    <div className="border-t border-border pt-5">
                        <h2 className="text-base font-semibold text-foreground">{t("setup.nextTitle")}</h2>
                        <p className="mt-2 text-sm text-muted-foreground">{t(eventOnly ? "setup.nextBodyLegacy" : "setup.nextBody")}</p>
                    </div>
                </div>
                <div className="flex flex-wrap justify-between gap-3 border-t border-border pt-5 lg:col-span-2">
                    <Button type="button" variant="outline" onClick={() => navigate("/workflows")}>{t("backToList")}</Button>
                    <Button type="submit" variant="brand" disabled={!value.name.trim()}>{t("setup.continue")}<ArrowRightIcon aria-hidden className="size-4" /></Button>
                </div>
            </form>
            <ConfirmDiscardDialog {...guard.confirm} />
        </PageShell>
    );
}
