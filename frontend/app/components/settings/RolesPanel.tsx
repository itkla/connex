"use client";

import { useCallback, useEffect, useState } from "react";
import { useTranslations } from "next-intl";
import { Loader2Icon } from "lucide-react";
import { PencilSquareIcon, PlusIcon, TrashIcon } from "@heroicons/react/24/outline";

import type { CustomRole } from "@/app/lib/types";
import {
    createWorkspaceRole,
    deleteWorkspaceRole,
    getPermissionCatalog,
    getWorkspaceRoles,
    updateWorkspaceRole,
} from "@/app/lib/api";
import { useWorkspace } from "@/app/hooks/useWorkspace";
import { toastError, toastSuccess } from "@/app/lib/toast";
import { fieldInputClass } from "@/components/ui/dialog-status-cover";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";

function humanize(token: string): string {
    return token.charAt(0).toUpperCase() + token.slice(1).toLowerCase();
}

function splitPermission(permission: string): { group: string; action: string } {
    const idx = permission.indexOf("_");
    const group = idx >= 0 ? permission.slice(0, idx) : permission;
    const action = idx >= 0 ? permission.slice(idx + 1) : permission;
    return {
        group: humanize(group),
        action: action.split("_").map(humanize).join(" "),
    };
}

function groupCatalog(catalog: string[]): { group: string; items: { permission: string; action: string }[] }[] {
    const groups = new Map<string, { permission: string; action: string }[]>();
    for (const permission of catalog) {
        const { group, action } = splitPermission(permission);
        if (!groups.has(group)) groups.set(group, []);
        groups.get(group)!.push({ permission, action });
    }
    return [...groups.entries()].map(([group, items]) => ({ group, items }));
}

