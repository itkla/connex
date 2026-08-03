"use client";

import { useLocale, useTranslations } from "next-intl";
import { RectangleStackIcon } from "@heroicons/react/24/outline";

import type { WorkflowVersion } from "@/app/lib/types";
import { formatWorkflowRunDateTime, normalizeWorkflowRunDateTime } from "@/app/components/settings/workflows/workflowRunStatus";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
    Dialog,
    DialogClose,
    DialogContent,
    DialogDescription,
    DialogFooter,
    DialogHeader,
    DialogTitle,
} from "@/components/ui/dialog";
import { Skeleton } from "@/components/ui/skeleton";

/** Immutable workflow version history with read-only editor inspection. */
export default function WorkflowVersionsDialog({
    open,
    versions,
    activeVersionId,
    loading,
    onOpenChange,
    onInspect,
}: {
    open: boolean;
    versions: WorkflowVersion[];
    activeVersionId: number | null;
    loading: boolean;
    onOpenChange: (open: boolean) => void;
    onInspect: (version: WorkflowVersion) => void;
}) {
    const t = useTranslations("WorkspaceWorkflows");
    const locale = useLocale();
    return (
        <Dialog open={open} onOpenChange={onOpenChange}>
            <DialogContent size="lg" showCloseButton={false} className="max-h-[80dvh] grid-rows-[auto_minmax(0,1fr)_auto] gap-0 overflow-hidden p-0">
                <DialogHeader className="border-b border-border px-6 py-5">
                    <DialogTitle>{t("versions.title")}</DialogTitle>
                    <DialogDescription>{t("versions.description")}</DialogDescription>
                </DialogHeader>
                <div className="min-h-0 overflow-y-auto">
                    {loading ? (
                        <div className="space-y-3 p-6">
                            {Array.from({ length: 3 }, (_, index) => <Skeleton key={index} className="h-20 w-full rounded-xl" />)}
                        </div>
                    ) : versions.length === 0 ? (
                        <div className="grid min-h-48 place-items-center px-6 text-center text-sm text-muted-foreground">
                            <div>
                                <RectangleStackIcon className="mx-auto size-8" />
                                <p className="mt-2">{t("versions.empty")}</p>
                            </div>
                        </div>
                    ) : (
                        <ol className="divide-y divide-border">
                            {versions.map((version) => (
                                <li key={version.id} className="flex flex-wrap items-center gap-3 px-6 py-4">
                                    <div className="min-w-0 flex-1">
                                        <div className="flex flex-wrap items-center gap-2">
                                            <span className="text-sm font-medium text-foreground">{t("versionShort", { number: version.versionNumber })}</span>
                                            {version.id === activeVersionId ? <Badge variant="secondary">{t("versions.active")}</Badge> : null}
                                        </div>
                                        <time
                                            className="mt-1 block text-xs text-muted-foreground"
                                            dateTime={normalizeWorkflowRunDateTime(version.publishedAt)}
                                        >
                                            {formatWorkflowRunDateTime(version.publishedAt, locale)}
                                        </time>
                                    </div>
                                    <Button variant="outline" size="sm" onClick={() => onInspect(version)}>{t("versions.inspect")}</Button>
                                </li>
                            ))}
                        </ol>
                    )}
                </div>
                <DialogFooter className="border-t border-border px-6 py-4">
                    <DialogClose asChild><Button variant="outline">{t("close")}</Button></DialogClose>
                </DialogFooter>
            </DialogContent>
        </Dialog>
    );
}
