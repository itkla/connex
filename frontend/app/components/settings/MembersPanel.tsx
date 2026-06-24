"use client";

import { useCallback, useEffect, useState } from "react";
import { useTranslations } from "next-intl";
import { Loader2Icon } from "lucide-react";
import {
    EnvelopeIcon,
    LinkIcon,
    TrashIcon,
    XMarkIcon,
} from "@heroicons/react/24/outline";

import type { WorkspaceInvite, WorkspaceMember, WorkspaceRole } from "@/app/lib/types";
import {
    createWorkspaceInvite,
    getWorkspaceInvites,
    getWorkspaceMembers,
    removeWorkspaceMember,
    revokeWorkspaceInvite,
    updateMemberRole,
} from "@/app/lib/api";
import { useWorkspace } from "@/app/hooks/useWorkspace";
import { useFieldErrors } from "@/app/hooks/useFieldErrors";
import { toastError, toastSuccess } from "@/app/lib/toast";
import { fieldErrorClass, fieldInputClass } from "@/components/ui/dialog-status-cover";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";

const ASSIGNABLE: WorkspaceRole[] = ["member", "admin"];

function MemberAvatar({ name }: { name: string }) {
    return (
        <span
            aria-hidden
            className="grid size-9 shrink-0 place-items-center rounded-lg bg-muted text-sm font-medium text-muted-foreground"
        >
            {name.trim().charAt(0).toUpperCase() || "?"}
        </span>
    );
}

