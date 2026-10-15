"use client";

import { useCallback, useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { useLocale, useTranslations } from "next-intl";
import {
    DevicePhoneMobileIcon,
    EllipsisHorizontalIcon,
    FingerPrintIcon,
    KeyIcon,
    PencilSquareIcon,
    PlusIcon,
    TrashIcon,
} from "@heroicons/react/24/outline";
import { Loader2Icon } from "lucide-react";

import type { Passkey } from "@/app/lib/types";
import {
    deletePasskey,
    getPasskeys,
    leaveWorkspace,
    renamePasskey,
} from "@/app/lib/api";
import { usePasskeySupport } from "@/app/hooks/usePasskeySupport";
import { useConfinementExits } from "@/app/hooks/useConfinementExits";
import { usePasskeyEnrollment } from "@/app/hooks/usePasskeyEnrollment";
import { usePrivilegedMfaStanding } from "@/app/hooks/usePrivilegedMfaStanding";
import { usePasskeyStepUpErrorHandler } from "@/app/hooks/usePasskeyStepUpError";
import { useWorkspace } from "@/app/hooks/useWorkspace";
import { isWebAuthnCancellation } from "@/app/lib/webauthnCancellation";
import { toastError, toastInfo, toastSuccess } from "@/app/lib/toast";
import { useLiveNow } from "@/app/hooks/useNow";
import { formatRelativeTime } from "@/app/lib/utils";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
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
import { Skeleton } from "@/components/ui/skeleton";
import DeleteRecordDialog from "@/app/components/records/DeleteRecordDialog";
import PasskeyApprovalDialog from "@/app/components/account/PasskeyApprovalDialog";
import PrivilegedMfaConfinementCard from "@/app/components/account/PrivilegedMfaConfinementCard";
import PasskeyEnrollmentPasswordDialog from "@/app/components/account/PasskeyEnrollmentPasswordDialog";
import Rise from "@/app/components/motion/Rise";
import SectionHeader from "@/app/components/dashboard/SectionHeader";

const rowActionTrigger =
    "flex size-7 items-center justify-center rounded-full text-muted-foreground opacity-0 transition hover:bg-muted/70 hover:text-foreground group-hover:opacity-100 focus:opacity-100 focus-visible:opacity-100 data-[state=open]:opacity-100";

function isRoaming(passkey: Passkey): boolean {
    return passkey.transports.some((t) => t === "usb" || t === "nfc" || t === "ble");
}

function PasskeyIcon({ passkey }: { passkey: Passkey }) {
    const Icon = isRoaming(passkey) ? KeyIcon : DevicePhoneMobileIcon;
    return (
        <span
            aria-hidden
            className="grid size-9 shrink-0 place-items-center rounded-lg bg-muted text-muted-foreground ring-1 ring-border"
        >
            <Icon className="size-4" />
        </span>
    );
}

export default function SecurityPanel() {
    const t = useTranslations("AccountSecurity");
    const locale = useLocale();
    const now = useLiveNow();
    const handlePasskeyStepUpError = usePasskeyStepUpErrorHandler();
    const { workspaces, runSelectionChange } = useWorkspace();
    const router = useRouter();

    const [passkeys, setPasskeys] = useState<Passkey[]>([]);
    const [loadingPasskeys, setLoadingPasskeys] = useState(true);
    const [passkeysFailed, setPasskeysFailed] = useState(false);
    const [reloadKey, setReloadKey] = useState(0);
    const supported = usePasskeySupport();
    const {
        requirements,
        status: standingStatus,
        reason: confinementReason,
        refresh: refreshStanding,
    } = usePrivilegedMfaStanding();

    const [renameTarget, setRenameTarget] = useState<Passkey | null>(null);
    const [renameValue, setRenameValue] = useState("");
    const [isRenaming, setIsRenaming] = useState(false);
    const [removeTarget, setRemoveTarget] = useState<Passkey | null>(null);
    const [isRemoving, setIsRemoving] = useState(false);
    const [approvalOpen, setApprovalOpen] = useState(false);

    const currentPasswordRequired = requirements?.currentPasswordRequired ?? false;
    const emailConfirmationRequired = requirements?.emailConfirmationRequired ?? false;
    const emailConfirmationSatisfied = requirements?.emailConfirmationSatisfied ?? false;
    const standing = requirements?.standing ?? null;
    const approvalGaps = requirements?.approvalGaps ?? [];
    const approvalPending = standing === "ATTESTATION_REQUIRED" || confinementReason === "attestation";
    const loading = loadingPasskeys || standingStatus === "loading";
    const error = passkeysFailed || standingStatus === "failed";
    const gapsNameUnknownPasskey = approvalGaps.some(
        (gap) => !passkeys.some((passkey) => passkey.credentialId === gap.credentialId),
    );

    useEffect(() => {
        let cancelled = false;
        (async () => {
            setLoadingPasskeys(true);
            setPasskeysFailed(false);
            try {
                const loaded = await getPasskeys();
                if (!cancelled) setPasskeys(loaded);
            } catch {
                if (!cancelled) {
                    setPasskeysFailed(true);
                    toastError(t("loadFailed"));
                }
            } finally {
                if (!cancelled) setLoadingPasskeys(false);
            }
        })();
        return () => {
            cancelled = true;
        };
    }, [t, reloadKey]);

    useEffect(() => {
        if (!gapsNameUnknownPasskey || loadingPasskeys) return;
        let cancelled = false;
        getPasskeys().then((loaded) => {
            if (!cancelled) setPasskeys(loaded);
        }, () => undefined);
        return () => {
            cancelled = true;
        };
    }, [gapsNameUnknownPasskey, loadingPasskeys, requirements]);

    const retryLoad = () => {
        setReloadKey((key) => key + 1);
        void refreshStanding("check");
    };

    const enrollment = usePasskeyEnrollment(
        {
            hasPasskeys: passkeys.length > 0,
            currentPasswordRequired,
            emailConfirmationRequired,
            emailConfirmationSatisfied,
        },
        {
            onEnrolled: async () => {
                const [loaded] = await Promise.all([getPasskeys(), refreshStanding("ceremony")]);
                setPasskeys(loaded);
            },
            onProofRejected: retryLoad,
        },
    );

    const leaveWorkspaceFromSecurity = useCallback(async (workspaceId: number) => {
        await runSelectionChange(async (publishActiveWorkspace) => {
            const selection = await leaveWorkspace(workspaceId);
            publishActiveWorkspace(selection.activeWorkspaceId);
            if (selection.activeWorkspaceId === null) {
                router.replace("/onboarding");
            }
            router.refresh();
        });
    }, [router, runSelectionChange]);
    const exits = useConfinementExits(leaveWorkspaceFromSecurity);

    const openRename = (passkey: Passkey) => {
        setRenameTarget(passkey);
        setRenameValue(passkey.label);
    };

    const confirmRename = async () => {
        if (!renameTarget) return;
        const label = renameValue.trim();
        if (!label) return;
        setIsRenaming(true);
        try {
            await renamePasskey(renameTarget.credentialId, label);
            setPasskeys((prev) =>
                prev.map((p) => (p.credentialId === renameTarget.credentialId ? { ...p, label } : p)),
            );
            toastSuccess(t("renamed"));
            setRenameTarget(null);
        } catch (err) {
            if (handlePasskeyStepUpError(err)) {
                return;
            }
            if (isWebAuthnCancellation(err)) {
                toastInfo(t("stepUpCanceled"));
            } else {
                toastError(t("renameFailed"));
            }
        } finally {
            setIsRenaming(false);
        }
    };

    const confirmRemove = async () => {
        if (!removeTarget) return;
        setIsRemoving(true);
        try {
            await deletePasskey(removeTarget.credentialId);
            setPasskeys((prev) => prev.filter((p) => p.credentialId !== removeTarget.credentialId));
            toastSuccess(t("removed"));
            setRemoveTarget(null);
        } catch (err) {
            if (handlePasskeyStepUpError(err)) {
                return;
            }
            if (isWebAuthnCancellation(err)) {
                toastInfo(t("stepUpCanceled"));
            } else {
                toastError(t("removeFailed"));
            }
        } finally {
            setIsRemoving(false);
        }
    };

    const announceApproval = (orgId: number) => {
        const organization = workspaces.find((workspace) => workspace.orgId === orgId)?.orgName
            ?? approvalGaps.find((gap) => gap.orgId === orgId)?.orgName;
        toastSuccess(t("approvalDone"), {
            description: organization
                ? t("approvalDoneDescription", { organization })
                : t("approvalDoneDescriptionGeneric"),
        });
        void Promise.all([getPasskeys(), refreshStanding("ceremony")]).then(([loaded]) => {
            setPasskeys(loaded);
        }, () => undefined);
    };

    const cardReason = approvalPending
        ? "attestation"
        : standing === "ENROLLMENT_REQUIRED" || confinementReason === "enrollment" ? "enrollment" : null;

    const addButton = (
        <Button
            onClick={() => void enrollment.addPasskey()}
            variant="brand"
            disabled={enrollment.adding || !supported}
            aria-busy={enrollment.adding}
        >
            {enrollment.adding ? <Loader2Icon className="size-4 animate-spin" /> : <PlusIcon className="size-4" />}
            {enrollment.adding ? t("adding") : t("add")}
        </Button>
    );

    return (
        <div className="space-y-10">
            <Rise className="space-y-3">
                <div>
                    <SectionHeader
                        title={t("title")}
                        action={!loading && !error && supported && !approvalPending ? addButton : undefined}
                    />
                    <p className="max-w-prose px-6 text-sm text-muted-foreground">{t("subtitle")}</p>
                </div>

                {cardReason && (
                    <PrivilegedMfaConfinementCard
                        reason={cardReason}
                        requirements={requirements}
                        passkeys={passkeys}
                        action={cardReason === "attestation" && supported && !loadingPasskeys && !passkeysFailed ? (
                            <Button
                                type="button"
                                variant="brand"
                                size="inline"
                                onClick={() => setApprovalOpen(true)}
                            >
                                {t("approvalEnter")}
                            </Button>
                        ) : undefined}
                        exits={exits}
                        canLeaveWorkspaces
                    />
                )}

                {!supported && (
                    <p className="rounded-2xl border border-border bg-card px-4 py-6 text-center text-sm text-muted-foreground">
                        {t("unsupported")}
                    </p>
                )}

                {supported && !loading && emailConfirmationRequired && (
                    <div className="mx-6 rounded-2xl border border-border bg-card px-5 py-4">
                        <h3 className="text-sm font-medium text-foreground">
                            {t("confirmationTitle")}
                        </h3>
                        <p className="mt-1 max-w-prose text-sm text-muted-foreground">
                            {emailConfirmationSatisfied
                                ? t("confirmationSatisfied")
                                : t("confirmationDescription")}
                        </p>
                        {!emailConfirmationSatisfied && (
                            <Button
                                type="button"
                                variant="outline"
                                size="inline"
                                onClick={() => void enrollment.sendConfirmation()}
                                disabled={enrollment.sendingConfirmation}
                                className="mt-3"
                            >
                                {enrollment.sendingConfirmation ? (
                                    <Loader2Icon className="size-4 animate-spin" />
                                ) : (
                                    t("confirmationSend")
                                )}
                            </Button>
                        )}
                    </div>
                )}

                {supported &&
                    (loading ? (
                        <PasskeySkeleton rows={2} />
                    ) : error ? (
                        <div className="flex flex-col items-center gap-3 rounded-2xl border border-border bg-card px-4 py-8 text-center">
                            <p className="text-sm text-muted-foreground">{t("loadFailed")}</p>
                            <Button variant="outline" size="sm" onClick={retryLoad}>
                                {t("retry")}
                            </Button>
                        </div>
                    ) : passkeys.length === 0 ? (
                        <div className="flex flex-col items-center gap-3 rounded-2xl border border-border bg-card px-6 py-10 text-center">
                            <span
                                aria-hidden
                                className="grid size-11 place-items-center rounded-xl bg-muted text-muted-foreground ring-1 ring-border"
                            >
                                <FingerPrintIcon className="size-5" />
                            </span>
                            <div className="space-y-1">
                                <p className="text-sm font-medium text-foreground">{t("emptyTitle")}</p>
                                <p className="max-w-sm text-sm text-muted-foreground">{t("emptyBody")}</p>
                            </div>
                            <div className="pt-1">{addButton}</div>
                        </div>
                    ) : (
                        <ul className="divide-y divide-border overflow-hidden rounded-2xl border border-border bg-card">
                            {passkeys.map((passkey) => (
                                <li key={passkey.credentialId} className="group flex items-center gap-3 px-4 py-3.5">
                                    <PasskeyIcon passkey={passkey} />
                                    <div className="min-w-0 flex-1 space-y-1">
                                        <span className="block truncate text-sm font-medium text-foreground">
                                            {passkey.label}
                                        </span>
                                        <span className="block truncate text-xs text-muted-foreground">
                                            {t("addedOn", {
                                                date: formatRelativeTime(passkey.createdAt, locale, now),
                                            })}
                                            {" · "}
                                            {passkey.lastUsedAt
                                                ? t("lastUsed", {
                                                      date: formatRelativeTime(passkey.lastUsedAt, locale, now),
                                                  })
                                                : t("neverUsed")}
                                        </span>
                                    </div>
                                    {!approvalPending && (
                                        <DropdownMenu>
                                            <DropdownMenuTrigger asChild>
                                                <button
                                                    type="button"
                                                    aria-label={t("passkeyActions", { label: passkey.label })}
                                                    className={rowActionTrigger}
                                                >
                                                    <EllipsisHorizontalIcon className="size-5" />
                                                </button>
                                            </DropdownMenuTrigger>
                                            <DropdownMenuContent align="end" className="w-40">
                                                <DropdownMenuItem onSelect={() => openRename(passkey)}>
                                                    <PencilSquareIcon className="size-4" />
                                                    {t("rename")}
                                                </DropdownMenuItem>
                                                <DropdownMenuItem
                                                    variant="destructive"
                                                    onSelect={() => setRemoveTarget(passkey)}
                                                >
                                                    <TrashIcon className="size-4" />
                                                    {t("remove")}
                                                </DropdownMenuItem>
                                            </DropdownMenuContent>
                                        </DropdownMenu>
                                    )}
                                </li>
                            ))}
                        </ul>
                    ))}

                {supported && !loading && !error && passkeys.length > 0 && !approvalPending && (
                    <div className="rounded-2xl border border-border bg-card px-5 py-4">
                        <h3 className="text-sm font-medium text-foreground">{t("approvalTitle")}</h3>
                        <p className="mt-1 max-w-prose text-sm text-muted-foreground">
                            {t("approvalDescription")}
                        </p>
                        <Button
                            type="button"
                            variant="outline"
                            size="inline"
                            onClick={() => setApprovalOpen(true)}
                            className="mt-3"
                        >
                            {t("approvalEnter")}
                        </Button>
                    </div>
                )}
            </Rise>

            <PasskeyApprovalDialog
                open={approvalOpen}
                onOpenChange={setApprovalOpen}
                onApproved={announceApproval}
            />

            <PasskeyEnrollmentPasswordDialog password={enrollment.password} />

            <Dialog
                open={renameTarget !== null}
                onOpenChange={(open) => {
                    if (!open) setRenameTarget(null);
                }}
            >
                <DialogContent className="sm:max-w-md">
                    <DialogHeader>
                        <DialogTitle>{t("renameTitle")}</DialogTitle>
                        <DialogDescription>{t("renameDescription")}</DialogDescription>
                    </DialogHeader>
                    <form
                        onSubmit={(e) => {
                            e.preventDefault();
                            void confirmRename();
                        }}
                        className="space-y-4"
                    >
                        <div className="space-y-2">
                            <Label htmlFor="passkey-label">{t("labelField")}</Label>
                            <Input
                                id="passkey-label"
                                value={renameValue}
                                onChange={(e) => setRenameValue(e.target.value)}
                                maxLength={255}
                                autoFocus
                                required
                            />
                        </div>
                        <DialogFooter>
                            <DialogClose asChild>
                                <Button type="button" variant="outline" disabled={isRenaming}>
                                    {t("cancel")}
                                </Button>
                            </DialogClose>
                            <Button
                                type="submit"
                                variant="brand"
                                disabled={isRenaming || !renameValue.trim()}
                            >
                                {isRenaming ? <Loader2Icon className="size-4 animate-spin" /> : t("save")}
                            </Button>
                        </DialogFooter>
                    </form>
                </DialogContent>
            </Dialog>

            <DeleteRecordDialog
                open={removeTarget !== null}
                onOpenChange={(open) => {
                    if (!open) setRemoveTarget(null);
                }}
                selectedIds={new Set(removeTarget ? [removeTarget.credentialId] : [])}
                selectedItems={removeTarget ? [removeTarget] : []}
                entityLabel={t("passkeyEntityLabel")}
                getDisplayName={(p) => p.label}
                isDeleting={isRemoving}
                confirmDelete={confirmRemove}
            />
        </div>
    );
}

function PasskeySkeleton({ rows }: { rows: number }) {
    return (
        <ul className="divide-y divide-border overflow-hidden rounded-2xl border border-border bg-card">
            {Array.from({ length: rows }, (_, i) => (
                <li key={i} className="flex items-center gap-3 px-4 py-3.5">
                    <Skeleton className="size-9 shrink-0 rounded-lg" />
                    <div className="flex-1 space-y-2">
                        <Skeleton className="h-3.5 w-32" />
                        <Skeleton className="h-3 w-48" />
                    </div>
                </li>
            ))}
        </ul>
    );
}
