"use client";

import { useCallback, useEffect, useState } from "react";
import { useTranslations } from "next-intl";
import { Loader2Icon } from "lucide-react";
import { AtSymbolIcon, CheckIcon, EllipsisHorizontalIcon, EnvelopeIcon, GlobeAltIcon, LinkIcon, TrashIcon } from "@heroicons/react/24/outline";

import type {
    CustomRole,
    WorkspaceInvite,
    WorkspaceInviteLink,
    WorkspaceMember,
    WorkspaceRole,
} from "@/app/lib/types";
import {
    addWorkspaceAllowedDomain,
    assignMemberCustomRole,
    createWorkspaceInvite,
    createWorkspaceInviteLink,
    getWorkspaceAllowedDomains,
    getWorkspaceInviteLinks,
    getWorkspaceInvites,
    getWorkspaceMembers,
    getWorkspaceRoles,
    removeWorkspaceAllowedDomain,
    removeWorkspaceMember,
    revokeWorkspaceInvite,
    revokeWorkspaceInviteLink,
    updateMemberRole,
} from "@/app/lib/api";
import { useWorkspace } from "@/app/hooks/useWorkspace";
import { useFieldErrors } from "@/app/hooks/useFieldErrors";
import { toastError, toastSuccess } from "@/app/lib/toast";
import { Avatar, AvatarFallback, AvatarImage } from "@/components/ui/avatar";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
    DropdownMenu,
    DropdownMenuContent,
    DropdownMenuItem,
    DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Input } from "@/components/ui/input";
import { InputGroup, InputGroupAddon, InputGroupInput } from "@/components/ui/input-group";
import {
    Select,
    SelectContent,
    SelectGroup,
    SelectItem,
    SelectLabel,
    SelectTrigger,
    SelectValue,
} from "@/components/ui/select";
import { Skeleton } from "@/components/ui/skeleton";
import DeleteRecordDialog from "@/app/components/records/DeleteRecordDialog";
import Rise from "@/app/components/motion/Rise";
import SectionHeader from "@/app/components/dashboard/SectionHeader";

const ASSIGNABLE: WorkspaceRole[] = ["member", "admin"];

const rowActionTrigger =
    "flex size-7 items-center justify-center rounded-full text-muted-foreground opacity-0 transition hover:bg-muted/70 hover:text-foreground group-hover:opacity-100 focus:opacity-100 focus-visible:opacity-100 data-[state=open]:opacity-100";

function initial(name: string) {
    return name.trim().charAt(0).toUpperCase() || "?";
}

function RoleBadge({ role, label }: { role: string; label: string }) {
    return role === "owner" ? (
        <Badge className="border-transparent bg-brand-light text-brand-dark">{label}</Badge>
    ) : (
        <Badge variant="secondary">{label}</Badge>
    );
}

function ListCard({ children }: { children: React.ReactNode }) {
    return (
        <ul className="divide-y divide-border overflow-hidden rounded-2xl border border-border bg-card">
            {children}
        </ul>
    );
}

