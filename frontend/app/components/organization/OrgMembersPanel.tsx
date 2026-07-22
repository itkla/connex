"use client";

import { useEffect, useState } from "react";
import { useTranslations } from "next-intl";
import { Loader2Icon } from "lucide-react";
import { EllipsisHorizontalIcon, TrashIcon } from "@heroicons/react/24/outline";

import type { OrgMember, OrgRole } from "@/app/lib/types";
import {
    addOrgMemberByEmail,
    ApiError,
    getOrgMembers,
    removeOrgMember,
    setOrgMemberRole,
} from "@/app/lib/api";
import { useWorkspace } from "@/app/hooks/useWorkspace";
import { useFieldErrors } from "@/app/hooks/useFieldErrors";
import { usePasskeyStepUpErrorHandler } from "@/app/hooks/usePasskeyStepUpError";
import { toastError, toastSuccess } from "@/app/lib/toast";
import { Avatar, AvatarFallback, AvatarImage } from "@/components/ui/avatar";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
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
    DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { InputGroup, InputGroupAddon, InputGroupInput } from "@/components/ui/input-group";
import { EnvelopeIcon } from "@heroicons/react/24/outline";
import {
    Select,
    SelectContent,
    SelectItem,
    SelectTrigger,
    SelectValue,
} from "@/components/ui/select";
import SectionHeader from "@/app/components/dashboard/SectionHeader";
import Rise from "@/app/components/motion/Rise";
import { NoAccessCard, EmptyRow, ListCard, rowActionTrigger } from "@/app/components/organization/OrgPrimitives";

function initial(name: string) {
    return name.trim().charAt(0).toUpperCase() || "?";
}

