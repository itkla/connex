"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { useTranslations } from "next-intl";
import { Loader2Icon } from "lucide-react";

import type { Workspace } from "@/app/lib/types";
import {
    acceptWorkspace,
    declineWorkspace,
    getPendingWorkspaces,
    leaveWorkspace,
} from "@/app/lib/api";
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
    const router = useRouter();
    const { workspaces, activeWorkspaceId, activeWorkspace } = useWorkspace();

    const [pending, setPending] = useState<Workspace[]>([]);
    const [loading, setLoading] = useState(true);
    const [busyId, setBusyId] = useState<number | null>(null);
    const [leaveOpen, setLeaveOpen] = useState(false);
    const [leaving, setLeaving] = useState(false);

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
            await acceptWorkspace(workspace.id);
            setPending((prev) => prev.filter((w) => w.id !== workspace.id));
            toastSuccess(t("accepted", { workspace: workspace.name }));
            router.refresh();
        } catch (err) {
            toastError(err instanceof Error ? err.message : t("acceptFailed"));
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
            toastError(err instanceof Error ? err.message : t("declineFailed"));
        } finally {
            setBusyId(null);
        }
    };

    const doLeave = async () => {
        if (!activeWorkspaceId || leaving) return;
        setLeaving(true);
        try {
            await leaveWorkspace(activeWorkspaceId);
            const remaining = workspaces.filter((w) => w.id !== activeWorkspaceId);
            toastSuccess(t("left"));
            router.replace(remaining.length > 0 ? "/dashboard" : "/onboarding");
            router.refresh();
        } catch (err) {
            toastError(err instanceof Error ? err.message : t("leaveFailed"));
            setLeaving(false);
            setLeaveOpen(false);
        }
    };

    return (
        <div className="space-y-10">
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
                                        disabled={busy}
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
                        disabled={!activeWorkspaceId}
                        onClick={() => setLeaveOpen(true)}
                    >
                        {t("leave")}
                    </Button>
                </div>
            </Rise>

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
                            disabled={leaving}
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