export default function RolesPanel() {
    const t = useTranslations("WorkspaceRoles");
    const { activeWorkspaceId } = useWorkspace();
    const workspaceId = activeWorkspaceId;

    const [roles, setRoles] = useState<CustomRole[]>([]);
    const [catalog, setCatalog] = useState<string[]>([]);
    const [loading, setLoading] = useState(true);
    const [accessDenied, setAccessDenied] = useState(false);

    const [editingId, setEditingId] = useState<number | "new" | null>(null);
    const [draftName, setDraftName] = useState("");
    const [draftPerms, setDraftPerms] = useState<Set<string>>(new Set());
    const [saving, setSaving] = useState(false);
    const [busyId, setBusyId] = useState<number | null>(null);

    useEffect(() => {
        if (!workspaceId) return;
        let cancelled = false;
        (async () => {
            setLoading(true);
            try {
                const [loadedRoles, loadedCatalog] = await Promise.all([
                    getWorkspaceRoles(workspaceId),
                    getPermissionCatalog(),
                ]);
                if (cancelled) return;
                setRoles(loadedRoles);
                setCatalog(loadedCatalog);
            } catch (err) {
                if (!cancelled) {
                    if (err instanceof Error && "status" in err && (err as { status?: number }).status === 403) {
                        setAccessDenied(true);
                    } else {
                        toastError(t("loadFailed"));
                    }
                }
            } finally {
                if (!cancelled) setLoading(false);
            }
        })();
        return () => {
            cancelled = true;
        };
    }, [workspaceId, t]);

    const startNew = () => {
        setEditingId("new");
        setDraftName("");
        setDraftPerms(new Set());
    };

    const startEdit = (role: CustomRole) => {
        setEditingId(role.id);
        setDraftName(role.name);
        setDraftPerms(new Set(role.permissions));
    };

    const togglePerm = useCallback((permission: string) => {
        setDraftPerms((prev) => {
            const next = new Set(prev);
            if (next.has(permission)) next.delete(permission);
            else next.add(permission);
            return next;
        });
    }, []);

    const save = async () => {
        if (!workspaceId || saving) return;
        const name = draftName.trim();
        if (!name) {
            toastError(t("nameRequired"));
            return;
        }
        setSaving(true);
        const permissions = [...draftPerms];
        try {
            if (editingId === "new") {
                const created = await createWorkspaceRole(workspaceId, name, permissions);
                setRoles((prev) => [...prev, created].sort((a, b) => a.name.localeCompare(b.name)));
                toastSuccess(t("created"));
            } else if (typeof editingId === "number") {
                const updated = await updateWorkspaceRole(workspaceId, editingId, name, permissions);
                setRoles((prev) => prev.map((r) => (r.id === updated.id ? updated : r)));
                toastSuccess(t("updated"));
            }
            setEditingId(null);
        } catch (err) {
            toastError(err instanceof Error ? err.message : t("saveFailed"));
        } finally {
            setSaving(false);
        }
    };

    const remove = async (role: CustomRole) => {
        if (!workspaceId) return;
        setBusyId(role.id);
        try {
            await deleteWorkspaceRole(workspaceId, role.id);
            setRoles((prev) => prev.filter((r) => r.id !== role.id));
            toastSuccess(t("deleted"));
        } catch (err) {
            toastError(err instanceof Error ? err.message : t("deleteFailed"));
        } finally {
            setBusyId(null);
        }
    };

    if (accessDenied) {
        return (
            <p className="rounded-lg border border-border bg-card px-4 py-8 text-center text-sm text-muted-foreground">
                {t("noAccess")}
            </p>
        );
    }

    const grouped = groupCatalog(catalog);

    return (
        <section className="space-y-6">
            <header className="flex items-start justify-between gap-4">
                <div>
                    <h2 className="text-lg font-semibold tracking-tight text-foreground">{t("title")}</h2>
                    <p className="text-sm text-muted-foreground">{t("subtitle")}</p>
                </div>
                {editingId === null && (
                    <Button
                        onClick={startNew}
                        className="shrink-0 bg-brand text-white shadow-sm transition hover:bg-brand-hover"
                    >
                        <PlusIcon className="size-4" />
                        {t("newRole")}
                    </Button>
                )}
            </header>

            {editingId !== null && (
                <div className="rounded-lg border border-border bg-card p-5">
                    <input
                        type="text"
                        value={draftName}
                        onChange={(e) => setDraftName(e.target.value)}
                        placeholder={t("roleNamePlaceholder")}
                        aria-label={t("roleName")}
                        className={cn(fieldInputClass, "mb-5 px-3")}
                        autoFocus
                        maxLength={64}
                    />
                    <div className="space-y-5">
                        {grouped.map(({ group, items }) => (
                            <div key={group}>
                                <h3 className="mb-2 text-xs font-semibold uppercase tracking-wider text-muted-foreground">
                                    {group}
                                </h3>
                                <div className="grid grid-cols-2 gap-2 sm:grid-cols-3">
                                    {items.map(({ permission, action }) => (
                                        <label
                                            key={permission}
                                            className="flex cursor-pointer items-center gap-2 rounded-md px-2 py-1.5 text-sm transition hover:bg-muted"
                                        >
                                            <input
                                                type="checkbox"
                                                checked={draftPerms.has(permission)}
                                                onChange={() => togglePerm(permission)}
                                                className="size-4 rounded border-border accent-brand"
                                            />
                                            <span className="text-foreground">{action}</span>
                                        </label>
                                    ))}
                                </div>
                            </div>
                        ))}
                    </div>
                    <div className="mt-6 flex justify-end gap-2">
                        <Button variant="outline" onClick={() => setEditingId(null)} disabled={saving}>
                            {t("cancel")}
                        </Button>
                        <Button
                            onClick={save}
                            disabled={saving}
                            className="min-w-24 bg-brand text-white shadow-sm transition hover:bg-brand-hover"
                        >
                            {saving ? <Loader2Icon className="size-4 animate-spin" /> : t("save")}
                        </Button>
                    </div>
                </div>
            )}

            {loading ? (
                <div className="h-20 animate-pulse rounded-lg border border-border bg-card" />
            ) : roles.length === 0 && editingId === null ? (
                <p className="rounded-lg border border-dashed border-border px-4 py-8 text-center text-sm text-muted-foreground">
                    {t("empty")}
                </p>
            ) : (
                <ul className="divide-y divide-border overflow-hidden rounded-lg border border-border bg-card">
                    {roles.map((role) => (
                        <li key={role.id} className="flex items-center gap-3 px-4 py-3">
                            <div className="min-w-0 flex-1">
                                <span className="block truncate text-sm font-medium text-foreground">{role.name}</span>
                                <span className="text-xs text-muted-foreground">
                                    {t("permissionCount", { count: role.permissions.length })}
                                </span>
                            </div>
                            <button
                                type="button"
                                onClick={() => startEdit(role)}
                                aria-label={t("edit")}
                                className="rounded-md p-1.5 text-muted-foreground transition hover:bg-muted hover:text-foreground"
                            >
                                <PencilSquareIcon className="size-4" />
                            </button>
                            <button
                                type="button"
                                onClick={() => remove(role)}
                                disabled={busyId === role.id}
                                aria-label={t("delete")}
                                className="rounded-md p-1.5 text-muted-foreground transition hover:bg-destructive/10 hover:text-destructive disabled:opacity-50"
                            >
                                {busyId === role.id ? (
                                    <Loader2Icon className="size-4 animate-spin" />
                                ) : (
                                    <TrashIcon className="size-4" />
                                )}
                            </button>
                        </li>
                    ))}
                </ul>
            )}
        </section>
    );
}