function EmptyRow({ children }: { children: React.ReactNode }) {
    return (
        <p className="rounded-2xl border border-border bg-card px-4 py-6 text-center text-sm text-muted-foreground">
            {children}
        </p>
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

    const [inviteLinks, setInviteLinks] = useState<WorkspaceInviteLink[]>([]);
    const [linkRole, setLinkRole] = useState<WorkspaceRole>("member");
    const [linkExpiry, setLinkExpiry] = useState("");
    const [linkMaxUses, setLinkMaxUses] = useState("");
    const [creatingLink, setCreatingLink] = useState(false);
    const [busyLinkId, setBusyLinkId] = useState<number | null>(null);
    const [copiedLinkId, setCopiedLinkId] = useState<number | null>(null);

    const [allowedDomains, setAllowedDomains] = useState<string[]>([]);
    const [domainInput, setDomainInput] = useState("");
    const [addingDomain, setAddingDomain] = useState(false);
    const [busyDomain, setBusyDomain] = useState<string | null>(null);

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
                    const loadedLinks = await getWorkspaceInviteLinks(workspaceId);
                    if (!cancelled) setInviteLinks(loadedLinks);
                    const loadedDomains = await getWorkspaceAllowedDomains(workspaceId);
                    if (!cancelled) setAllowedDomains(loadedDomains);
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
            const result = await createWorkspaceInvite(workspaceId, inviteEmail.trim(), inviteRole);
            setInviteEmail("");
            if (result.member) {
                const member = result.member;
                setMembers((prev) => [...prev.filter((m) => m.id !== member.id), member]);
                toastSuccess(t("memberInvited"));
            } else if (result.invite) {
                const invite = result.invite;
                setInvites((prev) => [invite, ...prev.filter((i) => i.email !== invite.email)]);
                await copyInviteLink(invite.token, true);
                toastSuccess(t("inviteCreated"));
            } else {
                toastError(t("inviteFailed"));
            }
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

    const copyShareLink = useCallback(
        async (link: WorkspaceInviteLink) => {
            const url = `${window.location.origin}/invite-link/${link.token}`;
            try {
                await navigator.clipboard.writeText(url);
                setCopiedLinkId(link.id);
                window.setTimeout(() => {
                    setCopiedLinkId((current) => (current === link.id ? null : current));
                }, 1500);
            } catch {
                toastError(url);
            }
        },
        [],
    );

    const createLink = async () => {
        if (!workspaceId || creatingLink) return;
        setCreatingLink(true);
        try {
            const expiresInDays = Number.parseInt(linkExpiry, 10);
            const maxUses = Number.parseInt(linkMaxUses, 10);
            const link = await createWorkspaceInviteLink(workspaceId, {
                role: linkRole,
                expiresInDays: Number.isFinite(expiresInDays) && expiresInDays > 0 ? expiresInDays : undefined,
                maxUses: Number.isFinite(maxUses) && maxUses > 0 ? maxUses : undefined,
            });
            setInviteLinks((prev) => [link, ...prev]);
            setLinkExpiry("");
            setLinkMaxUses("");
            await copyShareLink(link);
            toastSuccess(t("linkCreated"));
        } catch (err) {
            toastError(err instanceof Error ? err.message : t("linkCreateFailed"));
        } finally {
            setCreatingLink(false);
        }
    };

    const revokeLink = async (linkId: number) => {
        if (!workspaceId) return;
        setBusyLinkId(linkId);
        try {
            await revokeWorkspaceInviteLink(workspaceId, linkId);
            setInviteLinks((prev) => prev.filter((l) => l.id !== linkId));
            toastSuccess(t("linkRevoked"));
        } catch (err) {
            toastError(err instanceof Error ? err.message : t("linkRevokeFailed"));
        } finally {
            setBusyLinkId(null);
        }
    };

    const addDomain = async () => {
        if (!workspaceId || addingDomain) return;
        resetFieldErrors();
        setAddingDomain(true);
        try {
            const updated = await addWorkspaceAllowedDomain(workspaceId, domainInput.trim());
            setAllowedDomains(updated);
            setDomainInput("");
            toastSuccess(t("domainAdded"));
        } catch (err) {
            if (!captureFieldErrors(err)) {
                toastError(err instanceof Error ? err.message : t("domainAddFailed"));
            }
        } finally {
            setAddingDomain(false);
        }
    };

    const removeDomain = async (domain: string) => {
        if (!workspaceId) return;
        setBusyDomain(domain);
        try {
            await removeWorkspaceAllowedDomain(workspaceId, domain);
            setAllowedDomains((prev) => prev.filter((d) => d !== domain));
            toastSuccess(t("domainRemoved"));
        } catch (err) {
            toastError(err instanceof Error ? err.message : t("domainRemoveFailed"));
        } finally {
            setBusyDomain(null);
        }
    };

    const selectableRoles: WorkspaceRole[] = isOwner ? ["member", "admin", "owner"] : ASSIGNABLE;

    return (
        <div className="space-y-10">
            <Rise className="space-y-3">
                <SectionHeader
                    title={t("title")}
                    action={
                        <span className="text-xs text-muted-foreground tabular-nums">
                            {t("count", { count: members.length })}
                        </span>
                    }
                />

                {loading ? (
                    <MemberSkeleton />
                ) : members.length === 0 ? (
                    <EmptyRow>{t("membersEmpty")}</EmptyRow>
                ) : (
                    <ListCard>
                        {members.map((member) => {
                            const isSelf = member.id === currentUserId;
                            const busy = busyMemberId === member.id;
                            const pending = member.status === "pending";
                            const editable = isAdmin && (member.roleId == null || isOwner);
                            return (
                                <li key={member.id} className="group flex items-center gap-3 px-4 py-3">
                                    <Avatar>
                                        {member.profilePictureUrl && (
                                            <AvatarImage
                                                src={member.profilePictureUrl}
                                                alt={member.displayName}
                                            />
                                        )}
                                        <AvatarFallback>{initial(member.displayName)}</AvatarFallback>
                                    </Avatar>
                                    <div className="min-w-0 flex-1">
                                        <div className="flex items-center gap-2">
                                            <span className="truncate text-sm font-medium text-foreground">
                                                {member.displayName}
                                            </span>
                                            {isSelf && (
                                                <Badge variant="outline" className="text-muted-foreground">
                                                    {t("you")}
                                                </Badge>
                                            )}
                                            {pending && (
                                                <Badge className="border-transparent bg-warmth-cool/15 text-foreground">
                                                    {t("pending")}
                                                </Badge>
                                            )}
                                        </div>
                                        <p className="truncate text-xs text-muted-foreground">{member.email}</p>
                                    </div>

                                    {editable ? (
                                        <Select
                                            value={member.roleId ? `custom:${member.roleId}` : member.role}
                                            disabled={busy}
                                            onValueChange={(value) => {
                                                if (value.startsWith("custom:")) assignCustom(member.id, Number(value.slice(7)));
                                                else changeRole(member.id, value as WorkspaceRole);
                                            }}
                                        >
                                            <SelectTrigger size="sm" className="w-auto" aria-label={t("roleLabel")}>
                                                <SelectValue />
                                            </SelectTrigger>
                                            <SelectContent align="end">
                                                {selectableRoles.map((r) => (
                                                    <SelectItem key={r} value={r}>
                                                        {roleLabel(r)}
                                                    </SelectItem>
                                                ))}
                                                {isOwner && customRoles.length > 0 && (
                                                    <SelectGroup>
                                                        <SelectLabel>{t("customRoles")}</SelectLabel>
                                                        {customRoles.map((r) => (
                                                            <SelectItem key={r.id} value={`custom:${r.id}`}>
                                                                {r.name}
                                                            </SelectItem>
                                                        ))}
                                                    </SelectGroup>
                                                )}
                                            </SelectContent>
                                        </Select>
                                    ) : (
                                        <RoleBadge role={member.role} label={roleLabel(member.role)} />
                                    )}

                                    {isAdmin && !isSelf && (
                                        <DropdownMenu>
                                            <DropdownMenuTrigger asChild>
                                                <button
                                                    type="button"
                                                    aria-label={t("memberActions")}
                                                    className={rowActionTrigger}
                                                >
                                                    <EllipsisHorizontalIcon className="size-5" />
                                                </button>
                                            </DropdownMenuTrigger>
                                            <DropdownMenuContent align="end" className="w-40">
                                                <DropdownMenuItem
                                                    variant="destructive"
                                                    onSelect={() => setRemoveTarget(member)}
                                                >
                                                    <TrashIcon className="size-4" />
                                                    {t("remove")}
                                                </DropdownMenuItem>
                                            </DropdownMenuContent>
                                        </DropdownMenu>
                                    )}
                                </li>
                            );
                        })}
                    </ListCard>
                )}
            </Rise>

            {isAdmin && (
                <Rise className="space-y-4">
                    <div>
                        <SectionHeader title={t("inviteTitle")} />
                        <p className="px-6 text-sm text-muted-foreground">{t("inviteSubtitle")}</p>
                    </div>

                    <form
                        onSubmit={(e) => {
                            e.preventDefault();
                            sendInvite();
                        }}
                        className="flex flex-col gap-3 sm:flex-row sm:items-start"
                    >
                        <div className="flex-1">
                            <InputGroup>
                                <InputGroupAddon>
                                    <EnvelopeIcon />
                                </InputGroupAddon>
                                <InputGroupInput
                                    type="email"
                                    value={inviteEmail}
                                    onChange={(e) => {
                                        setInviteEmail(e.target.value);
                                        clearError("email");
                                    }}
                                    placeholder={t("emailPlaceholder")}
                                    aria-label={t("emailLabel")}
                                    aria-invalid={Boolean(fieldErrors.email)}
                                />
                            </InputGroup>
                            {fieldErrors.email && (
                                <p className="mt-1.5 text-sm text-destructive">{fieldErrors.email}</p>
                            )}
                        </div>
                        <Select value={inviteRole} onValueChange={(v) => setInviteRole(v as WorkspaceRole)}>
                            <SelectTrigger className="w-full sm:w-36" aria-label={t("roleLabel")}>
                                <SelectValue />
                            </SelectTrigger>
                            <SelectContent align="end">
                                {selectableRoles.map((r) => (
                                    <SelectItem key={r} value={r}>
                                        {roleLabel(r)}
                                    </SelectItem>
                                ))}
                            </SelectContent>
                        </Select>
                        <Button
                            type="submit"
                            disabled={sending || inviteEmail.trim().length === 0}
                            className="min-w-28 bg-brand text-white hover:bg-brand-hover"
                        >
                            {sending ? <Loader2Icon className="size-4 animate-spin" /> : t("sendInvite")}
                        </Button>
                    </form>

                    <div className="space-y-2 pt-2">
                        <SectionHeader title={t("pendingTitle")} />
                        {invites.length === 0 ? (
                            <EmptyRow>{t("pendingEmpty")}</EmptyRow>
                        ) : (
                            <ListCard>
                                {invites.map((invite) => {
                                    const busy = busyInviteId === invite.id;
                                    return (
                                        <li key={invite.id} className="group flex items-center gap-3 px-4 py-3">
                                            <div className="min-w-0 flex-1">
                                                <div className="flex items-center gap-2">
                                                    <span className="truncate text-sm font-medium text-foreground">
                                                        {invite.email}
                                                    </span>
                                                    <RoleBadge role={invite.role} label={roleLabel(invite.role)} />
                                                </div>
                                                <p className="text-xs text-muted-foreground">
                                                    {t("expires", { date: invite.expiresAt.slice(0, 10) })}
                                                </p>
                                            </div>
                                            {busy ? (
                                                <Loader2Icon className="size-4 animate-spin text-muted-foreground" />
                                            ) : (
                                                <DropdownMenu>
                                                    <DropdownMenuTrigger asChild>
                                                        <button
                                                            type="button"
                                                            aria-label={t("inviteActions")}
                                                            className={rowActionTrigger}
                                                        >
                                                            <EllipsisHorizontalIcon className="size-5" />
                                                        </button>
                                                    </DropdownMenuTrigger>
                                                    <DropdownMenuContent align="end" className="w-44">
                                                        <DropdownMenuItem onSelect={() => copyInviteLink(invite.token)}>
                                                            <LinkIcon className="size-4" />
                                                            {t("copyLink")}
                                                        </DropdownMenuItem>
                                                        <DropdownMenuItem
                                                            variant="destructive"
                                                            onSelect={() => revoke(invite.id)}
                                                        >
                                                            <TrashIcon className="size-4" />
                                                            {t("revoke")}
                                                        </DropdownMenuItem>
                                                    </DropdownMenuContent>
                                                </DropdownMenu>
                                            )}
                                        </li>
                                    );
                                })}
                            </ListCard>
                        )}
                    </div>

                    <div className="space-y-4 pt-2">
                        <div>
                            <SectionHeader title={t("linkTitle")} />
                            <p className="px-6 text-sm text-muted-foreground">{t("linkSubtitle")}</p>
                        </div>

                        <form
                            onSubmit={(e) => {
                                e.preventDefault();
                                createLink();
                            }}
                            className="flex flex-col gap-3 sm:flex-row sm:items-start"
                        >
                            <Select value={linkRole} onValueChange={(v) => setLinkRole(v as WorkspaceRole)}>
                                <SelectTrigger className="w-full sm:w-36" aria-label={t("roleLabel")}>
                                    <SelectValue />
                                </SelectTrigger>
                                <SelectContent align="start">
                                    {selectableRoles.map((r) => (
                                        <SelectItem key={r} value={r}>
                                            {roleLabel(r)}
                                        </SelectItem>
                                    ))}
                                </SelectContent>
                            </Select>
                            <Input
                                type="number"
                                min={1}
                                inputMode="numeric"
                                value={linkExpiry}
                                onChange={(e) => setLinkExpiry(e.target.value)}
                                placeholder={t("linkExpiryPlaceholder")}
                                aria-label={t("linkExpiryLabel")}
                                className="w-full sm:flex-1"
                            />
                            <Input
                                type="number"
                                min={1}
                                inputMode="numeric"
                                value={linkMaxUses}
                                onChange={(e) => setLinkMaxUses(e.target.value)}
                                placeholder={t("linkMaxUsesPlaceholder")}
                                aria-label={t("linkMaxUsesLabel")}
                                className="w-full sm:flex-1"
                            />
                            <Button
                                type="submit"
                                disabled={creatingLink}
                                className="min-w-28 bg-brand text-white hover:bg-brand-hover"
                            >
                                {creatingLink ? <Loader2Icon className="size-4 animate-spin" /> : t("createLink")}
                            </Button>
                        </form>

                        <div className="space-y-2 pt-2">
                            <SectionHeader title={t("activeLinksTitle")} />
                            {inviteLinks.length === 0 ? (
                                <EmptyRow>{t("activeLinksEmpty")}</EmptyRow>
                            ) : (
                                <ListCard>
                                    {inviteLinks.map((link) => {
                                        const busy = busyLinkId === link.id;
                                        const copied = copiedLinkId === link.id;
                                        return (
                                            <li key={link.id} className="group flex items-center gap-3 px-4 py-3">
                                                <span
                                                    aria-hidden
                                                    className="grid size-8 shrink-0 place-items-center rounded-full bg-muted text-muted-foreground"
                                                >
                                                    <LinkIcon className="size-4" />
                                                </span>
                                                <div className="min-w-0 flex-1">
                                                    <div className="flex items-center gap-2">
                                                        <RoleBadge role={link.role} label={roleLabel(link.role)} />
                                                        <span className="text-xs text-muted-foreground tabular-nums">
                                                            {link.maxUses != null
                                                                ? t("linkUsesOf", { used: link.usedCount, max: link.maxUses })
                                                                : t("linkUses", { count: link.usedCount })}
                                                        </span>
                                                    </div>
                                                    <p className="text-xs text-muted-foreground">
                                                        {t("expires", { date: link.expiresAt.slice(0, 10) })}
                                                    </p>
                                                </div>
                                                <Button
                                                    type="button"
                                                    variant="outline"
                                                    size="sm"
                                                    onClick={() => copyShareLink(link)}
                                                    aria-label={t("copyLink")}
                                                >
                                                    {copied ? (
                                                        <CheckIcon className="size-4" />
                                                    ) : (
                                                        <LinkIcon className="size-4" />
                                                    )}
                                                    {copied ? t("linkCopied") : t("copyLink")}
                                                </Button>
                                                {busy ? (
                                                    <Loader2Icon className="size-4 animate-spin text-muted-foreground" />
                                                ) : (
                                                    <DropdownMenu>
                                                        <DropdownMenuTrigger asChild>
                                                            <button
                                                                type="button"
                                                                aria-label={t("linkActions")}
                                                                className={rowActionTrigger}
                                                            >
                                                                <EllipsisHorizontalIcon className="size-5" />
                                                            </button>
                                                        </DropdownMenuTrigger>
                                                        <DropdownMenuContent align="end" className="w-44">
                                                            <DropdownMenuItem
                                                                variant="destructive"
                                                                onSelect={() => revokeLink(link.id)}
                                                            >
                                                                <TrashIcon className="size-4" />
                                                                {t("revoke")}
                                                            </DropdownMenuItem>
                                                        </DropdownMenuContent>
                                                    </DropdownMenu>
                                                )}
                                            </li>
                                        );
                                    })}
                                </ListCard>
                            )}
                        </div>
                    </div>

                    <div className="space-y-4 pt-2">
                        <div>
                            <SectionHeader title={t("domainsTitle")} />
                            <p className="px-6 text-sm text-muted-foreground">{t("domainsSubtitle")}</p>
                        </div>

                        <form
                            onSubmit={(e) => {
                                e.preventDefault();
                                addDomain();
                            }}
                            className="flex flex-col gap-3 sm:flex-row sm:items-start"
                        >
                            <div className="flex-1">
                                <InputGroup>
                                    <InputGroupAddon>
                                        <AtSymbolIcon />
                                    </InputGroupAddon>
                                    <InputGroupInput
                                        value={domainInput}
                                        onChange={(e) => {
                                            setDomainInput(e.target.value);
                                            clearError("domain");
                                        }}
                                        placeholder={t("domainPlaceholder")}
                                        aria-label={t("domainLabel")}
                                        aria-invalid={Boolean(fieldErrors.domain)}
                                    />
                                </InputGroup>
                                {fieldErrors.domain && (
                                    <p className="mt-1.5 text-sm text-destructive">{fieldErrors.domain}</p>
                                )}
                            </div>
                            <Button
                                type="submit"
                                disabled={addingDomain || domainInput.trim().length === 0}
                                className="min-w-28 bg-brand text-white hover:bg-brand-hover"
                            >
                                {addingDomain ? <Loader2Icon className="size-4 animate-spin" /> : t("addDomain")}
                            </Button>
                        </form>

                        {allowedDomains.length === 0 ? (
                            <EmptyRow>{t("domainsEmpty")}</EmptyRow>
                        ) : (
                            <ListCard>
                                {allowedDomains.map((domain) => {
                                    const busy = busyDomain === domain;
                                    return (
                                        <li key={domain} className="group flex items-center gap-3 px-4 py-3">
                                            <span
                                                aria-hidden
                                                className="grid size-8 shrink-0 place-items-center rounded-full bg-muted text-muted-foreground"
                                            >
                                                <GlobeAltIcon className="size-4" />
                                            </span>
                                            <span className="min-w-0 flex-1 truncate text-sm font-medium text-foreground">
                                                {domain}
                                            </span>
                                            {busy ? (
                                                <Loader2Icon className="size-4 animate-spin text-muted-foreground" />
                                            ) : (
                                                <DropdownMenu>
                                                    <DropdownMenuTrigger asChild>
                                                        <button
                                                            type="button"
                                                            aria-label={t("removeDomain")}
                                                            className={rowActionTrigger}
                                                        >
                                                            <EllipsisHorizontalIcon className="size-5" />
                                                        </button>
                                                    </DropdownMenuTrigger>
                                                    <DropdownMenuContent align="end" className="w-44">
                                                        <DropdownMenuItem
                                                            variant="destructive"
                                                            onSelect={() => removeDomain(domain)}
                                                        >
                                                            <TrashIcon className="size-4" />
                                                            {t("removeDomain")}
                                                        </DropdownMenuItem>
                                                    </DropdownMenuContent>
                                                </DropdownMenu>
                                            )}
                                        </li>
                                    );
                                })}
                            </ListCard>
                        )}
                    </div>
                </Rise>
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

function MemberSkeleton() {
    return (
        <ul className="divide-y divide-border overflow-hidden rounded-2xl border border-border bg-card">
            {[0, 1, 2].map((i) => (
                <li key={i} className="flex items-center gap-3 px-4 py-3">
                    <Skeleton className="size-8 shrink-0 rounded-full" />
                    <div className="flex-1 space-y-2">
                        <Skeleton className="h-3.5 w-32" />
                        <Skeleton className="h-3 w-48" />
                    </div>
                    <Skeleton className="h-5 w-16 rounded-full" />
                </li>
            ))}
        </ul>
    );
}
