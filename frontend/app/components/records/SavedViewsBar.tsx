"use client";

import { useEffect, useRef, useState } from "react";
import { usePathname, useSearchParams } from "next/navigation";
import { useTranslations } from "next-intl";
import {
    BookmarkIcon,
    BookmarkSlashIcon,
    EllipsisHorizontalIcon,
    ExclamationTriangleIcon,
    LinkIcon,
    LockClosedIcon,
    PencilIcon,
    PlusIcon,
    StarIcon,
    TrashIcon,
    UserGroupIcon,
    UsersIcon,
} from "@heroicons/react/24/outline";

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
    DropdownMenuLabel,
    DropdownMenuSeparator,
    DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { cn } from "@/lib/utils";
import {
    ApiError,
    clearDefaultSavedView,
    createSavedView,
    deleteSavedView,
    getSavedView,
    isFieldError,
    pinSavedView,
    setDefaultSavedView,
    unpinSavedView,
    updateSavedView,
} from "@/app/lib/api";
import {
    evaluableSegmentDefinition,
    hasSegmentConditions,
    normalizeSegmentDefinition,
} from "@/app/lib/segmentDefinition";
import { toastError, toastSuccess } from "@/app/lib/toast";
import { publishSavedViewMutation } from "@/app/lib/saved-view-events";
import { parseSavedViewToken, savedViewRecordPath, savedViewToken } from "@/app/lib/savedViewLink";
import { writeSavedViewToUrl, writeWorkspaceSavedViewToUrl } from "@/app/hooks/listStateUrl";
import { useWorkspace } from "@/app/hooks/useWorkspace";
import type { SavedView, SavedViewConfig, SavedViewRecordType } from "@/app/lib/types";

const EMPTY_CONFIG: SavedViewConfig = { filters: {}, query: "", sortKey: null, sortDirection: "asc" };

/** Canonical string for a config, so two configs compare equal regardless of key/value order. */
function canonical(config: SavedViewConfig | null | undefined): string {
    const filters = config?.filters ?? {};
    const sorted: Record<string, string[]> = {};
    for (const key of Object.keys(filters).sort()) {
        const values = filters[key];
        if (values && values.length > 0) sorted[key] = [...values].sort();
    }
    const normalizedSegments = normalizeSegmentDefinition(config?.segments);
    const evaluableSegments = normalizedSegments ? evaluableSegmentDefinition(normalizedSegments) : null;
    const segments = evaluableSegments && hasSegmentConditions(evaluableSegments)
        ? JSON.stringify(evaluableSegments)
        : "";
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
    const segments = normalizeSegmentDefinition(config?.segments);
    const hasSegments = segments ? hasSegmentConditions(evaluableSegmentDefinition(segments)) : false;
    return !hasFilters && !(config?.query ?? "").trim() && !config?.sortKey && !hasSegments;
}

/** Merges a view into a list, replacing any existing entry with the same id. */
function upsertView(views: SavedView[], view: SavedView): SavedView[] {
    return views.some((other) => other.id === view.id)
        ? views.map((other) => (other.id === view.id ? view : other))
        : [...views, view];
}

/**
 * The Views tab bar for a records list: an "All" tab plus the workspace's saved views, with apply, save
 * (new / update), rename, delete, pin, default, and — for owned views — visibility and copy-link. A view
 * bundles the current filters, search, and sort; applying one drives the browser via {@code onApply} and
 * publishes a shareable `?sv=<workspaceId>:<id>` pointer to the URL. On mount the bar honors a shared
 * `?sv` link (re-resolving the view authoritatively) or, on a clean load, the user's default view.
 */
