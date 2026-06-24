"use client";

import { useEffect, useMemo, useState } from "react";
import { useTranslations } from "next-intl";
import {
    LockClosedIcon,
    PencilSquareIcon,
    PlusIcon,
    ShieldCheckIcon,
    TrashIcon,
} from "@heroicons/react/24/outline";

import type { CustomRole } from "@/app/lib/types";
import {
    createWorkspaceRole,
    deleteWorkspaceRole,
    getBuiltInRoles,
    getPermissionCatalog,
    getWorkspaceRoles,
    updateWorkspaceRole,
} from "@/app/lib/api";
import { useWorkspace } from "@/app/hooks/useWorkspace";
import { toastError, toastSuccess } from "@/app/lib/toast";
import { Button } from "@/components/ui/button";
import DeleteRecordDialog from "@/app/components/records/DeleteRecordDialog";
import RoleDialog from "./RoleDialog";
import { groupPermissions, type PermissionGroup } from "./permissions";

function SectionLabel({ children }: { children: React.ReactNode }) {
    return (
        <h2 className="text-xs font-medium tracking-[0.12em] text-muted-foreground uppercase">{children}</h2>
    );
}

function RoleIcon({ locked }: { locked?: boolean }) {
    return (
        <span
            aria-hidden
            className="grid size-9 shrink-0 place-items-center rounded-lg bg-background text-muted-foreground ring-1 ring-border"
        >
            {locked ? <LockClosedIcon className="size-4" /> : <ShieldCheckIcon className="size-4" />}
        </span>
    );
}

function PermissionSummary({
    role,
    groups,
    totalPermissions,
}: {
    role: CustomRole;
    groups: PermissionGroup[];
    totalPermissions: number;
}) {
    const t = useTranslations("WorkspaceRoles");
    if (role.permissions.length === 0) {
        return <span className="text-xs text-muted-foreground">{t("noPermissions")}</span>;
    }
    if (totalPermissions > 0 && role.permissions.length === totalPermissions) {
        return <span className="text-xs font-medium text-brand">{t("fullAccess")}</span>;
    }
    const set = new Set(role.permissions);
    const touched = groups.filter((g) => g.items.some((i) => set.has(i.permission)));
    return (
        <div className="flex flex-wrap items-center gap-1.5">
            {touched.map(({ group, label, items }) => {
                const on = items.filter((i) => set.has(i.permission)).length;
                return (
                    <span
                        key={group}
                        className="inline-flex items-center gap-1 rounded-full bg-background px-2 py-0.5 text-[11px] font-medium text-muted-foreground ring-1 ring-border"
                    >
                        {label}
                        <span className="text-muted-foreground/60">{on}</span>
                    </span>
                );
            })}
        </div>
    );
}

