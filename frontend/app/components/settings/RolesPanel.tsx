"use client";

import { useEffect, useMemo, useState } from "react";
import { useRouter } from "next/navigation";
import AccessDenied from "@/app/components/AccessDenied";
import { useTranslations } from "next-intl";
import {
    EllipsisHorizontalIcon,
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
import { usePasskeyStepUpErrorHandler } from "@/app/hooks/usePasskeyStepUpError";
import { useApiErrorToast } from "@/app/hooks/useApiErrorToast";
import { toastError, toastSuccess } from "@/app/lib/toast";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
    DropdownMenu,
    DropdownMenuContent,
    DropdownMenuItem,
    DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Skeleton } from "@/components/ui/skeleton";
import DeleteRecordDialog from "@/app/components/records/DeleteRecordDialog";
import Rise from "@/app/components/motion/Rise";
import { SettingsSection } from "@/app/components/settings/SettingsSection";
import RoleDialog from "./RoleDialog";
import { groupPermissions, type PermissionGroup } from "./permissions";

const rowActionTrigger =
    "flex size-7 items-center justify-center rounded-full text-muted-foreground opacity-0 transition hover:bg-muted/70 hover:text-foreground group-hover:opacity-100 focus:opacity-100 focus-visible:opacity-100 data-[state=open]:opacity-100";

function RoleIcon({ locked }: { locked?: boolean }) {
    return (
        <span
            aria-hidden
            className="grid size-9 shrink-0 place-items-center rounded-lg bg-muted text-muted-foreground ring-1 ring-border"
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
                        className="inline-flex items-center gap-1 rounded-full bg-muted px-2 py-0.5 text-[11px] font-medium text-muted-foreground ring-1 ring-border"
                    >
                        {label}
                        <span className="text-muted-foreground/60">{on}</span>
                    </span>
                );
            })}
        </div>
    );
}

/**
 * Custom-role administration: the permission set each workspace role carries.
 *
 * Saving or deleting a role is refreshed from the server rather than only patched into local
 * state. Editing a role rewrites the effective permissions of everyone holding it — the viewer
 * included — and the app shell resolves those once per server render, so without the refresh
 * the editor would keep the gates and navigation of the permissions they just changed.
 */
/**
 * Where the panel is rendering, so one component serves both of its homes while #1340 migrates the
 * workspace destinations.
 *
 * - `page` is `/settings/roles` exactly as it ships: the panel's two headings are the page's only
 *   section headings.
 * - `section` is the roles section of People & access, which is already named "Roles" for the deep
 *   link that leads to it, so the panel's own headings sit one level below that name.
 */
export type RolesPresentation = "page" | "section";

export default function RolesPanel({ presentation = "page" }: { presentation?: RolesPresentation } = {}) {
    const t = useTranslations("WorkspaceRoles");
    const showApiError = useApiErrorToast("WorkspaceRoles");
    const headingLevel = presentation === "section" ? 3 : 2;
    const handlePasskeyStepUpError = usePasskeyStepUpErrorHandler();
    const router = useRouter();
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
            router.refresh();
        } catch (err) {
            if (!handlePasskeyStepUpError(err)) {
                showApiError(err, "saveFailed");
            }
            throw err;
        }
    };

    const confirmRemove = async () => {
        if (!workspaceId || !removeTarget) return;
        setIsRemoving(true);
        try {
            await deleteWorkspaceRole(workspaceId, removeTarget.id);
            setRoles((prev) => prev.filter((r) => r.id !== removeTarget.id));
            router.refresh();
            toastSuccess(t("deleted"));
            setRemoveTarget(null);
        } catch (err) {
            if (!handlePasskeyStepUpError(err)) {
                showApiError(err, "deleteFailed");
            }
        } finally {
            setIsRemoving(false);
        }
    };

    if (accessDenied) {
        return (
            <AccessDenied variant="inline" body={t("noAccess")} />
        );
    }

    const totalPermissions = catalog.length;

    return (
        <div className="space-y-10">
            <Rise className="space-y-4">
                <SettingsSection
                    headingLevel={headingLevel}
                    title={t("defaultRolesLabel")}
                    description={t("builtInSubtitle")}
                    action={
                        !loading && (
                            <Button onClick={openCreate} variant="brand">
                                <PlusIcon className="size-4" />
                                {t("newRole")}
                            </Button>
                        )
                    }
                />

                {loading ? (
                    <RoleSkeleton rows={3} />
                ) : (
                    <ul className="divide-y divide-border overflow-hidden rounded-2xl border border-border bg-card">
                        {builtIn.map((role) => (
                            <li key={role.name} className="flex items-start gap-3 px-4 py-3.5">
                                <RoleIcon locked />
                                <div className="min-w-0 flex-1 space-y-1.5">
                                    <div className="flex items-center gap-2">
                                        <span className="truncate text-sm font-medium capitalize text-foreground">
                                            {role.name}
                                        </span>
                                        <Badge variant="secondary" className="text-muted-foreground">
                                            <LockClosedIcon className="size-3" />
                                            {t("builtInBadge")}
                                        </Badge>
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
            </Rise>

            <Rise className="space-y-4">
                <SettingsSection
                    headingLevel={headingLevel}
                    title={t("customRolesLabel")}
                    action={
                        !loading && roles.length > 0 ? (
                            <Badge variant="secondary" className="tabular-nums">
                                {t("permissionCount", { count: roles.length })}
                            </Badge>
                        ) : undefined
                    }
                />

                {loading ? (
                    <RoleSkeleton rows={2} />
                ) : roles.length === 0 ? (
                    <p className="rounded-2xl border border-border bg-card px-4 py-6 text-center text-sm text-muted-foreground">
                        {t("empty")}
                    </p>
                ) : (
                    <ul className="divide-y divide-border overflow-hidden rounded-2xl border border-border bg-card">
                        {roles.map((role) => (
                            <li key={role.id} className="group flex items-start gap-3 px-4 py-3.5">
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
                                <DropdownMenu>
                                    <DropdownMenuTrigger asChild>
                                        <button
                                            type="button"
                                            aria-label={t("roleActions")}
                                            className={rowActionTrigger}
                                        >
                                            <EllipsisHorizontalIcon className="size-5" />
                                        </button>
                                    </DropdownMenuTrigger>
                                    <DropdownMenuContent align="end" className="w-40">
                                        <DropdownMenuItem onSelect={() => openEdit(role)}>
                                            <PencilSquareIcon className="size-4" />
                                            {t("edit")}
                                        </DropdownMenuItem>
                                        <DropdownMenuItem
                                            variant="destructive"
                                            onSelect={() => setRemoveTarget(role)}
                                        >
                                            <TrashIcon className="size-4" />
                                            {t("delete")}
                                        </DropdownMenuItem>
                                    </DropdownMenuContent>
                                </DropdownMenu>
                            </li>
                        ))}
                    </ul>
                )}
            </Rise>

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
        <ul className="divide-y divide-border overflow-hidden rounded-2xl border border-border bg-card">
            {Array.from({ length: rows }, (_, i) => (
                <li key={i} className="flex items-center gap-3 px-4 py-3.5">
                    <Skeleton className="size-9 shrink-0 rounded-lg" />
                    <div className="flex-1 space-y-2">
                        <Skeleton className="h-3.5 w-28" />
                        <Skeleton className="h-3 w-44" />
                    </div>
                </li>
            ))}
        </ul>
    );
}
