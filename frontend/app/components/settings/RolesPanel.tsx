"use client";

import { useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
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
    updateBuiltInRoleExportPermission,
} from "@/app/lib/api";
import { useWorkspace } from "@/app/hooks/useWorkspace";
import { usePermission } from "@/app/hooks/usePermissions";
import { usePasskeyStepUpErrorHandler } from "@/app/hooks/usePasskeyStepUpError";
import {
    ConfinementAcknowledgementDeclined,
    useConfinementAcknowledgement,
} from "@/app/hooks/useConfinementAcknowledgement";
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
import { Switch } from "@/components/ui/switch";
import { rowActionTrigger } from "@/app/components/settings/SettingsListPrimitives";
import DeleteRecordDialog from "@/app/components/records/DeleteRecordDialog";
import Rise from "@/app/components/motion/Rise";
import { SettingsSection } from "@/app/components/settings/SettingsSection";
import ConfinementAcknowledgementDialog from "@/app/components/settings/ConfinementAcknowledgementDialog";
import RoleDialog from "./RoleDialog";
import { groupPermissions, type PermissionGroup } from "./permissions";

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
}: {
    role: CustomRole;
    groups: PermissionGroup[];
}) {
    const t = useTranslations("WorkspaceRoles");
    const permissions = new Set(role.permissions);
    return (
        <details className="text-sm">
            <summary className="cursor-pointer text-muted-foreground focus-visible:outline-2 focus-visible:outline-ring">
                {t("accessDetails")}
            </summary>
            <p className="mt-2 text-xs text-muted-foreground">{t("accessScopeHint")}</p>
            <div className="mt-3 space-y-4">
                {groups.map(({ group, label, items }) => (
                    <div key={group} className="space-y-1.5">
                        <p className="font-medium text-foreground">{label}</p>
                        <ul className="space-y-2">
                            {items.map(({ permission, action, description }) => (
                                <li key={permission}>
                                    <p className="text-foreground">
                                        {action} · {t(permissions.has(permission) ? "allowed" : "notAllowed")}
                                    </p>
                                    <p className="text-xs text-muted-foreground">{description}</p>
                                </li>
                            ))}
                        </ul>
                    </div>
                ))}
            </div>
        </details>
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
    const canManageRoles = usePermission("ROLE_MANAGE");
    const showApiError = useApiErrorToast("WorkspaceRoles");
    const headingLevel = presentation === "section" ? 3 : 2;
    const handlePasskeyStepUpError = usePasskeyStepUpErrorHandler();
    const router = useRouter();
    const confinement = useConfinementAcknowledgement();
    const { activeWorkspaceId } = useWorkspace();
    const workspaceId = activeWorkspaceId;
    const workspaceEpoch = useRef(0);
    useLayoutEffect(() => {
        workspaceEpoch.current += 1;
        return () => { workspaceEpoch.current += 1; };
    }, [workspaceId]);

    const [roles, setRoles] = useState<CustomRole[]>([]);
    const [builtIn, setBuiltIn] = useState<CustomRole[]>([]);
    const [catalog, setCatalog] = useState<string[]>([]);
    const [loading, setLoading] = useState(true);
    const [accessDenied, setAccessDenied] = useState(false);

    const [dialogOpen, setDialogOpen] = useState(false);
    const [editing, setEditing] = useState<CustomRole | null>(null);
    const [removeTarget, setRemoveTarget] = useState<CustomRole | null>(null);
    const [isRemoving, setIsRemoving] = useState(false);
    const [savingExportRole, setSavingExportRole] = useState<string | null>(null);

    const groups = useMemo(() => groupPermissions(catalog, t), [catalog, t]);

    useEffect(() => {
        if (!workspaceId) return;
        let cancelled = false;
        (async () => {
            setLoading(true);
            setSavingExportRole(null);
            try {
                const [loadedRoles, loadedBuiltIn, loadedCatalog] = await Promise.all([
                    canManageRoles ? getWorkspaceRoles(workspaceId) : Promise.resolve([]),
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
    }, [workspaceId, canManageRoles, t]);

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
                const updated = await confinement.run(
                    t("confinementSubjectRoleEdit", { name }),
                    (acknowledge) => updateWorkspaceRole(workspaceId, editing.id, name, permissions, acknowledge),
                );
                if (updated === null) throw new ConfinementAcknowledgementDeclined();
                setRoles((prev) => prev.map((r) => (r.id === updated.id ? updated : r)));
                toastSuccess(t("updated"));
            } else {
                const created = await createWorkspaceRole(workspaceId, name, permissions);
                setRoles((prev) => [...prev, created].sort((a, b) => a.name.localeCompare(b.name)));
                toastSuccess(t("created"));
            }
            router.refresh();
        } catch (err) {
            if (!(err instanceof ConfinementAcknowledgementDeclined) && !handlePasskeyStepUpError(err)) {
                showApiError(err, "saveFailed");
            }
            throw err;
        }
    };

    const setBuiltInExport = async (role: CustomRole, enabled: boolean) => {
        if (!workspaceId || savingExportRole !== null) return;
        const originEpoch = workspaceEpoch.current;
        setSavingExportRole(role.name);
        try {
            const updated = await updateBuiltInRoleExportPermission(workspaceId, role.name, enabled);
            if (workspaceEpoch.current !== originEpoch) return;
            setBuiltIn(updated);
            router.refresh();
            toastSuccess(t("updated"));
        } catch (err) {
            if (workspaceEpoch.current === originEpoch && !handlePasskeyStepUpError(err)) showApiError(err, "saveFailed");
        } finally {
            if (workspaceEpoch.current === originEpoch) setSavingExportRole(null);
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

    return (
        <div className="space-y-10">
            <Rise className="space-y-4">
                <SettingsSection
                    headingLevel={headingLevel}
                    title={t("defaultRolesLabel")}
                    description={t("builtInSubtitle")}
                    action={
                        !loading && canManageRoles && (
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
                                            {t(`builtInRoles.${role.name}.name`)}
                                        </span>
                                        <Badge variant="secondary" className="text-muted-foreground">
                                            <LockClosedIcon className="size-3" />
                                            {t("builtInBadge")}
                                        </Badge>
                                    </div>
                                    <p className="text-sm text-muted-foreground">{t(`builtInRoles.${role.name}.description`)}</p>
                                    <div className="flex items-center justify-between gap-4 py-2">
                                        <label htmlFor={`built-in-export-${role.name}`} className="text-sm text-foreground">
                                            {t("permissionLabels.EXPORT_DATA.action")}
                                        </label>
                                        <Switch
                                            id={`built-in-export-${role.name}`}
                                            aria-label={t("exportPermissionLabel", { role: t(`builtInRoles.${role.name}.name`) })}
                                            checked={role.permissions.includes("EXPORT_DATA")}
                                            disabled={role.exportPermissionEditable !== true || savingExportRole !== null}
                                            onCheckedChange={(enabled) => void setBuiltInExport(role, enabled)}
                                        />
                                    </div>
                                    {savingExportRole === role.name && <p className="text-xs text-muted-foreground">{t("exportPermissionSaving")}</p>}
                                    <PermissionSummary role={role} groups={groups} />
                                </div>
                            </li>
                        ))}
                    </ul>
                )}
            </Rise>

            {canManageRoles && <Rise className="space-y-4">
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
                                    <PermissionSummary role={role} groups={groups} />
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
            </Rise>}

            <RoleDialog
                open={dialogOpen}
                onOpenChange={setDialogOpen}
                groups={groups}
                editing={editing}
                onSubmit={submitRole}
            />

            <ConfinementAcknowledgementDialog acknowledgement={confinement} />

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
