"use client";

import { useRef, useState } from "react";
import { useLocale, useTranslations } from "next-intl";
import { Loader2Icon } from "lucide-react";
import { CheckIcon, ClipboardDocumentIcon } from "@heroicons/react/24/outline";

import type { MfaAttestationCode } from "@/app/lib/types";
import { useApiErrorToast } from "@/app/hooks/useApiErrorToast";
import { usePasskeyStepUpErrorHandler } from "@/app/hooks/usePasskeyStepUpError";
import { toastError, toastSuccess } from "@/app/lib/toast";
import { formatDateTime } from "@/app/lib/utils";
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

/** The member a passkey approval code is for. */
export type PasskeyApprovalCodeTarget = {
    id: number;
    displayName: string;
};

/**
 * Creates or turns off a member's passkey approval code (#1534), for the workspace member list and the
 * organization administrator list alike; the caller supplies the scope's own create and turn-off
 * requests. The dialog explains what a code does before creating one, then shows the new code exactly
 * once with its expiry. While a code is showing, a click outside does not close the dialog, because a
 * code lost that way can never be shown again. Focus opens on Create code rather than on the first
 * focusable control, which is the link that turns off the open code, and moves to Copy code once a
 * code is showing.
 *
 * Creating and turning off both need a fresh passkey check, which the API client runs and retries on
 * its own; a canceled or failed check is reported the way every other step-up reports it.
 *
 * @param target the member the code is for, or null when the dialog is closed
 * @param organizationName the organization whose administrator access the code can approve a passkey for
 * @param onClose called once the dialog has closed
 * @param issue creates a code for the member, replacing any open one
 * @param revoke turns off the member's open code, if there is one
 */
export default function PasskeyApprovalCodeDialog({
    target,
    organizationName,
    onClose,
    issue,
    revoke,
}: {
    target: PasskeyApprovalCodeTarget | null;
    organizationName: string;
    onClose: () => void;
    issue: (userId: number) => Promise<MfaAttestationCode>;
    revoke: (userId: number) => Promise<void>;
}) {
    const t = useTranslations("PasskeyApprovalCode");
    const locale = useLocale();
    const showApiError = useApiErrorToast("PasskeyApprovalCode");
    const handlePasskeyStepUpError = usePasskeyStepUpErrorHandler();
    const [issued, setIssued] = useState<MfaAttestationCode | null>(null);
    const [pending, setPending] = useState<"create" | "revoke" | null>(null);
    const [copied, setCopied] = useState(false);
    const createButton = useRef<HTMLButtonElement>(null);

    const close = () => {
        setIssued(null);
        setCopied(false);
        onClose();
    };

    const create = async () => {
        if (!target || pending) return;
        setPending("create");
        try {
            setIssued(await issue(target.id));
        } catch (err) {
            if (!handlePasskeyStepUpError(err)) {
                showApiError(err, "createFailed");
            }
        } finally {
            setPending(null);
        }
    };

    const turnOff = async () => {
        if (!target || pending) return;
        setPending("revoke");
        try {
            await revoke(target.id);
            toastSuccess(t("revokedToast", { name: target.displayName }));
            close();
        } catch (err) {
            if (!handlePasskeyStepUpError(err)) {
                showApiError(err, "revokeFailed");
            }
        } finally {
            setPending(null);
        }
    };

    const copy = async () => {
        if (!issued) return;
        try {
            await navigator.clipboard.writeText(issued.code);
            setCopied(true);
            window.setTimeout(() => setCopied(false), 1500);
        } catch {
            toastError(t("copyFailed"), { description: t("copyFailedDescription") });
        }
    };

    const name = target?.displayName ?? "";

    return (
        <Dialog
            open={target !== null}
            onOpenChange={(open) => {
                if (!open && pending === null) close();
            }}
        >
            <DialogContent
                className="sm:max-w-md"
                onOpenAutoFocus={(event) => {
                    event.preventDefault();
                    createButton.current?.focus();
                }}
                onInteractOutside={(event) => {
                    if (issued) event.preventDefault();
                }}
            >
                {issued ? (
                    <>
                        <DialogHeader>
                            <DialogTitle>{t("issuedTitle", { name })}</DialogTitle>
                            <DialogDescription>{t("issuedDescription", { name })}</DialogDescription>
                        </DialogHeader>
                        <div className="flex flex-wrap items-center justify-between gap-3 rounded-xl border border-border bg-muted/40 px-4 py-3">
                            <code className="select-all font-mono text-lg font-medium tracking-widest text-foreground">
                                {issued.code}
                            </code>
                            <Button
                                type="button"
                                variant="outline"
                                size="toolbar"
                                onClick={() => void copy()}
                                autoFocus
                            >
                                {copied ? (
                                    <CheckIcon className="size-4" />
                                ) : (
                                    <ClipboardDocumentIcon className="size-4" />
                                )}
                                {copied ? t("copied") : t("copy")}
                            </Button>
                        </div>
                        <p className="text-sm text-muted-foreground">
                            {t("expires", { date: formatDateTime(issued.expiresAt, locale) })}
                        </p>
                        <DialogFooter>
                            <Button type="button" variant="brand" onClick={close}>
                                {t("done")}
                            </Button>
                        </DialogFooter>
                    </>
                ) : (
                    <>
                        <DialogHeader>
                            <DialogTitle>{t("title")}</DialogTitle>
                            <DialogDescription>
                                {t("description", { name, organization: organizationName })}
                            </DialogDescription>
                        </DialogHeader>
                        <p className="text-sm text-muted-foreground">{t("guidance")}</p>
                        <div className="flex flex-wrap items-center gap-x-1.5 gap-y-1 text-sm text-muted-foreground">
                            <span>{t("revokePrompt")}</span>
                            <Button
                                type="button"
                                variant="link"
                                size="inline"
                                className="h-auto p-0"
                                onClick={() => void turnOff()}
                                disabled={pending !== null}
                                aria-busy={pending === "revoke"}
                            >
                                {pending === "revoke" && <Loader2Icon className="size-3.5 animate-spin" />}
                                {t("revoke")}
                            </Button>
                        </div>
                        <DialogFooter>
                            <DialogClose asChild>
                                <Button type="button" variant="outline" disabled={pending !== null}>
                                    {t("cancel")}
                                </Button>
                            </DialogClose>
                            <Button
                                ref={createButton}
                                type="button"
                                variant="brand"
                                onClick={() => void create()}
                                disabled={pending !== null}
                                aria-busy={pending === "create"}
                            >
                                {pending === "create" ? <Loader2Icon className="size-4 animate-spin" /> : t("create")}
                            </Button>
                        </DialogFooter>
                    </>
                )}
            </DialogContent>
        </Dialog>
    );
}
