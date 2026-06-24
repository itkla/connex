"use client";

import { useCallback, useEffect, useState } from "react";
import { useTranslations } from "next-intl";
import { Loader2Icon } from "lucide-react";
import { EnvelopeIcon, LinkIcon, TrashIcon } from "@heroicons/react/24/outline";

import type { CustomRole, WorkspaceInvite, WorkspaceMember, WorkspaceRole } from "@/app/lib/types";
import {
    assignMemberCustomRole,
    createWorkspaceInvite,
    getWorkspaceInvites,
    getWorkspaceMembers,
    getWorkspaceRoles,
    removeWorkspaceMember,
    revokeWorkspaceInvite,
    updateMemberRole,
} from "@/app/lib/api";
import { useWorkspace } from "@/app/hooks/useWorkspace";
import { useFieldErrors } from "@/app/hooks/useFieldErrors";
import { toastError, toastSuccess } from "@/app/lib/toast";
import { fieldErrorClass, fieldInputClass } from "@/components/ui/dialog-status-cover";
import { Button } from "@/components/ui/button";
import DeleteRecordDialog from "@/app/components/records/DeleteRecordDialog";
import { cn } from "@/lib/utils";

const ASSIGNABLE: WorkspaceRole[] = ["member", "admin"];

function SectionLabel({ children }: { children: React.ReactNode }) {
    return (
        <h2 className="text-xs font-medium tracking-[0.12em] text-muted-foreground uppercase">{children}</h2>
    );
}

function MemberAvatar({ name }: { name: string }) {
    return (
        <span
            aria-hidden
            className="grid size-9 shrink-0 place-items-center rounded-lg bg-background text-sm font-medium text-muted-foreground ring-1 ring-border"
        >
            {name.trim().charAt(0).toUpperCase() || "?"}
        </span>
    );
}

function RoleBadge({ role, label }: { role: string; label: string }) {
    return (
        <span
            className={cn(
                "inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium",
                role === "owner"
                    ? "bg-brand-light text-brand-dark"
                    : "bg-background text-muted-foreground ring-1 ring-border",
            )}
        >
            {label}
        </span>
    );
}

