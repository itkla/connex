"use client";

import { useState } from "react";
import { useTranslations } from "next-intl";
import { EllipsisHorizontalIcon, PlusIcon } from "@heroicons/react/24/outline";

import {
    Dialog,
    DialogClose,
    DialogContent,
    DialogDescription,
    DialogFooter,
    DialogHeader,
    DialogTitle,
} from "@/components/ui/dialog";
import {
    DropdownMenu,
    DropdownMenuContent,
    DropdownMenuItem,
    DropdownMenuSeparator,
    DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { cn } from "@/lib/utils";
import { ApiError, createSavedView, deleteSavedView, updateSavedView } from "@/app/lib/api";
import { toastError, toastSuccess } from "@/app/lib/toast";
import type { SavedView, SavedViewConfig, SavedViewRecordType } from "@/app/lib/types";

/** Canonical string for a config, so two configs compare equal regardless of key/value order. */
function canonical(config: SavedViewConfig | null | undefined): string {
    const filters = config?.filters ?? {};
    const sorted: Record<string, string[]> = {};
    for (const key of Object.keys(filters).sort()) {
        const values = filters[key];
        if (values && values.length > 0) sorted[key] = [...values].sort();
    }
    const segments = (config?.segments ?? [])
        .map((segment) => `${segment.key}:${segment.days ?? ""}`)
        .sort();
    return JSON.stringify({
        filters: sorted,
        query: (config?.query ?? "").trim(),
        sortKey: config?.sortKey ?? null,
        sortDirection: config?.sortDirection ?? "asc",
        segments,
    });
}

/** A config is "empty" (= the All view) when it carries no filters, no search, and no sort. */
function isEmpty(config: SavedViewConfig | null | undefined): boolean {
    const filters = config?.filters ?? {};
    const hasFilters = Object.values(filters).some((values) => values && values.length > 0);
    return !hasFilters && !(config?.query ?? "").trim() && !config?.sortKey && (config?.segments?.length ?? 0) === 0;
}

/**
 * The Views tab bar for a records list: an "All" tab plus the user's saved views, with
 * apply, save (new / update), rename, and delete. A view bundles the current filters,
 * search, and sort; applying one drives the browser via {@code onApply}. The highlighted
 * tab is whichever view the current config matches (so it survives a reload), and the
 * explicitly-applied view stays the edit target while it is being modified.
 */
export default function SavedViewsBar({
    recordType,
    initialViews,
    currentConfig,
    onApply,
}: {
    recordType: SavedViewRecordType;
    initialViews: SavedView[];
    currentConfig: SavedViewConfig;
    onApply: (config: SavedViewConfig) => void;
}) {
    const t = useTranslations("SavedViews");
    const [views, setViews] = useState(initialViews);
    const [activeId, setActiveId] = useState<number | null>(null);
    const [dialog, setDialog] = useState<{ mode: "create" | "rename"; view?: SavedView } | null>(null);

    const currentKey = canonical(currentConfig);
    const explicitView = activeId !== null ? (views.find((view) => view.id === activeId) ?? null) : null;
    const matchedView = views.find((view) => canonical(view.config) === currentKey) ?? null;
    const activeView = explicitView ?? matchedView;
    const modified = explicitView != null && canonical(explicitView.config) !== currentKey;
    const canSaveNew = !matchedView && !isEmpty(currentConfig);

    const applyView = (view: SavedView | null) => {
        setActiveId(view?.id ?? null);
        onApply(view ? view.config : { filters: {}, query: "", sortKey: null, sortDirection: "asc", segments: [] });
    };

    const saveCurrent = async () => {
        if (!explicitView) return;
        try {
            const saved = await updateSavedView(explicitView.id, { recordType, name: explicitView.name, config: currentConfig });
            setViews((prev) => prev.map((view) => (view.id === saved.id ? saved : view)));
            toastSuccess(t("saved"));
        } catch (err) {
            toastError(err instanceof ApiError ? err.message : t("saveFailed"));
        }
    };

    const remove = async (view: SavedView) => {
        try {
            await deleteSavedView(view.id);
            setViews((prev) => prev.filter((other) => other.id !== view.id));
            if (activeId === view.id) setActiveId(null);
            toastSuccess(t("deleted"));
        } catch (err) {
            toastError(err instanceof ApiError ? err.message : t("deleteFailed"));
        }
    };

    const submitDialog = async (name: string) => {
        try {
            if (dialog?.mode === "rename" && dialog.view) {
                const saved = await updateSavedView(dialog.view.id, { recordType, name, config: dialog.view.config });
                setViews((prev) => prev.map((view) => (view.id === saved.id ? saved : view)));
            } else {
                const created = await createSavedView({ recordType, name, config: currentConfig, position: views.length });
                setViews((prev) => [...prev, created]);
                setActiveId(created.id);
            }
            setDialog(null);
        } catch (err) {
            toastError(err instanceof ApiError ? err.message : t("saveFailed"));
        }
    };

    return (
        <div className="flex items-center gap-1 overflow-x-auto">
            <ViewTab label={t("all")} active={activeView === null} onClick={() => applyView(null)} />
            {views.map((view) => (
                <ViewTab
                    key={view.id}
                    label={view.name}
                    active={view === activeView}
                    dirty={view === explicitView && modified}
                    dirtyLabel={t("modified")}
                    onClick={() => applyView(view)}
                />
            ))}

            {explicitView && modified ? (
                <DropdownMenu>
                    <DropdownMenuTrigger asChild>
                        <Button size="xs" variant="ghost" className="ml-1 text-brand hover:text-brand-hover">
                            {t("save")}
                        </Button>
                    </DropdownMenuTrigger>
                    <DropdownMenuContent align="start">
                        <DropdownMenuItem onSelect={saveCurrent}>{t("updateView", { name: explicitView.name })}</DropdownMenuItem>
                        <DropdownMenuItem onSelect={() => setDialog({ mode: "create" })}>{t("saveAsNew")}</DropdownMenuItem>
                    </DropdownMenuContent>
                </DropdownMenu>
            ) : (
                canSaveNew && (
                    <Button
                        size="xs"
                        variant="ghost"
                        className="ml-1 gap-1 text-brand hover:text-brand-hover"
                        onClick={() => setDialog({ mode: "create" })}
                    >
                        <PlusIcon className="size-3.5" />
                        {t("saveView")}
                    </Button>
                )
            )}

            {activeView && (
                <DropdownMenu>
                    <DropdownMenuTrigger asChild>
                        <Button size="icon-xs" variant="ghost" aria-label={t("viewActions")}>
                            <EllipsisHorizontalIcon className="size-4" />
                        </Button>
                    </DropdownMenuTrigger>
                    <DropdownMenuContent align="end">
                        <DropdownMenuItem onSelect={() => setDialog({ mode: "rename", view: activeView })}>{t("rename")}</DropdownMenuItem>
                        <DropdownMenuSeparator />
                        <DropdownMenuItem variant="destructive" onSelect={() => remove(activeView)}>{t("delete")}</DropdownMenuItem>
                    </DropdownMenuContent>
                </DropdownMenu>
            )}

            <Dialog open={dialog !== null} onOpenChange={(open) => { if (!open) setDialog(null); }}>
                <DialogContent className="sm:max-w-sm">
                    {dialog !== null && (
                        <NameForm mode={dialog.mode} initialName={dialog.view?.name ?? ""} onSubmit={submitDialog} />
                    )}
                </DialogContent>
            </Dialog>
        </div>
    );
}

function ViewTab({
    label,
    active,
    dirty,
    dirtyLabel,
    onClick,
}: {
    label: string;
    active: boolean;
    dirty?: boolean;
    dirtyLabel?: string;
    onClick: () => void;
}) {
    return (
        <button
            type="button"
            onClick={onClick}
            aria-current={active ? "true" : undefined}
            className={cn(
                "whitespace-nowrap rounded-md px-3 py-1.5 text-sm font-medium transition-colors",
                active ? "bg-muted text-foreground" : "text-muted-foreground hover:bg-muted/60 hover:text-foreground",
            )}
        >
            {label}
            {dirty && (
                <>
                    <span className="ml-1.5 inline-block size-1.5 rounded-full bg-brand align-middle" aria-hidden="true" />
                    <span className="sr-only">{dirtyLabel}</span>
                </>
            )}
        </button>
    );
}

function NameForm({
    mode,
    initialName,
    onSubmit,
}: {
    mode: "create" | "rename";
    initialName: string;
    onSubmit: (name: string) => Promise<void>;
}) {
    const t = useTranslations("SavedViews");
    const [name, setName] = useState(initialName);
    const [saving, setSaving] = useState(false);

    const handleSubmit = async (e: React.SubmitEvent<HTMLFormElement>) => {
        e.preventDefault();
        if (!name.trim()) return;
        setSaving(true);
        await onSubmit(name.trim());
        setSaving(false);
    };

    return (
        <form onSubmit={handleSubmit}>
            <DialogHeader>
                <DialogTitle>{mode === "rename" ? t("renameTitle") : t("saveViewTitle")}</DialogTitle>
                <DialogDescription>{t("nameDescription")}</DialogDescription>
            </DialogHeader>
            <div className="grid gap-1.5 py-4">
                <Label htmlFor="saved-view-name">{t("nameLabel")}</Label>
                <Input
                    id="saved-view-name"
                    value={name}
                    onChange={(e) => setName(e.target.value)}
                    maxLength={128}
                    autoFocus
                    placeholder={t("namePlaceholder")}
                />
            </div>
            <DialogFooter>
                <DialogClose asChild>
                    <Button type="button" variant="outline" disabled={saving}>
                        {t("cancel")}
                    </Button>
                </DialogClose>
                <Button type="submit" disabled={saving || !name.trim()} className="bg-brand text-white hover:bg-brand-hover">
                    {t("save")}
                </Button>
            </DialogFooter>
        </form>
    );
}
