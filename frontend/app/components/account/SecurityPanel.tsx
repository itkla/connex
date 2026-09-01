"use client";

import { useEffect, useState } from "react";
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
import { startRegistration, WebAuthnError } from "@simplewebauthn/browser";

import type { Passkey } from "@/app/lib/types";
import {
    ApiError,
    beginPasskeyRegistration,
    completePrivilegedMfaEnrollment,
    deletePasskey,
    finishPasskeyRegistration,
    getPasskeyRegistrationRequirements,
    getPasskeys,
    MAIL_TRANSPORT_UNAVAILABLE_CODE,
    renamePasskey,
    requestPasskeyBootstrapConfirmation,
} from "@/app/lib/api";
import { usePasskeySupport } from "@/app/hooks/usePasskeySupport";
import { usePasskeyStepUpErrorHandler } from "@/app/hooks/usePasskeyStepUpError";
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

function deviceLabel(): string {
    if (typeof navigator === "undefined") return "Passkey";
    const ua = navigator.userAgent;
    const os = /Windows/.test(ua)
        ? "Windows"
        : /iPhone|iPad|iPod/.test(ua)
          ? "iOS"
          : /Mac/.test(ua)
            ? "macOS"
            : /Android/.test(ua)
              ? "Android"
              : /Linux/.test(ua)
                ? "Linux"
                : null;
    const browser = /Edg\//.test(ua)
        ? "Edge"
        : /Firefox\//.test(ua)
          ? "Firefox"
          : /Chrome\//.test(ua)
            ? "Chrome"
            : /Safari\//.test(ua)
              ? "Safari"
              : null;
    if (browser && os) return `${browser} on ${os}`;
    return os ?? "Passkey";
}

function isCancellation(err: unknown): boolean {
    if (err instanceof WebAuthnError && err.cause instanceof Error && err.cause.name === "NotAllowedError") {
        return true;
    }
    return err instanceof Error && err.name === "NotAllowedError";
}