export default function MembersPanel({ currentUserId }: { currentUserId: number | null }) {
    const t = useTranslations("WorkspaceMembers");
    const { activeWorkspaceId, activeWorkspace } = useWorkspace();
    const workspaceId = activeWorkspaceId;
    const role = activeWorkspace?.role;
    const isAdmin = role === "admin" || role === "owner";
    const isOwner = role === "owner";

    const [members, setMembers] = useState<WorkspaceMember[]>([]);
    const [invites, setInvites] = useState<WorkspaceInvite[]>([]);
    const [customRoles, setCustomRoles] = useState<CustomRole[]>([]);
    const [loading, setLoading] = useState(true);

    const [inviteEmail, setInviteEmail] = useState("");
    const [inviteRole, setInviteRole] = useState<WorkspaceRole>("member");
    const [sending, setSending] = useState(false);
    const { fieldErrors, reset: resetFieldErrors, clearError, captureFieldErrors } = useFieldErrors();

    const [busyMemberId, setBusyMemberId] = useState<number | null>(null);
    const [removeTarget, setRemoveTarget] = useState<WorkspaceMember | null>(null);
    const [isRemoving, setIsRemoving] = useState(false);
    const [busyInviteId, setBusyInviteId] = useState<number | null>(null);

    const roleLabel = useCallback(
        (r: string) =>
            r === "owner" ? t("roleOwner") : r === "admin" ? t("roleAdmin") : r === "member" ? t("roleMember") : r,
        [t],
    );

    useEffect(() => {
        if (!workspaceId) return;
        let cancelled = false;
        (async () => {
            setLoading(true);
            try {
                const loadedMembers = await getWorkspaceMembers(workspaceId);
                if (cancelled) return;
                setMembers(loadedMembers);
                if (isAdmin) {
                    const loadedInvites = await getWorkspaceInvites(workspaceId);
                    if (!cancelled) setInvites(loadedInvites);
                }
                if (isOwner) {
                    const loadedRoles = await getWorkspaceRoles(workspaceId);
                    if (!cancelled) setCustomRoles(loadedRoles);
                }
            } catch {
                if (!cancelled) toastError(t("loadFailed"));
            } finally {
                if (!cancelled) setLoading(false);
            }
        })();
        return () => {
            cancelled = true;
        };
    }, [workspaceId, isAdmin, isOwner, t]);

    const changeRole = async (userId: number, next: WorkspaceRole) => {
        if (!workspaceId) return;
        setBusyMemberId(userId);
        try {
            const updated = await updateMemberRole(workspaceId, userId, next);
            setMembers((prev) => prev.map((m) => (m.id === userId ? updated : m)));
            toastSuccess(t("roleChanged"));
        } catch (err) {
            toastError(err instanceof Error ? err.message : t("roleChangeFailed"));
        } finally {
            setBusyMemberId(null);
        }
    };

    const assignCustom = async (userId: number, roleId: number) => {
        if (!workspaceId) return;
        setBusyMemberId(userId);
        try {
            const updated = await assignMemberCustomRole(workspaceId, userId, roleId);
            setMembers((prev) => prev.map((m) => (m.id === userId ? updated : m)));
            toastSuccess(t("roleChanged"));
        } catch (err) {
            toastError(err instanceof Error ? err.message : t("roleChangeFailed"));
        } finally {
            setBusyMemberId(null);
        }
    };

    const confirmRemove = async () => {
        if (!workspaceId || !removeTarget) return;
        setIsRemoving(true);
        try {
            await removeWorkspaceMember(workspaceId, removeTarget.id);
            setMembers((prev) => prev.filter((m) => m.id !== removeTarget.id));
            toastSuccess(t("removed"));
            setRemoveTarget(null);
        } catch (err) {
            toastError(err instanceof Error ? err.message : t("removeFailed"));
        } finally {
            setIsRemoving(false);
        }
    };

    const copyInviteLink = useCallback(
        async (token: string, silent = false) => {
            const link = `${window.location.origin}/invite/${token}`;
            try {
                await navigator.clipboard.writeText(link);
                if (!silent) toastSuccess(t("linkCopied"));
            } catch {
                if (!silent) toastError(link);
            }
        },
        [t],
    );

    const sendInvite = async () => {
        if (!workspaceId || sending) return;
        resetFieldErrors();
        setSending(true);
        try {
            const invite = await createWorkspaceInvite(workspaceId, inviteEmail.trim(), inviteRole);
            setInvites((prev) => [invite, ...prev.filter((i) => i.email !== invite.email)]);
            setInviteEmail("");
            await copyInviteLink(invite.token, true);
            toastSuccess(t("inviteCreated"));
        } catch (err) {
            if (!captureFieldErrors(err)) {
                toastError(err instanceof Error ? err.message : t("inviteFailed"));
            }
        } finally {
            setSending(false);
        }
    };

    const revoke = async (inviteId: number) => {
        if (!workspaceId) return;
        setBusyInviteId(inviteId);
        try {
            await revokeWorkspaceInvite(workspaceId, inviteId);
            setInvites((prev) => prev.filter((i) => i.id !== inviteId));
            toastSuccess(t("revoked"));
        } catch (err) {
            toastError(err instanceof Error ? err.message : t("revokeFailed"));
        } finally {
            setBusyInviteId(null);
        }
    };

    const selectableRoles: WorkspaceRole[] = isOwner ? ["member", "admin", "owner"] : ASSIGNABLE;

    return (
        <div className="space-y-10">
            <section className="space-y-3">
                <div className="flex items-baseline justify-between">
                    <SectionLabel>{t("title")}</SectionLabel>
                    <span className="text-xs text-muted-foreground">{t("count", { count: members.length })}</span>
                </div>

                {loading ? (
                    <MemberSkeleton />
                ) : members.length === 0 ? (
                    <EmptyCard>{t("membersEmpty")}</EmptyCard>
                ) : (
                    <ul className="divide-y divide-border overflow-hidden rounded-2xl bg-muted ring-1 ring-border">
                        {members.map((member) => {
                            const isSelf = member.id === currentUserId;
                            const busy = busyMemberId === member.id;
                            const pending = member.status === "pending";
                            return (
                                <li key={member.id} className="flex items-center gap-3 px-4 py-3">
                                    <MemberAvatar name={member.displayName} />
                                    <div className="min-w-0 flex-1">
                                        <div className="flex items-center gap-2">
                                            <span className="truncate text-sm font-medium text-foreground">
                                                {member.displayName}
                                            </span>
                                            {isSelf && (
                                                <span className="rounded-full bg-background px-1.5 py-0.5 text-[11px] font-medium text-muted-foreground ring-1 ring-border">
                                                    {t("you")}
                                                </span>
                                            )}
                                            {pending && (
                                                <span className="rounded-full bg-amber-500/10 px-1.5 py-0.5 text-[11px] font-medium text-amber-600 dark:text-amber-400">
                                                    {t("pending")}
                                                </span>
                                            )}
                                        </div>
                                        <span className="truncate text-xs text-muted-foreground">{member.email}</span>
                                    </div>

                                    {isAdmin && (member.roleId == null || isOwner) ? (
                                        <select
                                            value={member.roleId ? `custom:${member.roleId}` : member.role}
                                            disabled={busy}
                                            onChange={(e) => {
                                                const value = e.target.value;
                                                if (value.startsWith("custom:")) assignCustom(member.id, Number(value.slice(7)));
                                                else changeRole(member.id, value as WorkspaceRole);
                                            }}
                                            aria-label={t("roleLabel")}
                                            className={cn(
                                                fieldInputClass,
                                                "w-auto cursor-pointer bg-background px-2 py-1 text-xs disabled:opacity-50",
                                            )}
                                        >
                                            {selectableRoles.map((r) => (
                                                <option key={r} value={r}>
                                                    {roleLabel(r)}
                                                </option>
                                            ))}
                                            {isOwner && customRoles.length > 0 && (
                                                <optgroup label={t("customRoles")}>
                                                    {customRoles.map((r) => (
                                                        <option key={r.id} value={`custom:${r.id}`}>
                                                            {r.name}
                                                        </option>
                                                    ))}
                                                </optgroup>
                                            )}
                                        </select>
                                    ) : (
                                        <RoleBadge role={member.role} label={roleLabel(member.role)} />
                                    )}

                                    {isAdmin && !isSelf && (
                                        <button
                                            type="button"
                                            onClick={() => setRemoveTarget(member)}
                                            aria-label={t("remove")}
                                            className="rounded-md p-1.5 text-muted-foreground transition hover:bg-destructive/10 hover:text-destructive"
                                        >
                                            <TrashIcon className="size-4" />
                                        </button>
                                    )}
                                </li>
                            );
                        })}
                    </ul>
                )}
            </section>

            {isAdmin && (
                <section className="space-y-3">
                    <SectionLabel>{t("inviteTitle")}</SectionLabel>
                    <p className="text-sm text-muted-foreground">{t("inviteSubtitle")}</p>

                    <form
                        onSubmit={(e) => {
                            e.preventDefault();
                            sendInvite();
                        }}
                        className="flex flex-col gap-3 sm:flex-row sm:items-start"
                    >
                        <div className="flex-1">
                            <div className="group relative">
                                <EnvelopeIcon className="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground transition-colors group-focus-within:text-brand" />
                                <input
                                    type="email"
                                    value={inviteEmail}
                                    onChange={(e) => {
                                        setInviteEmail(e.target.value);
                                        clearError("email");
                                    }}
                                    placeholder={t("emailPlaceholder")}
                                    aria-label={t("emailLabel")}
                                    aria-invalid={Boolean(fieldErrors.email)}
                                    className={cn(fieldInputClass, "pl-9 pr-3", fieldErrors.email && fieldErrorClass)}
                                />
                            </div>
                            {fieldErrors.email && (
                                <p className="mt-1 text-sm text-destructive">{fieldErrors.email}</p>
                            )}
                        </div>
                        <select
                            value={inviteRole}
                            onChange={(e) => setInviteRole(e.target.value as WorkspaceRole)}
                            aria-label={t("roleLabel")}
                            className={cn(fieldInputClass, "w-full cursor-pointer px-3 sm:w-32")}
                        >
                            {selectableRoles.map((r) => (
                                <option key={r} value={r}>
                                    {roleLabel(r)}
                                </option>
                            ))}
                        </select>
                        <Button
                            type="submit"
                            disabled={sending || inviteEmail.trim().length === 0}
                            className="min-w-28 bg-brand text-white shadow-sm transition hover:bg-brand-hover hover:shadow-md"
                        >
                            {sending ? <Loader2Icon className="size-4 animate-spin" /> : t("sendInvite")}
                        </Button>
                    </form>

                    <div className="pt-2">
                        <h3 className="mb-2 text-sm font-medium text-foreground">{t("pendingTitle")}</h3>
                        {invites.length === 0 ? (
                            <EmptyCard dashed>{t("pendingEmpty")}</EmptyCard>
                        ) : (
                            <ul className="divide-y divide-border overflow-hidden rounded-2xl bg-muted ring-1 ring-border">
                                {invites.map((invite) => (
                                    <li key={invite.id} className="flex items-center gap-3 px-4 py-3">
                                        <div className="min-w-0 flex-1">
                                            <div className="flex items-center gap-2">
                                                <span className="truncate text-sm font-medium text-foreground">
                                                    {invite.email}
                                                </span>
                                                <RoleBadge role={invite.role} label={roleLabel(invite.role)} />
                                            </div>
                                            <span className="text-xs text-muted-foreground">
                                                {t("expires", { date: invite.expiresAt.slice(0, 10) })}
                                            </span>
                                        </div>
                                        <button
                                            type="button"
                                            onClick={() => copyInviteLink(invite.token)}
                                            className="inline-flex items-center gap-1.5 rounded-md px-2 py-1 text-xs font-medium text-muted-foreground transition hover:bg-background hover:text-foreground"
                                        >
                                            <LinkIcon className="size-3.5" />
                                            {t("copyLink")}
                                        </button>
                                        <button
                                            type="button"
                                            onClick={() => revoke(invite.id)}
                                            disabled={busyInviteId === invite.id}
                                            className="rounded-md px-2 py-1 text-xs font-medium text-muted-foreground transition hover:bg-destructive/10 hover:text-destructive disabled:opacity-50"
                                        >
                                            {busyInviteId === invite.id ? (
                                                <Loader2Icon className="size-3.5 animate-spin" />
                                            ) : (
                                                t("revoke")
                                            )}
                                        </button>
                                    </li>
                                ))}
                            </ul>
                        )}
                    </div>
                </section>
            )}

            <DeleteRecordDialog
                open={removeTarget !== null}
                onOpenChange={(open) => {
                    if (!open) setRemoveTarget(null);
                }}
                selectedIds={new Set(removeTarget ? [removeTarget.id] : [])}
                selectedItems={removeTarget ? [removeTarget] : []}
                entityLabel={t("memberEntityLabel")}
                getDisplayName={(m) => m.displayName}
                isDeleting={isRemoving}
                confirmDelete={confirmRemove}
            />
        </div>
    );
}

function EmptyCard({ children, dashed = false }: { children: React.ReactNode; dashed?: boolean }) {
    return (
        <p
            className={cn(
                "rounded-2xl px-4 py-8 text-center text-sm text-muted-foreground",
                dashed ? "border border-dashed border-border" : "bg-muted ring-1 ring-border",
            )}
        >
            {children}
        </p>
    );
}

function MemberSkeleton() {
    return (
        <ul className="divide-y divide-border overflow-hidden rounded-2xl bg-muted ring-1 ring-border">
            {[0, 1, 2].map((i) => (
                <li key={i} className="flex items-center gap-3 px-4 py-3">
                    <span className="size-9 shrink-0 animate-pulse rounded-lg bg-background" />
                    <div className="flex-1 space-y-2">
                        <span className="block h-3.5 w-32 animate-pulse rounded bg-background" />
                        <span className="block h-3 w-48 animate-pulse rounded bg-background" />
                    </div>
                    <span className="h-6 w-16 animate-pulse rounded-full bg-background" />
                </li>
            ))}
        </ul>
    );
}
