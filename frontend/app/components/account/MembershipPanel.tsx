"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { useFormatter, useTranslations } from "next-intl";
import { Loader2Icon } from "lucide-react";

import type { OwnershipOffer, Workspace } from "@/app/lib/types";
import {
    acceptOwnershipOffer,
    acceptWorkspace,
    ApiError,
    declineOwnershipOffer,
    declineWorkspace,
    getMyOwnershipOffers,
    getPendingWorkspaces,
    leaveWorkspace,
} from "@/app/lib/api";
import { useApiErrorToast } from "@/app/hooks/useApiErrorToast";
import { usePasskeyStepUpErrorHandler } from "@/app/hooks/usePasskeyStepUpError";
import { useWorkspace } from "@/app/hooks/useWorkspace";
import { toastError, toastSuccess } from "@/app/lib/toast";
import { Avatar, AvatarFallback } from "@/components/ui/avatar";
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
import { Skeleton } from "@/components/ui/skeleton";
import Rise from "@/app/components/motion/Rise";
import SectionHeader from "@/app/components/dashboard/SectionHeader";

export default function MembershipPanel() {
    const t = useTranslations("AccountInvites");
    const showApiError = useApiErrorToast("AccountInvites");
    const handlePasskeyStepUpError = usePasskeyStepUpErrorHandler();
    const format = useFormatter();
    const router = useRouter();
    const { activeWorkspaceId, activeWorkspace, runSelectionChange, switching } = useWorkspace();

    const [pending, setPending] = useState<Workspace[]>([]);
    const [loading, setLoading] = useState(true);
    const [busyId, setBusyId] = useState<number | null>(null);
    const [leaveOpen, setLeaveOpen] = useState(false);
    const [leaving, setLeaving] = useState(false);
    const [offers, setOffers] = useState<OwnershipOffer[]>([]);
    const [busyOfferId, setBusyOfferId] = useState<string | null>(null);
    const [confinementOffer, setConfinementOffer] = useState<OwnershipOffer | null>(null);

    useEffect(() => {
        let cancelled = false;
        (async () => {
            try {
                const loaded = await getMyOwnershipOffers();
                if (!cancelled) setOffers(loaded.filter((offer) => offer.state === "OPEN"));
            } catch {
                if (!cancelled) toastError(t("offersLoadFailed"));
            }
        })();
        return () => {
            cancelled = true;
        };
    }, [t]);

    const scopeLabel = (offer: OwnershipOffer) =>
        offer.scopeName?.trim()
        || (offer.scopeKind === "ORGANIZATION" ? t("ownershipScopeOrganization") : t("ownershipScopeWorkspace"));

    const acceptOffer = async (offer: OwnershipOffer, acknowledgeConfinement = false) => {
        setBusyOfferId(offer.id);
        try {
            await acceptOwnershipOffer(offer.id, acknowledgeConfinement);
            setOffers((prev) => prev.filter((existing) => existing.id !== offer.id));
            setConfinementOffer(null);
            toastSuccess(t("ownershipAccepted", { scope: scopeLabel(offer) }));
            router.refresh();
        } catch (err) {
            if (err instanceof ApiError && err.code === "PRIVILEGED_MFA_CONFINEMENT_ACKNOWLEDGEMENT_REQUIRED") {
                setConfinementOffer(offer);
            } else if (!handlePasskeyStepUpError(err)) {
                showApiError(err, "ownershipAcceptFailed");
            }
        } finally {
            setBusyOfferId(null);
        }
    };

    const declineOffer = async (offer: OwnershipOffer) => {
        setBusyOfferId(offer.id);
        try {
            await declineOwnershipOffer(offer.id);
            setOffers((prev) => prev.filter((existing) => existing.id !== offer.id));
            toastSuccess(t("ownershipDeclined"));
        } catch (err) {
            showApiError(err, "ownershipDeclineFailed");
        } finally {
            setBusyOfferId(null);
        }
    };

    useEffect(() => {
        let cancelled = false;
        (async () => {
            setLoading(true);
            try {
                const loaded = await getPendingWorkspaces();
                if (!cancelled) setPending(loaded);
            } catch {
                if (!cancelled) toastError(t("loadFailed"));
            } finally {
                if (!cancelled) setLoading(false);
            }
        })();
        return () => {
            cancelled = true;
        };
    }, [t]);

    const accept = async (workspace: Workspace) => {
        setBusyId(workspace.id);
        try {
            await runSelectionChange(async (publishActiveWorkspace, publishWorkspace) => {
                const accepted = await acceptWorkspace(workspace.id);
                publishWorkspace(accepted);
                publishActiveWorkspace(accepted.id);
                setPending((prev) => prev.filter((w) => w.id !== workspace.id));
                toastSuccess(t("accepted", { workspace: workspace.name }));
                router.refresh();
            });
        } catch (err) {
            showApiError(err, "acceptFailed");
        } finally {
            setBusyId(null);
        }
    };

    const decline = async (workspace: Workspace) => {
        setBusyId(workspace.id);
        try {
            await declineWorkspace(workspace.id);
            setPending((prev) => prev.filter((w) => w.id !== workspace.id));
            toastSuccess(t("declined"));
        } catch (err) {
            showApiError(err, "declineFailed");
        } finally {
            setBusyId(null);
        }
    };

    const doLeave = async () => {
        if (!activeWorkspaceId || leaving) return;
        setLeaving(true);
        try {
            await runSelectionChange(async (publishActiveWorkspace) => {
                const selection = await leaveWorkspace(activeWorkspaceId);
                publishActiveWorkspace(selection.activeWorkspaceId);
                toastSuccess(t("left"));
                router.replace(selection.activeWorkspaceId !== null ? "/dashboard" : "/onboarding");
                router.refresh();
            });
        } catch (err) {
            showApiError(err, "leaveFailed");
            setLeaving(false);
            setLeaveOpen(false);
        }
    };

    return (
        <div className="space-y-10">
            {offers.length > 0 && (
                <Rise className="space-y-3">
                    <div>
                        <SectionHeader title={t("ownershipTitle")} />
                        <p className="px-6 text-sm text-muted-foreground">{t("ownershipSubtitle")}</p>
                    </div>
                    <ul className="divide-y divide-border overflow-hidden rounded-2xl border border-border bg-card">
                        {offers.map((offer) => {
                            const busy = busyOfferId === offer.id;
                            const scope = scopeLabel(offer);
                            const expires = format.dateTime(new Date(offer.expiresAt), { dateStyle: "medium" });
                            return (
                                <li key={offer.id} className="flex flex-wrap items-center gap-3 px-4 py-3">
                                    <Avatar>
                                        <AvatarFallback className="bg-brand-light font-medium text-brand-dark">
                                            {scope.trim().charAt(0).toUpperCase() || "?"}
                                        </AvatarFallback>
                                    </Avatar>
                                    <div className="min-w-0 flex-1">
                                        <p className="truncate text-sm font-medium text-foreground">
                                            {t("ownershipOfferTitle", { scope })}
                                        </p>
                                        <p className="truncate text-xs text-muted-foreground">
                                            {offer.offerorDisplayName
                                                ? t("ownershipOfferMeta", { name: offer.offerorDisplayName, date: expires })
                                                : t("ownershipOfferExpires", { date: expires })}
                                        </p>
                                    </div>
                                    <Button size="toolbar" variant="outline" disabled={busy} onClick={() => declineOffer(offer)}>
                                        {t("decline")}
                                    </Button>
                                    <Button size="toolbar" variant="brand" disabled={busy} onClick={() => acceptOffer(offer)}>
                                        {busy ? <Loader2Icon className="size-4 animate-spin" /> : t("accept")}
                                    </Button>
                                </li>
                            );
                        })}
                    </ul>
                </Rise>
            )}

            <Rise className="space-y-3">
                <div>
                    <SectionHeader title={t("pendingTitle")} />
                    <p className="px-6 text-sm text-muted-foreground">{t("pendingSubtitle")}</p>
                </div>

                {loading ? (
                    <div className="overflow-hidden rounded-2xl border border-border bg-card">
                        <div className="flex items-center gap-3 px-4 py-3">
                            <Skeleton className="size-8 shrink-0 rounded-full" />
                            <Skeleton className="h-3.5 w-40" />
                        </div>
                    </div>
                ) : pending.length === 0 ? (
                    <p className="rounded-2xl border border-border bg-card px-4 py-6 text-center text-sm text-muted-foreground">
                        {t("pendingEmpty")}
                    </p>
                ) : (
                    <ul className="divide-y divide-border overflow-hidden rounded-2xl border border-border bg-card">
                        {pending.map((workspace) => {
                            const busy = busyId === workspace.id;
                            return (
                                <li key={workspace.id} className="flex items-center gap-3 px-4 py-3">
                                    <Avatar>
                                        <AvatarFallback className="bg-brand-light font-medium text-brand-dark">
                                            {workspace.name.trim().charAt(0).toUpperCase() || "?"}
                                        </AvatarFallback>
                                    </Avatar>
                                    <span className="min-w-0 flex-1 truncate text-sm font-medium text-foreground">
                                        {workspace.name}
                                    </span>
                                    <Button
                                        size="sm"
                                        variant="outline"
                                        disabled={busy}
                                        onClick={() => decline(workspace)}
                                    >
                                        {t("decline")}
                                    </Button>
                                    <Button
                                        size="sm"
                                        variant="brand"
                                        disabled={busy || switching}
                                        onClick={() => accept(workspace)}
                                    >
                                        {busy ? <Loader2Icon className="size-4 animate-spin" /> : t("accept")}
                                    </Button>
                                </li>
                            );
                        })}
                    </ul>
                )}
            </Rise>

            <Rise className="space-y-3">
                <SectionHeader title={t("leaveTitle")} />
                <div className="flex flex-wrap items-center justify-between gap-3 rounded-2xl border border-border bg-card px-4 py-4">
                    <p className="text-sm text-muted-foreground">
                        {t("leaveSubtitle", { workspace: activeWorkspace?.name ?? "" })}
                    </p>
                    <Button
                        variant="destructive"
                        disabled={!activeWorkspaceId || switching}
                        onClick={() => setLeaveOpen(true)}
                    >
                        {t("leave")}
                    </Button>
                </div>
            </Rise>

            <Dialog
                open={confinementOffer !== null}
                onOpenChange={(open) => {
                    if (!open && busyOfferId === null) setConfinementOffer(null);
                }}
            >
                <DialogContent>
                    <DialogHeader>
                        <DialogTitle>
                            {t("ownershipConfinementTitle", {
                                scope: confinementOffer ? scopeLabel(confinementOffer) : "",
                            })}
                        </DialogTitle>
                        <DialogDescription>{t("ownershipConfinementBody")}</DialogDescription>
                    </DialogHeader>
                    <DialogFooter>
                        <DialogClose asChild>
                            <Button variant="outline" disabled={busyOfferId !== null}>
                                {t("cancel")}
                            </Button>
                        </DialogClose>
                        <Button
                            variant="brand"
                            disabled={busyOfferId !== null || confinementOffer === null}
                            onClick={() => {
                                if (confinementOffer) void acceptOffer(confinementOffer, true);
                            }}
                        >
                            {busyOfferId !== null
                                ? <Loader2Icon className="size-4 animate-spin" />
                                : t("ownershipConfinementAccept")}
                        </Button>
                    </DialogFooter>
                </DialogContent>
            </Dialog>

            <Dialog open={leaveOpen} onOpenChange={(open) => !leaving && setLeaveOpen(open)}>
                <DialogContent>
                    <DialogHeader>
                        <DialogTitle>{t("leaveConfirmTitle", { workspace: activeWorkspace?.name ?? "" })}</DialogTitle>
                        <DialogDescription>{t("leaveConfirmBody")}</DialogDescription>
                    </DialogHeader>
                    <DialogFooter>
                        <DialogClose asChild>
                            <Button variant="outline" disabled={leaving}>
                                {t("cancel")}
                            </Button>
                        </DialogClose>
                        <Button
                            variant="destructive"
                            className="bg-destructive text-destructive-foreground hover:bg-destructive/90"
                            disabled={leaving || switching}
                            onClick={doLeave}
                        >
                            {leaving ? <Loader2Icon className="size-4 animate-spin" /> : t("leave")}
                        </Button>
                    </DialogFooter>
                </DialogContent>
            </Dialog>
        </div>
    );
}