export default function SecurityPanel() {
    const t = useTranslations("AccountSecurity");
    const locale = useLocale();
    const now = useLiveNow();
    const handlePasskeyStepUpError = usePasskeyStepUpErrorHandler();

    const [passkeys, setPasskeys] = useState<Passkey[]>([]);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState(false);
    const [reloadKey, setReloadKey] = useState(0);
    const supported = usePasskeySupport();
    const [adding, setAdding] = useState(false);
    const [currentPasswordRequired, setCurrentPasswordRequired] = useState(false);
    const [passwordOpen, setPasswordOpen] = useState(false);
    const [currentPassword, setCurrentPassword] = useState("");
    const [passwordError, setPasswordError] = useState<string | null>(null);
    const [confirmingPassword, setConfirmingPassword] = useState(false);
    const [emailConfirmationRequired, setEmailConfirmationRequired] = useState(false);
    const [emailConfirmationSatisfied, setEmailConfirmationSatisfied] = useState(false);
    const [confirmationOpen, setConfirmationOpen] = useState(false);
    const [sendingConfirmation, setSendingConfirmation] = useState(false);

    const [renameTarget, setRenameTarget] = useState<Passkey | null>(null);
    const [renameValue, setRenameValue] = useState("");
    const [isRenaming, setIsRenaming] = useState(false);
    const [removeTarget, setRemoveTarget] = useState<Passkey | null>(null);
    const [isRemoving, setIsRemoving] = useState(false);

    useEffect(() => {
        let cancelled = false;
        (async () => {
            setLoading(true);
            setError(false);
            try {
                const [loaded, requirements] = await Promise.all([
                    getPasskeys(),
                    getPasskeyRegistrationRequirements(),
                ]);
                if (!cancelled) {
                    setPasskeys(loaded);
                    setCurrentPasswordRequired(requirements.currentPasswordRequired);
                    setEmailConfirmationRequired(requirements.emailConfirmationRequired);
                    setEmailConfirmationSatisfied(requirements.emailConfirmationSatisfied);
                }
            } catch {
                if (!cancelled) {
                    setError(true);
                    toastError(t("loadFailed"));
                }
            } finally {
                if (!cancelled) setLoading(false);
            }
        })();
        return () => {
            cancelled = true;
        };
    }, [t, reloadKey]);

    const finishRegistration = async (optionsJSON: Awaited<ReturnType<typeof beginPasskeyRegistration>>) => {
        const credential = await startRegistration({ optionsJSON });
        await finishPasskeyRegistration(deviceLabel(), credential);
        completePrivilegedMfaEnrollment();
        setPasskeys(await getPasskeys());
        setCurrentPasswordRequired(false);
        setEmailConfirmationRequired(false);
        setEmailConfirmationSatisfied(false);
        toastSuccess(t("added"));
    };

    const sendConfirmation = async () => {
        if (sendingConfirmation) return;
        setSendingConfirmation(true);
        try {
            await requestPasskeyBootstrapConfirmation();
            toastSuccess(t("confirmationSent"));
        } catch (err) {
            toastError(
                err instanceof ApiError && err.code === MAIL_TRANSPORT_UNAVAILABLE_CODE
                    ? t("confirmationUnavailable")
                    : t("confirmationFailed"),
            );
        } finally {
            setSendingConfirmation(false);
        }
    };

    const addPasskey = async () => {
        if (passkeys.length === 0 && emailConfirmationRequired && !emailConfirmationSatisfied) {
            setConfirmationOpen(true);
            return;
        }
        if (passkeys.length === 0 && currentPasswordRequired) {
            setPasswordError(null);
            setPasswordOpen(true);
            return;
        }
        setAdding(true);
        let registrationStarted = false;
        try {
            const optionsJSON = await beginPasskeyRegistration();
            registrationStarted = true;
            await finishRegistration(optionsJSON);
        } catch (err) {
            if (isCancellation(err)) {
                toastInfo(t(registrationStarted ? "canceled" : "stepUpCanceled"));
            } else if (handlePasskeyStepUpError(err)) {
                return;
            } else if (err instanceof ApiError && err.status === 403 && passkeys.length === 0) {
                toastError(emailConfirmationRequired ? t("confirmationRequired") : t("freshSignInRequired"));
                setReloadKey((key) => key + 1);
            } else {
                toastError(t("addFailed"));
                if (passkeys.length === 0) {
                    setReloadKey((key) => key + 1);
                }
            }
        } finally {
            setAdding(false);
        }
    };

    const confirmFirstPasskey = async () => {
        if (!currentPassword || confirmingPassword) return;
        setConfirmingPassword(true);
        setPasswordError(null);
        try {
            const optionsJSON = await beginPasskeyRegistration(currentPassword);
            setCurrentPassword("");
            await finishRegistration(optionsJSON);
            setPasswordOpen(false);
        } catch (err) {
            setCurrentPassword("");
            if (isCancellation(err)) {
                toastInfo(t("canceled"));
                setPasswordOpen(false);
            } else if (err instanceof ApiError && err.status === 401) {
                setPasswordError(t("incorrectPassword"));
            } else {
                toastError(t("addFailed"));
            }
        } finally {
            setConfirmingPassword(false);
        }
    };

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
            if (isCancellation(err)) {
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
            if (isCancellation(err)) {
                toastInfo(t("stepUpCanceled"));
            } else {
                toastError(t("removeFailed"));
            }
        } finally {
            setIsRemoving(false);
        }
    };

    const addButton = (
        <Button
            onClick={addPasskey}
            variant="brand"
            disabled={adding || !supported}
            aria-busy={adding}
        >
            {adding ? <Loader2Icon className="size-4 animate-spin" /> : <PlusIcon className="size-4" />}
            {adding ? t("adding") : t("add")}
        </Button>
    );

    return (
        <div className="space-y-10">
            <Rise className="space-y-3">
                <div>
                    <SectionHeader title={t("title")} action={!loading && supported ? addButton : undefined} />
                    <p className="max-w-prose px-6 text-sm text-muted-foreground">{t("subtitle")}</p>
                </div>

                {!supported && (
                    <p className="rounded-2xl border border-border bg-card px-4 py-6 text-center text-sm text-muted-foreground">
                        {t("unsupported")}
                    </p>
                )}

                {supported &&
                    (loading ? (
                        <PasskeySkeleton rows={2} />
                    ) : error ? (
                        <div className="flex flex-col items-center gap-3 rounded-2xl border border-border bg-card px-4 py-8 text-center">
                            <p className="text-sm text-muted-foreground">{t("loadFailed")}</p>
                            <Button variant="outline" size="sm" onClick={() => setReloadKey((k) => k + 1)}>
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
                                </li>
                            ))}
                        </ul>
                    ))}
            </Rise>

            <Dialog
                open={passwordOpen}
                onOpenChange={(open) => {
                    if (confirmingPassword) return;
                    setPasswordOpen(open);
                    if (!open) {
                        setCurrentPassword("");
                        setPasswordError(null);
                    }
                }}
            >
                <DialogContent className="sm:max-w-md">
                    <DialogHeader>
                        <DialogTitle>{t("passwordTitle")}</DialogTitle>
                        <DialogDescription>{t("passwordDescription")}</DialogDescription>
                    </DialogHeader>
                    <form
                        onSubmit={(e) => {
                            e.preventDefault();
                            void confirmFirstPasskey();
                        }}
                        className="space-y-4"
                    >
                        <div className="space-y-2">
                            <Label htmlFor="passkey-current-password">{t("passwordLabel")}</Label>
                            <Input
                                id="passkey-current-password"
                                type="password"
                                autoComplete="current-password"
                                value={currentPassword}
                                onChange={(e) => {
                                    setCurrentPassword(e.target.value);
                                    setPasswordError(null);
                                }}
                                maxLength={255}
                                aria-invalid={passwordError !== null}
                                aria-describedby={passwordError ? "passkey-current-password-error" : undefined}
                                autoFocus
                                required
                            />
                            {passwordError && (
                                <p id="passkey-current-password-error" role="alert" className="text-sm text-destructive">
                                    {passwordError}
                                </p>
                            )}
                        </div>
                        <DialogFooter>
                            <DialogClose asChild>
                                <Button type="button" variant="outline" disabled={confirmingPassword}>
                                    {t("cancel")}
                                </Button>
                            </DialogClose>
                            <Button
                                type="submit"
                                disabled={confirmingPassword || !currentPassword}
                                className="bg-brand text-white hover:bg-brand-hover"
                            >
                                {confirmingPassword ? <Loader2Icon className="size-4 animate-spin" /> : t("continue")}
                            </Button>
                        </DialogFooter>
                    </form>
                </DialogContent>
            </Dialog>

            <Dialog
                open={confirmationOpen}
                onOpenChange={(open) => {
                    if (sendingConfirmation) return;
                    setConfirmationOpen(open);
                }}
            >
                <DialogContent className="sm:max-w-md">
                    <DialogHeader>
                        <DialogTitle>{t("confirmationTitle")}</DialogTitle>
                        <DialogDescription>{t("confirmationDescription")}</DialogDescription>
                    </DialogHeader>
                    <DialogFooter>
                        <DialogClose asChild>
                            <Button type="button" variant="outline" disabled={sendingConfirmation}>
                                {t("cancel")}
                            </Button>
                        </DialogClose>
                        <Button
                            type="button"
                            onClick={() => void sendConfirmation()}
                            disabled={sendingConfirmation}
                            className="bg-brand text-white hover:bg-brand-hover"
                        >
                            {sendingConfirmation
                                ? <Loader2Icon className="size-4 animate-spin" />
                                : t("confirmationSend")}
                        </Button>
                    </DialogFooter>
                </DialogContent>
            </Dialog>

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