export default function OrgMembersPanel({ currentUserId }: { currentUserId: number | null }) {
    const t = useTranslations("OrgMembers");
    const handlePasskeyStepUpError = usePasskeyStepUpErrorHandler();
    const { activeWorkspace } = useWorkspace();
    const orgId = activeWorkspace?.orgId ?? null;
    const isOwner = activeWorkspace?.orgRole === "owner";
    const { fieldErrors, setFieldErrors, clearError } = useFieldErrors();

    const [members, setMembers] = useState<OrgMember[]>([]);
    const [loading, setLoading] = useState(true);
    const [accessDenied, setAccessDenied] = useState(false);
    const [busy, setBusy] = useState<number | null>(null);
    const [removeTarget, setRemoveTarget] = useState<OrgMember | null>(null);
    const [isRemoving, setIsRemoving] = useState(false);
    const [email, setEmail] = useState("");
    const [addRole, setAddRole] = useState<OrgRole>("admin");
    const [adding, setAdding] = useState(false);

    const ownerCount = members.filter((m) => m.orgRole === "owner").length;
    const roleLabel = (role: OrgRole) => (role === "owner" ? t("roleOwner") : t("roleAdmin"));

    async function reload(id: number) {
        const loaded = await getOrgMembers(id);
        setMembers(loaded);
    }

    useEffect(() => {
        if (!orgId) return;
        let cancelled = false;
        (async () => {
            setLoading(true);
            try {
                const loaded = await getOrgMembers(orgId);
                if (!cancelled) setMembers(loaded);
            } catch (err) {
                if (cancelled) return;
                if (err instanceof ApiError && err.status === 403) setAccessDenied(true);
                else toastError(err instanceof Error ? err.message : String(err));
            } finally {
                if (!cancelled) setLoading(false);
            }
        })();
        return () => {
            cancelled = true;
        };
    }, [orgId]);

    async function changeRole(userId: number, next: OrgRole) {
        if (!orgId) return;
        setBusy(userId);
        try {
            await setOrgMemberRole(orgId, userId, next);
            await reload(orgId);
            toastSuccess(t("roleUpdatedToast"));
        } catch (err) {
            if (!handlePasskeyStepUpError(err)) {
                toastError(err instanceof Error ? err.message : String(err));
            }
        } finally {
            setBusy(null);
        }
    }

    async function confirmRemove() {
        if (!orgId || !removeTarget) return;
        setIsRemoving(true);
        try {
            await removeOrgMember(orgId, removeTarget.id);
            setMembers((prev) => prev.filter((m) => m.id !== removeTarget.id));
            toastSuccess(t("removedToast"));
            setRemoveTarget(null);
        } catch (err) {
            if (!handlePasskeyStepUpError(err)) {
                toastError(err instanceof Error ? err.message : String(err));
            }
        } finally {
            setIsRemoving(false);
        }
    }

    async function addMember() {
        if (!orgId || email.trim().length === 0) return;
        setAdding(true);
        try {
            await addOrgMemberByEmail(orgId, email.trim(), addRole);
            await reload(orgId);
            setEmail("");
            toastSuccess(t("addedToast"));
        } catch (err) {
            if (err instanceof ApiError && err.fieldErrors) setFieldErrors(err.fieldErrors);
            else if (!handlePasskeyStepUpError(err)) toastError(err instanceof Error ? err.message : String(err));
        } finally {
            setAdding(false);
        }
    }

    if (accessDenied) return <NoAccessCard />;

    return (
        <div className="space-y-10">
            <Rise className="space-y-3">
                <div>
                    <SectionHeader title={t("title")} />
                    <p className="px-6 text-sm text-muted-foreground">{t("subtitle")}</p>
                </div>

                {loading ? (
                    <ListCard>
                        {[0, 1].map((i) => (
                            <li key={i} className="flex items-center gap-3 px-4 py-3">
                                <span className="size-9 shrink-0 animate-pulse rounded-full bg-muted" />
                                <span className="h-4 w-40 animate-pulse rounded bg-muted" />
                            </li>
                        ))}
                    </ListCard>
                ) : members.length === 0 ? (
                    <EmptyRow>{t("empty")}</EmptyRow>
                ) : (
                    <ListCard>
                        {members.map((member) => {
                            const isSelf = member.id === currentUserId;
                            const lockedSoleOwner = member.orgRole === "owner" && ownerCount <= 1;
                            const editable = isOwner && !lockedSoleOwner;
                            const removable = isOwner && !lockedSoleOwner;
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
                                        </div>
                                        <p className="truncate text-xs text-muted-foreground">{member.email}</p>
                                    </div>

                                    {editable ? (
                                        <Select
                                            value={member.orgRole}
                                            disabled={busy === member.id}
                                            onValueChange={(value) => changeRole(member.id, value as OrgRole)}
                                        >
                                            <SelectTrigger size="sm" className="w-auto" aria-label={t("changeRole")}>
                                                <SelectValue />
                                            </SelectTrigger>
                                            <SelectContent align="end">
                                                <SelectItem value="owner">{t("roleOwner")}</SelectItem>
                                                <SelectItem value="admin">{t("roleAdmin")}</SelectItem>
                                            </SelectContent>
                                        </Select>
                                    ) : member.orgRole === "owner" ? (
                                        <Badge
                                            className="border-transparent bg-brand-light text-brand-dark"
                                            title={lockedSoleOwner ? t("lastOwnerTooltip") : undefined}
                                        >
                                            {roleLabel(member.orgRole)}
                                        </Badge>
                                    ) : (
                                        <Badge variant="secondary">{roleLabel(member.orgRole)}</Badge>
                                    )}

                                    {removable && (
                                        <DropdownMenu>
                                            <DropdownMenuTrigger asChild>
                                                <button type="button" aria-label={t("remove")} className={rowActionTrigger}>
                                                    <EllipsisHorizontalIcon className="size-5" />
                                                </button>
                                            </DropdownMenuTrigger>
                                            <DropdownMenuContent align="end" className="w-40">
                                                <DropdownMenuItem variant="destructive" onSelect={() => setRemoveTarget(member)}>
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

            {isOwner && (
                <Rise className="space-y-4">
                    <div>
                        <SectionHeader title={t("addTitle")} />
                        <p className="px-6 text-sm text-muted-foreground">{t("addSubtitle")}</p>
                    </div>
                    <form
                        onSubmit={(e) => {
                            e.preventDefault();
                            addMember();
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
                                    value={email}
                                    onChange={(e) => {
                                        setEmail(e.target.value);
                                        clearError("email");
                                    }}
                                    placeholder={t("addEmailPlaceholder")}
                                    aria-label={t("addEmailLabel")}
                                    aria-invalid={Boolean(fieldErrors.email)}
                                />
                            </InputGroup>
                            {fieldErrors.email && <p className="mt-1.5 text-sm text-destructive">{fieldErrors.email}</p>}
                        </div>
                        <Select value={addRole} onValueChange={(v) => setAddRole(v as OrgRole)}>
                            <SelectTrigger className="w-full sm:w-36" aria-label={t("addRoleLabel")}>
                                <SelectValue />
                            </SelectTrigger>
                            <SelectContent align="end">
                                <SelectItem value="admin">{t("roleAdmin")}</SelectItem>
                                <SelectItem value="owner">{t("roleOwner")}</SelectItem>
                            </SelectContent>
                        </Select>
                        <Button
                            type="submit"
                            variant="brand"
                            disabled={adding || email.trim().length === 0}
                            className="min-w-36"
                        >
                            {adding ? <Loader2Icon className="size-4 animate-spin" /> : t("addButton")}
                        </Button>
                    </form>
                </Rise>
            )}

            <Dialog
                open={removeTarget !== null}
                onOpenChange={(open) => {
                    if (!open) setRemoveTarget(null);
                }}
            >
                <DialogContent className="sm:max-w-md">
                    <DialogHeader>
                        <DialogTitle>{t("removeConfirmTitle")}</DialogTitle>
                        <DialogDescription>
                            {t("removeConfirmBody", { name: removeTarget?.displayName ?? "" })}
                        </DialogDescription>
                    </DialogHeader>
                    <DialogFooter>
                        <DialogClose asChild>
                            <Button variant="outline" disabled={isRemoving}>
                                {t("cancel")}
                            </Button>
                        </DialogClose>
                        <Button variant="destructive" onClick={confirmRemove} disabled={isRemoving}>
                            {isRemoving ? <Loader2Icon className="size-4 animate-spin" /> : t("remove")}
                        </Button>
                    </DialogFooter>
                </DialogContent>
            </Dialog>
        </div>
    );
}
