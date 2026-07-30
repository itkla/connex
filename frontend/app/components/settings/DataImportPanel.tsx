"use client";

import { useState } from "react";
import { useTranslations } from "next-intl";
import { ArrowUpTrayIcon, ClockIcon } from "@heroicons/react/24/outline";

import InteractionHistoryImportDialog from "@/app/components/import/InteractionHistoryImportDialog";
import { SettingsSection } from "@/app/components/settings/SettingsSection";
import { Button } from "@/components/ui/button";

export default function DataImportPanel() {
    const t = useTranslations("WorkspaceData");
    const [open, setOpen] = useState(false);

    return (
        <SettingsSection title={t("title")} description={t("subtitle")}>
            <div className="flex flex-col gap-4 rounded-2xl border border-border bg-card p-4 sm:flex-row sm:items-center sm:justify-between sm:p-5">
                <div className="flex min-w-0 items-start gap-3">
                    <span className="flex size-10 shrink-0 items-center justify-center rounded-xl bg-muted text-muted-foreground">
                        <ClockIcon className="size-5" />
                    </span>
                    <div className="min-w-0 space-y-1">
                        <h3 className="text-sm font-semibold text-foreground">{t("historyTitle")}</h3>
                        <p className="max-w-2xl text-sm text-muted-foreground">{t("historyDescription")}</p>
                    </div>
                </div>
                <Button className="shrink-0" onClick={() => setOpen(true)}>
                    <ArrowUpTrayIcon className="size-4" />
                    {t("historyAction")}
                </Button>
            </div>
            <InteractionHistoryImportDialog open={open} onOpenChange={setOpen} />
        </SettingsSection>
    );
}
