"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { useTranslations } from "next-intl";
import { Loader2Icon } from "lucide-react";
import {
    ArrowUturnLeftIcon,
    CheckIcon,
    EllipsisHorizontalIcon,
    EnvelopeIcon,
    GlobeAltIcon,
    KeyIcon,
    LinkIcon,
    MagnifyingGlassIcon,
    TrashIcon,
} from "@heroicons/react/24/outline";

import type {
    CustomRole,
    OwnershipOffer,
    WorkspaceInvite,
    WorkspaceInviteLink,
    WorkspaceMember,
    WorkspaceRole,
} from "@/app/lib/types";
import {
    assignMemberCustomRole,
    createWorkspaceInvite,
    createWorkspaceInviteLink,
    getBuiltInRoles,
    getWorkspaceInviteLinks,
    getWorkspaceInvites,
    getWorkspaceMembers,
    getWorkspaceOwnershipOffers,
    getWorkspaceRoles,
    isOwnershipOfferCreated,
    issueWorkspaceMfaAttestation,
    removeWorkspaceMember,
    revokeWorkspaceInvite,
    revokeWorkspaceInviteLink,
    revokeWorkspaceMfaAttestation,
    revokeWorkspaceOwnershipOffer,
    updateMemberRole,
} from "@/app/lib/api";
import { useWorkspace } from "@/app/hooks/useWorkspace";
import { useGrantedPermissions, usePermission } from "@/app/hooks/usePermissions";
import { usePasskeyStepUpErrorHandler } from "@/app/hooks/usePasskeyStepUpError";
import { useFieldErrors } from "@/app/hooks/useFieldErrors";
import { useApiErrorToast } from "@/app/hooks/useApiErrorToast";
import { useScopeOwnershipOffers } from "@/app/hooks/useScopeOwnershipOffers";
import { useConfinementAcknowledgement } from "@/app/hooks/useConfinementAcknowledgement";
import { toastError, toastSuccess } from "@/app/lib/toast";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
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
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import {
    Dialog, DialogClose, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from "@/components/ui/dialog";
import PermissionsUnavailable from "@/app/components/PermissionsUnavailable";
import Rise from "@/app/components/motion/Rise";
import AllowedDomainsPanel from "@/app/components/settings/AllowedDomainsPanel";
import ConfinementAcknowledgementDialog from "@/app/components/settings/ConfinementAcknowledgementDialog";
import PasskeyApprovalCodeDialog from "@/app/components/settings/PasskeyApprovalCodeDialog";
import { SettingsSection } from "@/app/components/settings/SettingsSection";
import WorkspaceUnavailableRetry from "@/app/components/WorkspaceUnavailableRetry";
import {
    EmptyRow,
    ListCard,
    TabListHeading,
    rowActionTrigger,
} from "@/app/components/settings/SettingsListPrimitives";

const SEARCH_THRESHOLD = 6;

type RoleOptionsLoadState = {
    workspaceId: number | null;
    status: "loading" | "ready" | "error";
    roles: CustomRole[];
};

function initial(name: string) {
    return name.trim().charAt(0).toUpperCase() || "?";
}

function isWorkspaceRole(role: string): role is WorkspaceRole {
    return role === "member" || role === "admin" || role === "owner";
}

function RoleBadge({ role, label }: { role: string; label: string }) {
    return role === "owner" ? (
        <Badge className="border-transparent bg-brand-light text-brand-text">{label}</Badge>
    ) : (
        <Badge variant="secondary">{label}</Badge>
    );
}

function RoleOptionsUnavailable({ onRetry }: { onRetry: () => Promise<void> }) {
    const t = useTranslations("WorkspaceMembers");
    const tRetry = useTranslations("CapabilityUnavailable");
    return (
        <PermissionsUnavailable
            variant="inline"
            title={t("roleOptionsLoadFailedTitle")}
            body={t("roleOptionsLoadFailedBody")}
            action={(
                <WorkspaceUnavailableRetry
                    label={tRetry("retry")}
                    pendingLabel={tRetry("retrying")}
                    onRetry={onRetry}
                    variant="outline"
                    size="inline"
                />
            )}
        />
    );
}

function InviteRoleOptionsSkeleton() {
    return (
        <div className="flex flex-col gap-3 sm:flex-row" aria-hidden="true">
            <Skeleton className="h-9 flex-1 rounded-full" />
            <Skeleton className="h-9 w-full rounded-full sm:w-36" />
            <Skeleton className="h-9 w-full rounded-full sm:w-28" />
        </div>
    );
}

/**
 * How the panel presents its invite journey, so one component serves both of its homes while
 * #1340 migrates the workspace destinations.
 *
 * - `legacy` is `/settings/members` exactly as it ships: allowed domains is the third tab of the
 *   invite strip, under the section's own "Invite & access" name.
 * - `consolidated` is the People & access page, where allowed domains is its own deep-linkable
 *   section and the strip is left holding only the two ways of inviting a member.
 */
export type MembersPresentation = "legacy" | "consolidated";

type MembersPanelProps = {
    currentUserId: number | null;
    presentation?: MembersPresentation;
};

/**
 * Workspace membership administration: roles, invites, invite links and allowed domains.
 *
 * A role change is refreshed from the server rather than only patched into local state. A member
 * manager can change their own row here, and the app shell resolves the viewer's effective
 * permissions once per server render — so without the refresh they would keep the permission
 * gates and navigation of the role they just left until a full page load.
 *
 * @param currentUserId the viewer, so their own row can be handled differently
 * @param presentation which of the panel's two homes is rendering it; defaults to the legacy route
 */
export default function MembersPanel({
    currentUserId,
    presentation = "legacy",
}: MembersPanelProps) {
    const { activeWorkspaceId: workspaceId } = useWorkspace();
    if (!workspaceId) return null;
    return (
        <MembersWorkspacePanel
            key={workspaceId}
            workspaceId={workspaceId}
            currentUserId={currentUserId}
            presentation={presentation}
        />
    );
}

function MembersWorkspacePanel({
    workspaceId,
    currentUserId,
    presentation,
}: Omit<MembersPanelProps, "presentation"> & {
    workspaceId: number;
    presentation: MembersPresentation;
}) {
    const t = useTranslations("WorkspaceMembers");
    const showApiError = useApiErrorToast("WorkspaceMembers");
    const handlePasskeyStepUpError = usePasskeyStepUpErrorHandler();
    const router = useRouter();
    const { activeWorkspace } = useWorkspace();
    const role = activeWorkspace?.role;
    const canManageMembers = usePermission("MEMBER_MANAGE");
    const canManageRoles = usePermission("ROLE_MANAGE");
    const grantedPermissions = useGrantedPermissions();
    const isOwner = role === "owner";

    const [members, setMembers] = useState<WorkspaceMember[]>([]);
    const [invites, setInvites] = useState<WorkspaceInvite[]>([]);
    const [customRoles, setCustomRoles] = useState<CustomRole[]>([]);
    const [roleOptionsLoadState, setRoleOptionsLoadState] = useState<RoleOptionsLoadState>({
        workspaceId: null,
        status: "loading",
        roles: [],
    });
    const roleOptionsRetry = useRef<Promise<void> | null>(null);
    const [loading, setLoading] = useState(true);
    const [memberSearch, setMemberSearch] = useState("");

    const [inviteEmail, setInviteEmail] = useState("");
    const [inviteRole, setInviteRole] = useState<WorkspaceRole>("member");
    const [sending, setSending] = useState(false);
    const [invitationTab, setInvitationTab] = useState("email");
    const inviteLinkTab = useRef<HTMLButtonElement>(null);
    const [copyRecoveryUrl, setCopyRecoveryUrl] = useState<string | null>(null);
    const [mailOutcome, setMailOutcome] = useState<string | null>(null);
    const { fieldErrors, reset: resetFieldErrors, clearError, captureFieldErrors } = useFieldErrors();

    const [busyMemberId, setBusyMemberId] = useState<number | null>(null);
    const [removeTarget, setRemoveTarget] = useState<WorkspaceMember | null>(null);
    const [isRemoving, setIsRemoving] = useState(false);
    const [approvalTarget, setApprovalTarget] = useState<WorkspaceMember | null>(null);
    const [busyInviteId, setBusyInviteId] = useState<string | null>(null);

    const [inviteLinks, setInviteLinks] = useState<WorkspaceInviteLink[]>([]);
    const [linkRole, setLinkRole] = useState<WorkspaceRole>("member");
    const [linkExpiry, setLinkExpiry] = useState("");
    const [linkMaxUses, setLinkMaxUses] = useState("");
    const [creatingLink, setCreatingLink] = useState(false);
    const [busyLinkId, setBusyLinkId] = useState<number | null>(null);
    const [copiedLinkId, setCopiedLinkId] = useState<number | null>(null);

    const showDomains = presentation === "legacy";

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
                if (canManageMembers) {
                    const loadedInvites = await getWorkspaceInvites(workspaceId);
                    if (!cancelled) setInvites(loadedInvites);
                    const loadedLinks = await getWorkspaceInviteLinks(workspaceId);
                    if (!cancelled) setInviteLinks(loadedLinks);
                }
                if (canManageRoles) {
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
    }, [workspaceId, canManageMembers, canManageRoles, t]);

    const membersRefresh = useRef(0);
    const refreshMembers = useCallback(async () => {
        if (!workspaceId) return;
        membersRefresh.current += 1;
        const refreshId = membersRefresh.current;
        try {
            const loaded = await getWorkspaceMembers(workspaceId);
            if (refreshId === membersRefresh.current) setMembers(loaded);
        } catch (err) {
            if (refreshId === membersRefresh.current) {
                showApiError(err, "membersRefreshFailed", "membersRefreshFailedDescription");
            }
        }
    }, [showApiError, workspaceId]);

    useEffect(() => {
        const generation = membersRefresh;
        return () => {
            generation.current += 1;
        };
    }, [workspaceId]);

    useEffect(() => {
        if (!workspaceId || (!canManageMembers && !canManageRoles)) return;
        let cancelled = false;
        void getBuiltInRoles(workspaceId).then((roles) => {
            if (!cancelled) setRoleOptionsLoadState({ workspaceId, status: "ready", roles });
        }).catch(() => {
            if (!cancelled) setRoleOptionsLoadState({ workspaceId, status: "error", roles: [] });
        });
        return () => {
            cancelled = true;
        };
    }, [workspaceId, canManageMembers, canManageRoles]);

    const retryRoleOptions = useCallback(() => {
        if (roleOptionsRetry.current) return roleOptionsRetry.current;
        const requestedWorkspaceId = workspaceId;
        const request = getBuiltInRoles(requestedWorkspaceId).then((roles) => {
            setRoleOptionsLoadState((current) => current.workspaceId === requestedWorkspaceId
                ? { workspaceId: requestedWorkspaceId, status: "ready", roles }
                : current);
        }).catch(() => {
            setRoleOptionsLoadState((current) => current.workspaceId === requestedWorkspaceId
                ? { workspaceId: requestedWorkspaceId, status: "error", roles: [] }
                : current);
        });
        roleOptionsRetry.current = request;
        void request.finally(() => {
            if (roleOptionsRetry.current === request) roleOptionsRetry.current = null;
        });
        return request;
    }, [workspaceId]);

    const ownershipOffers = useScopeOwnershipOffers(
        workspaceId,
        isOwner,
        getWorkspaceOwnershipOffers,
        (err) => showApiError(err, "ownershipOffersLoadFailed"),
    );
    const { openOfferByOfferee } = ownershipOffers;
    const confinement = useConfinementAcknowledgement();

    const changeRole = async (userId: number, next: WorkspaceRole) => {
        if (!workspaceId) return;
        setBusyMemberId(userId);
        try {
            const name = members.find((m) => m.id === userId)?.displayName ?? "";
            const updated = await confinement.run(
                t("confinementSubjectRole", { name, role: roleLabel(next) }),
                (acknowledge) => updateMemberRole(workspaceId, userId, next, acknowledge),
            );
            if (updated === null) return;
            if (isOwnershipOfferCreated<WorkspaceMember>(updated)) {
                const { offer, currentMember } = updated;
                if (currentMember) {
                    setMembers((prev) => prev.map((m) => (m.id === userId ? currentMember : m)));
                }
                void refreshMembers();
                ownershipOffers.remember(offer);
                toastSuccess(t("ownershipOffered", { name }));
                return;
            }
            setMembers((prev) => prev.map((m) => (m.id === userId ? updated : m)));
            void refreshMembers();
            ownershipOffers.refresh();
            router.refresh();
            toastSuccess(t("roleChanged"));
        } catch (err) {
            if (!handlePasskeyStepUpError(err)) {
                showApiError(err, "roleChangeFailed");
            }
        } finally {
            setBusyMemberId(null);
        }
    };

    const withdrawOwnershipOffer = async (offer: OwnershipOffer) => {
        if (!workspaceId) return;
        setBusyMemberId(offer.offereeUserId);
        try {
            await revokeWorkspaceOwnershipOffer(workspaceId, offer.id);
            ownershipOffers.forget(offer.id);
            toastSuccess(t("ownershipOfferWithdrawn"));
        } catch (err) {
            if (!handlePasskeyStepUpError(err)) {
                showApiError(err, "ownershipOfferWithdrawFailed");
            }
        } finally {
            setBusyMemberId(null);
        }
    };

    const assignCustom = async (userId: number, roleId: number) => {
        if (!workspaceId) return;
        setBusyMemberId(userId);
        try {
            const name = members.find((m) => m.id === userId)?.displayName ?? "";
            const role = customRoles.find((candidate) => candidate.id === roleId)?.name ?? "";
            const updated = await confinement.run(
                t("confinementSubjectRole", { name, role }),
                (acknowledge) => assignMemberCustomRole(workspaceId, userId, roleId, acknowledge),
            );
            if (updated === null) return;
            setMembers((prev) => prev.map((m) => (m.id === userId ? updated : m)));
            void refreshMembers();
            ownershipOffers.refresh();
            router.refresh();
            toastSuccess(t("roleChanged"));
        } catch (err) {
            if (!handlePasskeyStepUpError(err)) {
                showApiError(err, "roleChangeFailed");
            }
        } finally {
            setBusyMemberId(null);
        }
    };

    const confirmRemove = async () => {
        if (!workspaceId || !removeTarget) return;
        setIsRemoving(true);
        try {
            await removeWorkspaceMember(workspaceId, removeTarget.id);
            membersRefresh.current += 1;
            setMembers((prev) => prev.filter((m) => m.id !== removeTarget.id));
            ownershipOffers.refresh();
            toastSuccess(t("removed"), { description: t("removedDescription") });
            setRemoveTarget(null);
        } catch (err) {
            if (!handlePasskeyStepUpError(err)) {
                showApiError(err, "removeFailed");
            }
        } finally {
            setIsRemoving(false);
        }
    };

    const copyUrl = useCallback(async (url: string, silent = false): Promise<boolean> => {
        try {
            await navigator.clipboard.writeText(url);
            setCopyRecoveryUrl(null);
            if (!silent) toastSuccess(t("linkCopied"));
            return true;
        } catch {
            setCopyRecoveryUrl(url);
            toastError(t("copyFailed"), { description: t("copyFailedDescription") });
            return false;
        }
    }, [t]);

    const copyInviteLink = useCallback(
        (token: string, silent = false) => copyUrl(`${window.location.origin}/invite#token=${token}`, silent),
        [copyUrl],
    );

    const sendInvite = async (email = inviteEmail.trim(), requestedRole = inviteRole) => {
        if (!workspaceId || sending) return;
        resetFieldErrors();
        setMailOutcome(null);
        setCopyRecoveryUrl(null);
        setSending(true);
        try {
            const result = await createWorkspaceInvite(workspaceId, email, requestedRole);
            setInviteEmail("");
            if (result.invite) {
                const invite = result.invite;
                setMailOutcome(t(result.emailDelivery === "ACCEPTED" ? "emailAccepted"
                    : result.emailDelivery === "SKIPPED" ? "emailSkipped"
                        : result.emailDelivery === "FAILED" ? "emailFailed" : "emailUnconfirmed"));
                setInvites((prev) => [invite, ...prev.filter((i) => i.email !== invite.email)]);
                if (invite.token) {
                    await copyInviteLink(invite.token, true);
                }
                toastSuccess(t("invitationCreated"));
            } else {
                toastError(t("inviteFailed"));
            }
        } catch (err) {
            if (!handlePasskeyStepUpError(err) && !captureFieldErrors(err)) {
                showApiError(err, "inviteFailed");
            }
        } finally {
            setSending(false);
        }
    };

    const revoke = async (inviteId: string) => {
        if (!workspaceId) return;
        setBusyInviteId(inviteId);
        try {
            await revokeWorkspaceInvite(workspaceId, inviteId);
            setInvites((prev) => prev.filter((i) => i.id !== inviteId));
            setCopyRecoveryUrl(null);
            toastSuccess(t("revoked"));
        } catch (err) {
            if (!handlePasskeyStepUpError(err)) {
                showApiError(err, "revokeFailed");
            }
        } finally {
            setBusyInviteId(null);
        }
    };

    const copyShareLink = useCallback(
        async (link: WorkspaceInviteLink) => {
            if (!link.token) return;
            const copied = await copyUrl(`${window.location.origin}/invite-link#token=${link.token}`, true);
            if (copied) {
                setCopiedLinkId(link.id);
                window.setTimeout(() => {
                    setCopiedLinkId((current) => (current === link.id ? null : current));
                }, 1500);
            }
        },
        [copyUrl],
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
            if (!handlePasskeyStepUpError(err)) {
                showApiError(err, "linkCreateFailed");
            }
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
            setCopyRecoveryUrl(null);
            toastSuccess(t("linkRevoked"));
        } catch (err) {
            if (!handlePasskeyStepUpError(err)) {
                showApiError(err, "linkRevokeFailed");
            }
        } finally {
            setBusyLinkId(null);
        }
    };

    const roleOptionsStatus = roleOptionsLoadState.workspaceId === workspaceId
        ? roleOptionsLoadState.status
        : "loading";
    const builtInRoleDefinitions = useMemo(
        () => roleOptionsLoadState.workspaceId === workspaceId && roleOptionsLoadState.status === "ready"
            ? roleOptionsLoadState.roles
            : [],
        [roleOptionsLoadState, workspaceId],
    );
    const grantableBuiltInRoles = useMemo(() => {
        const roles: WorkspaceRole[] = [];
        for (const candidate of builtInRoleDefinitions) {
            if (!candidate.permissions.every((permission) => grantedPermissions.has(permission))) continue;
            if (!isWorkspaceRole(candidate.name)) continue;
            if (candidate.name === "owner" && !isOwner) continue;
            roles.push(candidate.name);
        }
        return roles;
    }, [builtInRoleDefinitions, grantedPermissions, isOwner]);
    const grantableBuiltInRoleSet = useMemo(
        () => new Set(grantableBuiltInRoles),
        [grantableBuiltInRoles],
    );
    const grantableInviteRoles = useMemo<WorkspaceRole[]>(
        () => grantableBuiltInRoles.filter((candidate) => candidate !== "owner"),
        [grantableBuiltInRoles],
    );
    const grantableCustomRoles = useMemo(
        () => customRoles.filter((candidate) =>
            candidate.permissions.every((permission) => grantedPermissions.has(permission))),
        [customRoles, grantedPermissions],
    );

    const trimmedSearch = memberSearch.trim().toLowerCase();
    const showSearch = !loading && members.length > SEARCH_THRESHOLD;
    const visibleMembers = useMemo(() => {
        if (!showSearch || !trimmedSearch) return members;
        return members.filter(
            (m) =>
                m.displayName.toLowerCase().includes(trimmedSearch) ||
                m.email.toLowerCase().includes(trimmedSearch),
        );
    }, [members, trimmedSearch, showSearch]);

    return (
        <div className="space-y-12">
            <Rise>
                <SettingsSection
                    title={t("title")}
                    description={t("subtitle", { workspace: activeWorkspace?.name ?? "" })}
                    action={
                        !loading ? (
                            <Badge variant="secondary" className="tabular-nums">
                                {t("count", { count: members.length })}
                            </Badge>
                        ) : null
                    }
                >
                    {showSearch && (
                        <InputGroup className="max-w-xs">
                            <InputGroupAddon>
                                <MagnifyingGlassIcon />
                            </InputGroupAddon>
                            <InputGroupInput
                                value={memberSearch}
                                onChange={(e) => setMemberSearch(e.target.value)}
                                placeholder={t("searchPlaceholder")}
                                aria-label={t("searchPlaceholder")}
                            />
                        </InputGroup>
                    )}

                    {loading ? (
                        <MemberSkeleton />
                    ) : members.length === 0 ? (
                        <EmptyRow>{t("membersEmpty")}</EmptyRow>
                    ) : visibleMembers.length === 0 ? (
                        <EmptyRow>{t("searchNoMatches", { query: memberSearch.trim() })}</EmptyRow>
                    ) : (
                        <ListCard>
                            {visibleMembers.map((member) => {
                                const isSelf = member.id === currentUserId;
                                const busy = busyMemberId === member.id;
                                const pending = member.status === "pending";
                                const targetIsOwner = member.builtInRole === "owner";
                                const protectedOwner = targetIsOwner && !isOwner;
                                const editable = !protectedOwner
                                    && (
                                        (canManageMembers && grantableBuiltInRoles.length > 0)
                                        || (canManageRoles && grantableCustomRoles.length > 0)
                                    );
                                const removable = canManageMembers && !isSelf && !protectedOwner;
                                const openOffer = openOfferByOfferee.get(member.id);
                                const withdrawable = isOwner && openOffer !== undefined;
                                const currentCustomRoleIsGrantable = member.roleId != null
                                    && grantableCustomRoles.some((candidate) => candidate.id === member.roleId);
                                const currentBuiltInRole = member.roleId == null && isWorkspaceRole(member.role)
                                    ? member.role
                                    : null;
                                const currentBuiltInRoleIsGrantable = currentBuiltInRole != null
                                    && grantableBuiltInRoleSet.has(currentBuiltInRole);
                                const attestable = !isSelf && !pending && !protectedOwner && (
                                    member.roleId != null
                                        ? canManageRoles && currentCustomRoleIsGrantable
                                        : canManageMembers && currentBuiltInRoleIsGrantable
                                );
                                return (
                                    <li key={member.id} className="group flex items-center gap-3 px-4 py-3">
                                        <Avatar>
                                            {member.profilePictureUrl && (
                                                <AvatarImage src={member.profilePictureUrl} alt={member.displayName} />
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
                                                {openOffer && (
                                                    <Badge className="border-transparent bg-warmth-cool/15 text-foreground">
                                                        {t("ownershipPending")}
                                                    </Badge>
                                                )}
                                                {member.passkeyApprovalNeeded === true && (
                                                    <Badge
                                                        variant="outline"
                                                        className="border-warning/40 bg-warning/10 text-warning-foreground"
                                                        title={t("passkeyNotApprovedHint")}
                                                    >
                                                        {t("passkeyNotApproved")}
                                                    </Badge>
                                                )}
                                            </div>
                                            <p className="truncate text-xs text-muted-foreground">{member.email}</p>
                                        </div>

                                        <div className="flex items-center gap-2">
                                            {editable ? (
                                                <Select
                                                    value={member.roleId ? `custom:${member.roleId}` : member.role}
                                                    disabled={busy}
                                                    onValueChange={(value) => {
                                                        if (value.startsWith("custom:")) {
                                                            assignCustom(member.id, Number(value.slice(7)));
                                                        } else if (isWorkspaceRole(value)) {
                                                            changeRole(member.id, value);
                                                        }
                                                    }}
                                                >
                                                    <SelectTrigger
                                                        size="sm"
                                                        className="w-auto"
                                                        aria-label={t("roleLabel")}
                                                    >
                                                        <SelectValue />
                                                    </SelectTrigger>
                                                    <SelectContent align="end">
                                                        {member.roleId != null && !currentCustomRoleIsGrantable ? (
                                                            <SelectItem value={`custom:${member.roleId}`} disabled>
                                                                {member.role}
                                                            </SelectItem>
                                                        ) : null}
                                                        {currentBuiltInRole != null && !currentBuiltInRoleIsGrantable ? (
                                                            <SelectItem value={currentBuiltInRole} disabled>
                                                                {roleLabel(currentBuiltInRole)}
                                                            </SelectItem>
                                                        ) : null}
                                                        {canManageMembers && grantableBuiltInRoles.map((candidate) => (
                                                            <SelectItem key={candidate} value={candidate}>
                                                                {roleLabel(candidate)}
                                                            </SelectItem>
                                                        ))}
                                                        {canManageRoles && grantableCustomRoles.length > 0 && (
                                                            <SelectGroup>
                                                                <SelectLabel>{t("customRoles")}</SelectLabel>
                                                                {grantableCustomRoles.map((candidate) => (
                                                                    <SelectItem
                                                                        key={candidate.id}
                                                                        value={`custom:${candidate.id}`}
                                                                    >
                                                                        {candidate.name}
                                                                    </SelectItem>
                                                                ))}
                                                            </SelectGroup>
                                                        )}
                                                    </SelectContent>
                                                </Select>
                                            ) : (
                                                <RoleBadge role={member.role} label={roleLabel(member.role)} />
                                            )}
                                            {member.roleId != null && targetIsOwner ? (
                                                <RoleBadge role="owner" label={roleLabel("owner")} />
                                            ) : null}
                                        </div>

                                        {(removable || attestable || withdrawable) && (
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
                                                <DropdownMenuContent align="end" className="w-56">
                                                    {attestable && (
                                                        <DropdownMenuItem onSelect={() => setApprovalTarget(member)}>
                                                            <KeyIcon className="size-4" />
                                                            {t("approvalCode")}
                                                        </DropdownMenuItem>
                                                    )}
                                                    {withdrawable && openOffer && (
                                                        <DropdownMenuItem
                                                            disabled={busy}
                                                            onSelect={() => withdrawOwnershipOffer(openOffer)}
                                                        >
                                                            <ArrowUturnLeftIcon className="size-4" />
                                                            {t("withdrawOwnershipOffer")}
                                                        </DropdownMenuItem>
                                                    )}
                                                    {removable && (
                                                        <DropdownMenuItem
                                                            variant="destructive"
                                                            onSelect={() => setRemoveTarget(member)}
                                                        >
                                                            <TrashIcon className="size-4" />
                                                            {t("remove")}
                                                        </DropdownMenuItem>
                                                    )}
                                                </DropdownMenuContent>
                                            </DropdownMenu>
                                        )}
                                    </li>
                                );
                            })}
                        </ListCard>
                    )}
                    {roleOptionsStatus === "error" && (canManageMembers || canManageRoles) ? (
                        <RoleOptionsUnavailable onRetry={retryRoleOptions} />
                    ) : null}
                </SettingsSection>
            </Rise>

            {canManageMembers && (
                <Rise>
                    <SettingsSection
                        title={showDomains ? t("inviteAccessTitle") : t("inviteMemberTitle")}
                        description={showDomains ? t("inviteAccessSubtitle") : t("inviteMemberSubtitle")}
                    >
                        <Tabs value={invitationTab} onValueChange={setInvitationTab} className="gap-5">
                            <TabsList className="w-full sm:w-auto">
                                <TabsTrigger value="email">
                                    <EnvelopeIcon />
                                    {t("inviteTabEmail")}
                                </TabsTrigger>
                                <TabsTrigger ref={inviteLinkTab} value="link">
                                    <LinkIcon />
                                    {t("inviteTabLink")}
                                </TabsTrigger>
                                {showDomains && (
                                    <TabsTrigger value="domains">
                                        <GlobeAltIcon />
                                        {t("inviteTabDomains")}
                                    </TabsTrigger>
                                )}
                            </TabsList>

                            {copyRecoveryUrl ? (
                                <Alert role="region" aria-label={t("copyFailed")}>
                                    <AlertTitle>{t("copyFailed")}</AlertTitle>
                                    <AlertDescription className="gap-3">
                                        <p>{t("copyFailedDescription")}</p>
                                        <Input aria-label={t("inviteUrl")} readOnly value={copyRecoveryUrl} onFocus={(event) => event.currentTarget.select()} />
                                        <div className="flex flex-wrap gap-2">
                                            <Button variant="outline" onClick={() => setCopyRecoveryUrl(null)}>{t("close")}</Button>
                                            <Button onClick={() => { void copyUrl(copyRecoveryUrl); }}>{t("copyLink")}</Button>
                                        </div>
                                    </AlertDescription>
                                </Alert>
                            ) : null}

                            <TabsContent value="email" className="space-y-5">
                                <p className="max-w-prose text-sm text-muted-foreground">{t("inviteSubtitle")}</p>
                                {roleOptionsStatus === "loading" ? (
                                    <InviteRoleOptionsSkeleton />
                                ) : roleOptionsStatus === "error" ? (
                                    <RoleOptionsUnavailable onRetry={retryRoleOptions} />
                                ) : grantableInviteRoles.length === 0 ? (
                                    <PermissionsUnavailable
                                        variant="inline"
                                        title={t("inviteRoleUnavailableTitle")}
                                        body={t("inviteRoleUnavailableBody")}
                                    />
                                ) : (
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
                                                <p className="mt-1.5 text-sm text-destructive">
                                                    {fieldErrors.email}
                                                </p>
                                            )}
                                        </div>
                                        <Select
                                            value={inviteRole}
                                            disabled={grantableInviteRoles.length === 0}
                                            onValueChange={(value) => {
                                                if (isWorkspaceRole(value) && value !== "owner") setInviteRole(value);
                                            }}
                                        >
                                            <SelectTrigger className="w-full sm:w-36" aria-label={t("roleLabel")}>
                                                <SelectValue />
                                            </SelectTrigger>
                                            <SelectContent align="end">
                                                {!grantableInviteRoles.includes(inviteRole) ? (
                                                    <SelectItem value={inviteRole} disabled>
                                                        {roleLabel(inviteRole)}
                                                    </SelectItem>
                                                ) : null}
                                                {grantableInviteRoles.map((candidate) => (
                                                    <SelectItem key={candidate} value={candidate}>
                                                        {roleLabel(candidate)}
                                                    </SelectItem>
                                                ))}
                                            </SelectContent>
                                        </Select>
                                        <Button
                                            type="submit"
                                            variant="brand"
                                            disabled={
                                                sending
                                                || inviteEmail.trim().length === 0
                                                || !grantableInviteRoles.includes(inviteRole)
                                            }
                                            className="min-w-28"
                                        >
                                            {sending
                                                ? <Loader2Icon className="size-4 animate-spin" />
                                                : t("sendInvite")}
                                        </Button>
                                    </form>
                                )}

                                <p role="status" aria-live="polite" aria-atomic="true" className="max-w-prose text-sm text-muted-foreground">{mailOutcome}</p>
                                <div className="space-y-2">
                                    <TabListHeading title={t("pendingTitle")} count={invites.length} />
                                    <p className="max-w-prose text-sm text-muted-foreground">{t("replaceInviteHint")}</p>
                                    {invites.length === 0 ? (
                                        <EmptyRow>{t("pendingEmpty")}</EmptyRow>
                                    ) : (
                                        <ListCard>
                                            {invites.map((invite) => {
                                                const busy = busyInviteId === invite.id;
                                                return (
                                                    <li
                                                        key={invite.id}
                                                        className="group flex items-center gap-3 px-4 py-3"
                                                    >
                                                        <div className="min-w-0 flex-1">
                                                            <div className="flex items-center gap-2">
                                                                <span className="truncate text-sm font-medium text-foreground">
                                                                    {invite.email}
                                                                </span>
                                                                <RoleBadge
                                                                    role={invite.role}
                                                                    label={roleLabel(invite.role)}
                                                                />
                                                            </div>
                                                            <p className="text-xs text-muted-foreground">
                                                                {invite.expiresAt
                                                                    ? t("expires", { date: invite.expiresAt.slice(0, 10) })
                                                                    : t("inviteNoExpiry")}
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
                                                                    {invite.token ? (
                                                                        <DropdownMenuItem
                                                                            onSelect={() => {
                                                                                if (invite.token) {
                                                                                    void copyInviteLink(invite.token);
                                                                                }
                                                                            }}
                                                                        >
                                                                            <LinkIcon className="size-4" />
                                                                            {t("copyLink")}
                                                                        </DropdownMenuItem>
                                                                    ) : grantableInviteRoles.includes(invite.role) ? (
                                                                        <DropdownMenuItem disabled={sending} onSelect={() => void sendInvite(invite.email, invite.role)}>
                                                                            <EnvelopeIcon className="size-4" />
                                                                            {t("replaceInvite")}
                                                                        </DropdownMenuItem>
                                                                    ) : null}
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
                            </TabsContent>

                            <TabsContent value="link" className="space-y-5">
                                <p className="max-w-prose text-sm text-muted-foreground">{t("linkSubtitle")}</p>
                                <p className="max-w-prose text-sm text-muted-foreground">{t("revokeLinkConsequences")}</p>
                                {roleOptionsStatus === "loading" ? (
                                    <InviteRoleOptionsSkeleton />
                                ) : roleOptionsStatus === "error" ? (
                                    <RoleOptionsUnavailable onRetry={retryRoleOptions} />
                                ) : grantableInviteRoles.length === 0 ? (
                                    <PermissionsUnavailable
                                        variant="inline"
                                        title={t("inviteRoleUnavailableTitle")}
                                        body={t("inviteRoleUnavailableBody")}
                                    />
                                ) : (
                                    <form
                                        onSubmit={(e) => {
                                            e.preventDefault();
                                            createLink();
                                        }}
                                        className="flex flex-col gap-3 sm:flex-row sm:items-start"
                                    >
                                        <Select
                                            value={linkRole}
                                            disabled={grantableInviteRoles.length === 0}
                                            onValueChange={(value) => {
                                                if (isWorkspaceRole(value) && value !== "owner") setLinkRole(value);
                                            }}
                                        >
                                            <SelectTrigger className="w-full sm:w-36" aria-label={t("roleLabel")}>
                                                <SelectValue />
                                            </SelectTrigger>
                                            <SelectContent align="start">
                                                {!grantableInviteRoles.includes(linkRole) ? (
                                                    <SelectItem value={linkRole} disabled>
                                                        {roleLabel(linkRole)}
                                                    </SelectItem>
                                                ) : null}
                                                {grantableInviteRoles.map((candidate) => (
                                                    <SelectItem key={candidate} value={candidate}>
                                                        {roleLabel(candidate)}
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
                                            variant="brand"
                                            disabled={creatingLink || !grantableInviteRoles.includes(linkRole)}
                                            className="min-w-28"
                                        >
                                            {creatingLink ? (
                                                <Loader2Icon className="size-4 animate-spin" />
                                            ) : (
                                                t("createLink")
                                            )}
                                        </Button>
                                    </form>
                                )}

                                <div className="space-y-2">
                                    <TabListHeading title={t("activeLinksTitle")} count={inviteLinks.length} />
                                    {inviteLinks.length === 0 ? (
                                        <EmptyRow>{t("activeLinksEmpty")}</EmptyRow>
                                    ) : (
                                        <ListCard>
                                            {inviteLinks.map((link) => {
                                                const busy = busyLinkId === link.id;
                                                const copied = copiedLinkId === link.id;
                                                return (
                                                    <li
                                                        key={link.id}
                                                        className="group flex items-center gap-3 px-4 py-3"
                                                    >
                                                        <span
                                                            aria-hidden
                                                            className="grid size-8 shrink-0 place-items-center rounded-full bg-muted text-muted-foreground"
                                                        >
                                                            <LinkIcon className="size-4" />
                                                        </span>
                                                        <div className="min-w-0 flex-1">
                                                            <div className="flex items-center gap-2">
                                                                <RoleBadge
                                                                    role={link.role}
                                                                    label={roleLabel(link.role)}
                                                                />
                                                                <span className="text-xs text-muted-foreground tabular-nums">
                                                                    {link.maxUses != null
                                                                        ? t("linkUsesOf", {
                                                                              used: link.usedCount,
                                                                              max: link.maxUses,
                                                                          })
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
                                                            disabled={!link.token}
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
                            </TabsContent>

                            {showDomains && (
                                <TabsContent value="domains">
                                    <AllowedDomainsPanel />
                                </TabsContent>
                            )}
                        </Tabs>
                    </SettingsSection>
                </Rise>
            )}

            <Dialog open={removeTarget !== null} onOpenChange={(open) => { if (!open && !isRemoving) setRemoveTarget(null); }}>
                <DialogContent className="max-h-[80dvh] overflow-y-auto">
                    <DialogHeader>
                        <DialogTitle>{t("removeTitle", { name: removeTarget?.displayName ?? "" })}</DialogTitle>
                        <DialogDescription>{t("removeDescription", { workspace: activeWorkspace?.name ?? "" })}</DialogDescription>
                    </DialogHeader>
                    <p className="text-sm text-muted-foreground">{t("removeOwnershipConsequences")}</p>
                    <p className="text-sm text-muted-foreground">{t("removeRejoinConsequences")}</p>
                    <Button variant="outline" disabled={isRemoving} onClick={() => {
                        setRemoveTarget(null);
                        setInvitationTab("link");
                        requestAnimationFrame(() => {
                            inviteLinkTab.current?.scrollIntoView({ block: "center" });
                            inviteLinkTab.current?.focus({ preventScroll: true });
                        });
                    }}>{t("reviewInvitations")}</Button>
                    <DialogFooter>
                        <DialogClose asChild><Button variant="outline" disabled={isRemoving}>{t("cancel")}</Button></DialogClose>
                        <Button variant="destructive" disabled={isRemoving} onClick={confirmRemove}>
                            {isRemoving ? <Loader2Icon className="size-4 animate-spin" /> : t("remove")}
                        </Button>
                    </DialogFooter>
                </DialogContent>
            </Dialog>

            <ConfinementAcknowledgementDialog acknowledgement={confinement} />

            <PasskeyApprovalCodeDialog
                target={approvalTarget}
                organizationName={activeWorkspace?.orgName ?? ""}
                onClose={() => setApprovalTarget(null)}
                issue={(userId) => issueWorkspaceMfaAttestation(workspaceId, userId)}
                revoke={(userId) => revokeWorkspaceMfaAttestation(workspaceId, userId)}
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