function RoleBadge({ role, label }: { role: WorkspaceRole; label: string }) {
    return (
        <span
            className={cn(
                "inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium",
                role === "owner"
                    ? "bg-brand-light text-brand-dark"
                    : "bg-muted text-muted-foreground ring-1 ring-border",
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
    const [loading, setLoading] = useState(true);

    const [inviteEmail, setInviteEmail] = useState("");
    const [inviteRole, setInviteRole] = useState<WorkspaceRole>("member");
    const [sending, setSending] = useState(false);
    const { fieldErrors, reset: resetFieldErrors, clearError, captureFieldErrors } = useFieldErrors();

    const [busyMemberId, setBusyMemberId] = useState<number | null>(null);
    const [confirmRemoveId, setConfirmRemoveId] = useState<number | null>(null);
    const [busyInviteId, setBusyInviteId] = useState<number | null>(null);

    const roleLabel = useCallback(
        (r: WorkspaceRole) => t(r === "owner" ? "roleOwner" : r === "admin" ? "roleAdmin" : "roleMember"),
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
            } catch {
                if (!cancelled) toastError(t("loadFailed"));
            } finally {
                if (!cancelled) setLoading(false);
            }
        })();
        return () => {
            cancelled = true;
        };
    }, [workspaceId, isAdmin, t]);

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

    const remove = async (userId: number) => {
        if (!workspaceId) return;
        setBusyMemberId(userId);
        try {
            await removeWorkspaceMember(workspaceId, userId);
            setMembers((prev) => prev.filter((m) => m.id !== userId));
            toastSuccess(t("removed"));
        } catch (err) {
            toastError(err instanceof Error ? err.message : t("removeFailed"));
        } finally {
            setBusyMemberId(null);
            setConfirmRemoveId(null);
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
            <section>
                <header className="mb-4">
                    <h2 className="text-lg font-semibold tracking-tight text-foreground">{t("title")}</h2>
                    <p className="text-sm text-muted-foreground">
                        {t("subtitle", { workspace: activeWorkspace?.name ?? "" })}
                    </p>
                </header>

                {loading ? (
                    <MemberSkeleton />
                ) : members.length === 0 ? (
                    <p className="rounded-lg border border-border bg-card px-4 py-8 text-center text-sm text-muted-foreground">
                        {t("membersEmpty")}
                    </p>
                ) : (
                    <ul className="divide-y divide-border overflow-hidden rounded-lg border border-border bg-card">
                        {members.map((member) => {
                            const isSelf = member.id === currentUserId;
                            const busy = busyMemberId === member.id;
                            return (
                                <li key={member.id} className="flex items-center gap-3 px-4 py-3">
                                    <MemberAvatar name={member.displayName} />
                                    <div className="min-w-0 flex-1">
                                        <div className="flex items-center gap-2">
                                            <span className="truncate text-sm font-medium text-foreground">
                                                {member.displayName}
                                            </span>
                                            {isSelf && (
                                                <span className="rounded-full bg-muted px-1.5 py-0.5 text-[11px] font-medium text-muted-foreground">
                                                    {t("you")}
                                                </span>
                                            )}
                                        </div>
                                        <span className="truncate text-xs text-muted-foreground">{member.email}</span>
                                    </div>

                                    {isAdmin ? (
                                        <select
                                            value={member.role}
                                            disabled={busy}
                                            onChange={(e) => changeRole(member.id, e.target.value as WorkspaceRole)}
                                            aria-label={t("roleLabel")}
                                            className={cn(
                                                fieldInputClass,
                                                "w-auto cursor-pointer px-2 py-1 text-xs disabled:opacity-50",
                                            )}
                                        >
                                            {selectableRoles.map((r) => (
                                                <option key={r} value={r}>
                                                    {roleLabel(r)}
                                                </option>
                                            ))}
                                        </select>
                                    ) : (
                                        <RoleBadge role={member.role} label={roleLabel(member.role)} />
                                    )}

                                    {isAdmin && !isSelf && (
                                        <div className="w-24 shrink-0 text-right">
                                            {confirmRemoveId === member.id ? (
                                                <div className="inline-flex items-center gap-1">
                                                    <button
                                                        type="button"
                                                        onClick={() => remove(member.id)}
                                                        disabled={busy}
                                                        className="rounded-md bg-destructive px-2 py-1 text-xs font-medium text-white transition hover:opacity-90 disabled:opacity-50"
                                                    >
                                                        {busy ? (
                                                            <Loader2Icon className="size-3.5 animate-spin" />
                                                        ) : (
                                                            t("removeConfirm")
                                                        )}
                                                    </button>
                                                    <button
                                                        type="button"
                                                        onClick={() => setConfirmRemoveId(null)}
                                                        disabled={busy}
                                                        aria-label={t("cancel")}
                                                        className="rounded-md p-1 text-muted-foreground transition hover:bg-muted hover:text-foreground"
                                                    >
                                                        <XMarkIcon className="size-4" />
                                                    </button>
                                                </div>
                                            ) : (
                                                <button
                                                    type="button"
                                                    onClick={() => setConfirmRemoveId(member.id)}
                                                    aria-label={t("remove")}
                                                    className="rounded-md p-1.5 text-muted-foreground transition hover:bg-destructive/10 hover:text-destructive"
                                                >
                                                    <TrashIcon className="size-4" />
                                                </button>
                                            )}
                                        </div>
                                    )}
                                </li>
                            );
                        })}
                    </ul>
                )}
            </section>

            {isAdmin && (
                <section>
                    <header className="mb-4">
                        <h2 className="text-lg font-semibold tracking-tight text-foreground">{t("inviteTitle")}</h2>
                        <p className="text-sm text-muted-foreground">{t("inviteSubtitle")}</p>
                    </header>

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

                    <div className="mt-6">
                        <h3 className="mb-2 text-sm font-medium text-foreground">{t("pendingTitle")}</h3>
                        {invites.length === 0 ? (
                            <p className="rounded-lg border border-dashed border-border px-4 py-6 text-center text-sm text-muted-foreground">
                                {t("pendingEmpty")}
                            </p>
                        ) : (
                            <ul className="divide-y divide-border overflow-hidden rounded-lg border border-border bg-card">
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
                                            className="inline-flex items-center gap-1.5 rounded-md px-2 py-1 text-xs font-medium text-muted-foreground transition hover:bg-muted hover:text-foreground"
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
        </div>
    );
}

function MemberSkeleton() {
    return (
        <ul className="divide-y divide-border overflow-hidden rounded-lg border border-border bg-card">
            {[0, 1, 2].map((i) => (
                <li key={i} className="flex items-center gap-3 px-4 py-3">
                    <span className="size-9 shrink-0 animate-pulse rounded-lg bg-muted" />
                    <div className="flex-1 space-y-2">
                        <span className="block h-3.5 w-32 animate-pulse rounded bg-muted" />
                        <span className="block h-3 w-48 animate-pulse rounded bg-muted" />
                    </div>
                    <span className="h-6 w-16 animate-pulse rounded-full bg-muted" />
                </li>
            ))}
        </ul>
    );
}