export default function SavedViewsBar({
    recordType,
    initialViews,
    currentConfig,
    onApply,
    defaultView,
    unavailable = false,
}: {
    recordType: SavedViewRecordType;
    initialViews: SavedView[];
    currentConfig: SavedViewConfig;
    onApply: (config: SavedViewConfig) => void;
    defaultView?: SavedView | null;
    /**
     * Set when the saved-view list could not be loaded. The bar then says so instead of rendering its
     * tabs, because an unreadable saved-view list must not present as an empty one. It also suppresses
     * silent application of the default view: with the tab strip and its "All" control hidden, an
     * auto-applied default would filter the table with no visible way to see or clear it. An explicit
     * {@code ?sv=} deep link is still honoured, since that is the user's own request.
     */
    unavailable?: boolean;
}) {
    const t = useTranslations("SavedViews");
    const pathname = usePathname();
    const { activeWorkspaceId, workspaces, switching, runInWorkspace } = useWorkspace();
    const [views, setViews] = useState(initialViews);
    const [activeId, setActiveId] = useState<number | null>(null);
    const [dialog, setDialog] = useState<{ mode: "create" | "rename"; view?: SavedView } | null>(null);
    const [switchPrompt, setSwitchPrompt] = useState<{ workspaceId: number; sv: string } | null>(null);

    const currentKey = canonical(currentConfig);
    const explicitView = activeId !== null ? (views.find((view) => view.id === activeId) ?? null) : null;
    const matchedView = views.find((view) => canonical(view.config) === currentKey) ?? null;
    const activeView = explicitView ?? matchedView;
    const modified = explicitView != null && canonical(explicitView.config) !== currentKey;
    const canSaveNew = !matchedView && !isEmpty(currentConfig);

    const applyView = (view: SavedView | null) => {
        setActiveId(view?.id ?? null);
        onApply(view ? view.config : EMPTY_CONFIG);
        writeSavedViewToUrl(pathname, view ? savedViewToken(view) : null);
    };

    const initialRef = useRef({ onApply, defaultView, activeWorkspaceId, workspaces, runInWorkspace, pathname, unavailable });
    const resolvedRef = useRef(false);
    const lastNavigatedSvRef = useRef<string | null>(null);
    useEffect(() => {
        if (resolvedRef.current) return;
        resolvedRef.current = true;
        const snapshot = new URLSearchParams(window.location.search);
        const sv = snapshot.get("sv");
        lastNavigatedSvRef.current = sv;
        const { onApply: apply, defaultView: fallback, activeWorkspaceId: currentWorkspaceId, workspaces: available, pathname: path, unavailable: listUnavailable } = initialRef.current;

        const applyResolved = (view: SavedView) => {
            if (view.recordType !== recordType) {
                toastError(t("viewUnavailable"));
                clearAndFallBack();
                return;
            }
            setViews((prev) => upsertView(prev, view));
            setActiveId(view.id);
            apply(view.config);
            writeSavedViewToUrl(path, savedViewToken(view));
        };
        const applyDefault = () => {
            if (!fallback || listUnavailable) return;
            setViews((prev) => upsertView(prev, fallback));
            setActiveId(fallback.id);
            apply(fallback.config);
            writeSavedViewToUrl(path, savedViewToken(fallback));
        };
        const clearAndFallBack = () => {
            writeSavedViewToUrl(path, null);
            applyDefault();
        };

        if (sv) {
            const parsed = parseSavedViewToken(sv);
            if (!parsed) {
                clearAndFallBack();
                return;
            }
            if (parsed.workspaceId !== currentWorkspaceId) {
                if (available.some((workspace) => workspace.id === parsed.workspaceId)) {
                    setSwitchPrompt({ workspaceId: parsed.workspaceId, sv });
                } else {
                    toastError(t("viewUnavailable"));
                    clearAndFallBack();
                }
                return;
            }
            getSavedView(parsed.id)
                .then(applyResolved)
                .catch(() => {
                    toastError(t("viewUnavailable"));
                    clearAndFallBack();
                });
            return;
        }

        snapshot.delete("sv");
        if (Array.from(snapshot.keys()).length > 0) return;
        applyDefault();
    }, [t, recordType]);

    const searchParams = useSearchParams();
    const svParam = searchParams.get("sv");
    const activeIdRef = useRef(activeId);
    const onApplyRef = useRef(onApply);
    useEffect(() => {
        activeIdRef.current = activeId;
        onApplyRef.current = onApply;
    });

    useEffect(() => {
        if (!resolvedRef.current) return;
        if (svParam === lastNavigatedSvRef.current) return;
        lastNavigatedSvRef.current = svParam;
        if (!svParam) return;
        const parsed = parseSavedViewToken(svParam);
        if (!parsed || parsed.workspaceId !== activeWorkspaceId) return;
        if (parsed.id === activeIdRef.current) return;
        let cancelled = false;
        getSavedView(parsed.id)
            .then((view) => {
                if (cancelled) return;
                if (view.recordType !== recordType) {
                    toastError(t("viewUnavailable"));
                    writeSavedViewToUrl(pathname, null);
                    return;
                }
                setViews((prev) => upsertView(prev, view));
                setActiveId(view.id);
                onApplyRef.current(view.config);
                writeSavedViewToUrl(pathname, savedViewToken(view));
            })
            .catch(() => {
                if (cancelled) return;
                toastError(t("viewUnavailable"));
                writeSavedViewToUrl(pathname, null);
            });
        return () => {
            cancelled = true;
        };
    }, [svParam, activeWorkspaceId, recordType, pathname, t]);

    useEffect(() => {
        if (modified) writeSavedViewToUrl(pathname, null);
    }, [modified, pathname]);

    const confirmSwitchWorkspace = async () => {
        if (!switchPrompt || switching) return;
        const target = switchPrompt.workspaceId;
        const targetSv = switchPrompt.sv;
        try {
            const switched = await runInWorkspace(target, async () => {
                writeWorkspaceSavedViewToUrl(pathname, targetSv);
            });
            if (!switched) return;
            setSwitchPrompt(null);
            window.location.reload();
        } catch (err) {
            toastError(err instanceof ApiError ? err.message : t("actionFailed"));
        }
    };
    const declineSwitchWorkspace = () => {
        setSwitchPrompt(null);
        writeSavedViewToUrl(pathname, null);
        if (defaultView) applyView(defaultView);
    };

    const saveCurrent = async () => {
        if (!explicitView) return;
        const config: SavedViewConfig = {
            ...currentConfig,
            visibleColumns: explicitView.config.visibleColumns,
            columnOrder: explicitView.config.columnOrder,
            pageSize: explicitView.config.pageSize,
        };
        try {
            const saved = await updateSavedView(explicitView.id, {
                recordType,
                name: explicitView.name,
                visibility: explicitView.visibility,
                config,
            });
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
            if (activeId === view.id) applyView(null);
            if (view.pinned) publishSavedViewMutation(recordType);
            toastSuccess(t("deleted"));
        } catch (err) {
            toastError(err instanceof ApiError ? err.message : t("deleteFailed"));
        }
    };

    const togglePin = async (view: SavedView) => {
        try {
            if (view.pinned) await unpinSavedView(view.id);
            else await pinSavedView(view.id);
            setViews((prev) => prev.map((other) => (other.id === view.id ? { ...other, pinned: !view.pinned } : other)));
            publishSavedViewMutation(recordType);
            toastSuccess(view.pinned ? t("unpinned") : t("pinned"));
        } catch (err) {
            toastError(err instanceof ApiError ? err.message : t("actionFailed"));
        }
    };

    const toggleDefault = async (view: SavedView) => {
        try {
            if (view.default) await clearDefaultSavedView(recordType);
            else await setDefaultSavedView(recordType, view.id);
            const nextDefault = !view.default;
            setViews((prev) => prev.map((other) => ({
                ...other,
                default: other.id === view.id ? nextDefault : false,
            })));
            toastSuccess(nextDefault ? t("defaultSet") : t("defaultCleared"));
        } catch (err) {
            toastError(err instanceof ApiError ? err.message : t("actionFailed"));
        }
    };

    const toggleVisibility = async (view: SavedView) => {
        const nextVisibility = view.visibility === "workspace" ? "private" : "workspace";
        try {
            const saved = await updateSavedView(view.id, {
                recordType,
                name: view.name,
                visibility: nextVisibility,
                config: view.config,
            });
            setViews((prev) => prev.map((other) => (other.id === saved.id ? saved : other)));
            toastSuccess(nextVisibility === "workspace" ? t("madeShared") : t("madePrivate"));
        } catch (err) {
            toastError(err instanceof ApiError ? err.message : t("actionFailed"));
        }
    };

    const copyLink = async (view: SavedView) => {
        const url = `${window.location.origin}/records/${savedViewRecordPath(view.recordType)}?sv=${savedViewToken(view)}`;
        try {
            await navigator.clipboard.writeText(url);
            toastSuccess(t("linkCopied"));
        } catch {
            toastError(t("copyLinkFailed"));
        }
    };

    const submitDialog = async (name: string): Promise<string | null> => {
        try {
            if (dialog?.mode === "rename" && dialog.view) {
                const saved = await updateSavedView(dialog.view.id, {
                    recordType,
                    name,
                    visibility: dialog.view.visibility,
                    config: dialog.view.config,
                });
                setViews((prev) => prev.map((view) => (view.id === saved.id ? saved : view)));
            } else {
                const created = await createSavedView({ recordType, name, config: currentConfig, position: views.length });
                setViews((prev) => [...prev, created]);
                setActiveId(created.id);
                writeSavedViewToUrl(pathname, savedViewToken(created));
            }
            setDialog(null);
            return null;
        } catch (err) {
            if (isFieldError(err) && err.fieldErrors.name) return err.fieldErrors.name;
            toastError(err instanceof ApiError ? err.message : t("saveFailed"));
            return null;
        }
    };

    if (unavailable) {
        return (
            <div className="flex items-center gap-1.5 px-3 py-1.5 text-sm text-muted-foreground">
                <ExclamationTriangleIcon className="size-4 shrink-0" aria-hidden="true" />
                {t("loadFailed")}
            </div>
        );
    }

    return (
        <div className="flex items-center gap-1 overflow-x-auto">
            <ViewTab label={t("all")} active={activeView === null} onClick={() => applyView(null)} />
            {views.map((view) => (
                <ViewTab
                    key={view.id}
                    label={view.name}
                    active={view === activeView}
                    shared={!view.ownedByCurrentUser}
                    sharedLabel={t("shared")}
                    pinned={view.pinned}
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
                        {explicitView.ownedByCurrentUser && (
                            <DropdownMenuItem onSelect={saveCurrent}>{t("updateView", { name: explicitView.name })}</DropdownMenuItem>
                        )}
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
                        {!activeView.ownedByCurrentUser && (
                            <>
                                <DropdownMenuLabel>{t("shared")}</DropdownMenuLabel>
                                <DropdownMenuSeparator />
                            </>
                        )}
                        <DropdownMenuItem onSelect={() => togglePin(activeView)}>
                            {activeView.pinned ? <BookmarkSlashIcon /> : <BookmarkIcon />}
                            {activeView.pinned ? t("unpin") : t("pin")}
                        </DropdownMenuItem>
                        <DropdownMenuItem onSelect={() => toggleDefault(activeView)}>
                            <StarIcon />
                            {activeView.default ? t("clearDefault") : t("setDefault")}
                        </DropdownMenuItem>
                        <DropdownMenuItem onSelect={() => copyLink(activeView)}>
                            <LinkIcon />
                            {t("copyLink")}
                        </DropdownMenuItem>
                        {activeView.ownedByCurrentUser && (
                            <>
                                <DropdownMenuSeparator />
                                <DropdownMenuItem onSelect={() => setDialog({ mode: "rename", view: activeView })}>
                                    <PencilIcon />
                                    {t("rename")}
                                </DropdownMenuItem>
                                <DropdownMenuItem onSelect={() => toggleVisibility(activeView)}>
                                    {activeView.visibility === "workspace" ? <LockClosedIcon /> : <UserGroupIcon />}
                                    {activeView.visibility === "workspace" ? t("makePrivate") : t("shareWorkspace")}
                                </DropdownMenuItem>
                                <DropdownMenuSeparator />
                                <DropdownMenuItem variant="destructive" onSelect={() => remove(activeView)}>
                                    <TrashIcon />
                                    {t("delete")}
                                </DropdownMenuItem>
                            </>
                        )}
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

            <Dialog
                open={switchPrompt !== null}
                onOpenChange={(open) => { if (!open && !switching) declineSwitchWorkspace(); }}
            >
                <DialogContent className="sm:max-w-sm">
                    <DialogHeader>
                        <DialogTitle>{t("switchWorkspaceTitle")}</DialogTitle>
                        <DialogDescription>{t("switchWorkspacePrompt")}</DialogDescription>
                    </DialogHeader>
                    <DialogFooter>
                        <Button type="button" variant="outline" onClick={declineSwitchWorkspace} disabled={switching}>
                            {t("switchWorkspaceCancel")}
                        </Button>
                        <Button type="button" variant="brand" onClick={confirmSwitchWorkspace} disabled={switching}>
                            {t("switchWorkspaceConfirm")}
                        </Button>
                    </DialogFooter>
                </DialogContent>
            </Dialog>
        </div>
    );
}

function ViewTab({
    label,
    active,
    shared,
    sharedLabel,
    pinned,
    dirty,
    dirtyLabel,
    onClick,
}: {
    label: string;
    active: boolean;
    shared?: boolean;
    sharedLabel?: string;
    pinned?: boolean;
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
                "inline-flex items-center gap-1.5 whitespace-nowrap rounded-md px-3 py-1.5 text-sm font-medium transition-colors",
                active ? "bg-muted text-foreground" : "text-muted-foreground hover:bg-muted/60 hover:text-foreground",
            )}
        >
            {pinned && <BookmarkIcon className="size-3.5 shrink-0 text-muted-foreground" aria-hidden="true" />}
            {shared && (
                <>
                    <UsersIcon className="size-3.5 shrink-0 text-muted-foreground" aria-hidden="true" />
                    <span className="sr-only">{sharedLabel}</span>
                </>
            )}
            {label}
            {dirty && (
                <>
                    <span className="inline-block size-1.5 rounded-full bg-brand align-middle" aria-hidden="true" />
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
    onSubmit: (name: string) => Promise<string | null>;
}) {
    const t = useTranslations("SavedViews");
    const [name, setName] = useState(initialName);
    const [saving, setSaving] = useState(false);
    const [fieldError, setFieldError] = useState<string | null>(null);

    const handleSubmit = async (e: React.SubmitEvent<HTMLFormElement>) => {
        e.preventDefault();
        if (!name.trim()) return;
        setSaving(true);
        setFieldError(null);
        const error = await onSubmit(name.trim());
        setSaving(false);
        if (error) setFieldError(error);
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
                    onChange={(e) => { setName(e.target.value); setFieldError(null); }}
                    maxLength={128}
                    autoFocus
                    aria-invalid={fieldError !== null}
                    placeholder={t("namePlaceholder")}
                />
                {fieldError && <p className="text-sm text-destructive">{fieldError}</p>}
            </div>
            <DialogFooter>
                <DialogClose asChild>
                    <Button type="button" variant="outline" disabled={saving}>
                        {t("cancel")}
                    </Button>
                </DialogClose>
                <Button type="submit" variant="brand" disabled={saving || !name.trim()}>
                    {t("save")}
                </Button>
            </DialogFooter>
        </form>
    );
}