export default function RolesPanel() {
    const t = useTranslations("WorkspaceRoles");
    const { activeWorkspaceId } = useWorkspace();
    const workspaceId = activeWorkspaceId;

    const [roles, setRoles] = useState<CustomRole[]>([]);
    const [builtIn, setBuiltIn] = useState<CustomRole[]>([]);
    const [catalog, setCatalog] = useState<string[]>([]);
    const [loading, setLoading] = useState(true);
    const [accessDenied, setAccessDenied] = useState(false);

    const [dialogOpen, setDialogOpen] = useState(false);
    const [editing, setEditing] = useState<CustomRole | null>(null);
    const [removeTarget, setRemoveTarget] = useState<CustomRole | null>(null);
    const [isRemoving, setIsRemoving] = useState(false);

    const groups = useMemo(() => groupPermissions(catalog), [catalog]);

    useEffect(() => {
        if (!workspaceId) return;
        let cancelled = false;
        (async () => {
            setLoading(true);
            try {
                const [loadedRoles, loadedBuiltIn, loadedCatalog] = await Promise.all([
                    getWorkspaceRoles(workspaceId),
                    getBuiltInRoles(workspaceId),
                    getPermissionCatalog(),
                ]);
                if (cancelled) return;
                setRoles(loadedRoles);
                setBuiltIn(loadedBuiltIn);
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

    const openCreate = () => {
        setEditing(null);
        setDialogOpen(true);
    };

    const openEdit = (role: CustomRole) => {
        setEditing(role);
        setDialogOpen(true);
    };

    const submitRole = async (name: string, permissions: string[]) => {
        if (!workspaceId) return;
        try {
            if (editing) {
                const updated = await updateWorkspaceRole(workspaceId, editing.id, name, permissions);
                setRoles((prev) => prev.map((r) => (r.id === updated.id ? updated : r)));
                toastSuccess(t("updated"));
            } else {
                const created = await createWorkspaceRole(workspaceId, name, permissions);
                setRoles((prev) => [...prev, created].sort((a, b) => a.name.localeCompare(b.name)));
                toastSuccess(t("created"));
            }
        } catch (err) {
            toastError(err instanceof Error ? err.message : t("saveFailed"));
            throw err;
        }
    };

    const confirmRemove = async () => {
        if (!workspaceId || !removeTarget) return;
        setIsRemoving(true);
        try {
            await deleteWorkspaceRole(workspaceId, removeTarget.id);
            setRoles((prev) => prev.filter((r) => r.id !== removeTarget.id));
            toastSuccess(t("deleted"));
            setRemoveTarget(null);
        } catch (err) {
            toastError(err instanceof Error ? err.message : t("deleteFailed"));
        } finally {
            setIsRemoving(false);
        }
    };

    if (accessDenied) {
        return (
            <p className="rounded-2xl bg-muted px-4 py-8 text-center text-sm text-muted-foreground ring-1 ring-border">
                {t("noAccess")}
            </p>
        );
    }

    const totalPermissions = catalog.length;

    return (
        <div className="space-y-10">
            <section className="space-y-3">
                <div className="flex items-start justify-between gap-4">
                    <div className="space-y-1">
                        <SectionLabel>{t("defaultRolesLabel")}</SectionLabel>
                        <p className="text-sm text-muted-foreground">{t("builtInSubtitle")}</p>
                    </div>
                    {!loading && (
                        <Button
                            onClick={openCreate}
                            className="shrink-0 bg-brand text-white shadow-sm transition hover:bg-brand-hover hover:shadow-md"
                        >
                            <PlusIcon className="size-4" />
                            {t("newRole")}
                        </Button>
                    )}
                </div>

                {loading ? (
                    <RoleSkeleton rows={3} />
                ) : (
                    <ul className="divide-y divide-border overflow-hidden rounded-2xl bg-muted ring-1 ring-border">
                        {builtIn.map((role) => (
                            <li key={role.name} className="flex items-start gap-3 px-4 py-3.5">
                                <RoleIcon locked />
                                <div className="min-w-0 flex-1 space-y-1.5">
                                    <div className="flex items-center gap-2">
                                        <span className="truncate text-sm font-medium capitalize text-foreground">
                                            {role.name}
                                        </span>
                                        <span className="inline-flex items-center gap-1 rounded-full bg-background px-2 py-0.5 text-[11px] font-medium text-muted-foreground ring-1 ring-border">
                                            <LockClosedIcon className="size-3" />
                                            {t("builtInBadge")}
                                        </span>
                                    </div>
                                    <PermissionSummary
                                        role={role}
                                        groups={groups}
                                        totalPermissions={totalPermissions}
                                    />
                                </div>
                            </li>
                        ))}
                    </ul>
                )}
            </section>

            <section className="space-y-3">
                <div className="flex items-baseline justify-between">
                    <SectionLabel>{t("customRolesLabel")}</SectionLabel>
                    {!loading && roles.length > 0 && (
                        <span className="text-xs text-muted-foreground">
                            {t("permissionCount", { count: roles.length })}
                        </span>
                    )}
                </div>

                {loading ? (
                    <RoleSkeleton rows={2} />
                ) : roles.length === 0 ? (
                    <p className="rounded-2xl border border-dashed border-border px-4 py-10 text-center text-sm text-muted-foreground">
                        {t("empty")}
                    </p>
                ) : (
                    <ul className="divide-y divide-border overflow-hidden rounded-2xl bg-muted ring-1 ring-border">
                        {roles.map((role) => (
                            <li key={role.id} className="flex items-start gap-3 px-4 py-3.5">
                                <RoleIcon />
                                <div className="min-w-0 flex-1 space-y-1.5">
                                    <span className="block truncate text-sm font-medium text-foreground">
                                        {role.name}
                                    </span>
                                    <PermissionSummary
                                        role={role}
                                        groups={groups}
                                        totalPermissions={totalPermissions}
                                    />
                                </div>
                                <div className="flex shrink-0 items-center gap-1">
                                    <button
                                        type="button"
                                        onClick={() => openEdit(role)}
                                        aria-label={t("edit")}
                                        className="rounded-md p-1.5 text-muted-foreground transition hover:bg-background hover:text-foreground"
                                    >
                                        <PencilSquareIcon className="size-4" />
                                    </button>
                                    <button
                                        type="button"
                                        onClick={() => setRemoveTarget(role)}
                                        aria-label={t("delete")}
                                        className="rounded-md p-1.5 text-muted-foreground transition hover:bg-destructive/10 hover:text-destructive"
                                    >
                                        <TrashIcon className="size-4" />
                                    </button>
                                </div>
                            </li>
                        ))}
                    </ul>
                )}
            </section>

            <RoleDialog
                open={dialogOpen}
                onOpenChange={setDialogOpen}
                groups={groups}
                editing={editing}
                onSubmit={submitRole}
            />

            <DeleteRecordDialog
                open={removeTarget !== null}
                onOpenChange={(open) => {
                    if (!open) setRemoveTarget(null);
                }}
                selectedIds={new Set(removeTarget ? [removeTarget.id] : [])}
                selectedItems={removeTarget ? [removeTarget] : []}
                entityLabel={t("roleEntityLabel")}
                getDisplayName={(r) => r.name}
                isDeleting={isRemoving}
                confirmDelete={confirmRemove}
            />
        </div>
    );
}

function RoleSkeleton({ rows }: { rows: number }) {
    return (
        <ul className="divide-y divide-border overflow-hidden rounded-2xl bg-muted ring-1 ring-border">
            {Array.from({ length: rows }, (_, i) => (
                <li key={i} className="flex items-center gap-3 px-4 py-3.5">
                    <span className="size-9 shrink-0 animate-pulse rounded-lg bg-background" />
                    <div className="flex-1 space-y-2">
                        <span className="block h-3.5 w-28 animate-pulse rounded bg-background" />
                        <span className="block h-3 w-44 animate-pulse rounded bg-background" />
                    </div>
                </li>
            ))}
        </ul>
    );
}
