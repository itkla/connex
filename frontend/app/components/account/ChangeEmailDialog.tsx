"use client";

import { useState } from "react";
import { useTranslations } from "next-intl";
import { EnvelopeIcon } from "@heroicons/react/24/outline";
import { Loader2Icon } from "lucide-react";

import { ApiError, requestEmailChange } from "@/app/lib/api";
import { useFieldErrors } from "@/app/hooks/useFieldErrors";
import { toastError } from "@/app/lib/toast";
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

type Props = {
    open: boolean;
    onOpenChange: (open: boolean) => void;
};

/**
 * Collects a new email address and the current password to request a verified email change.
 * On success it shows a "check your inbox" state pointing the recipient at the new address;
 * the change only applies once they redeem the confirmation link.
 */
export default function ChangeEmailDialog({ open, onOpenChange }: Props) {
    const t = useTranslations("AccountChangeEmail");
    const { fieldErrors, reset, clearError, captureFieldErrors } = useFieldErrors();

    const [newEmail, setNewEmail] = useState("");
    const [currentPassword, setCurrentPassword] = useState("");
    const [submitting, setSubmitting] = useState(false);
    const [sent, setSent] = useState(false);

    const handleOpenChange = (next: boolean) => {
        if (submitting) return;
        onOpenChange(next);
        if (!next) {
            setNewEmail("");
            setCurrentPassword("");
            setSent(false);
            reset();
        }
    };

    const submit = async () => {
        if (submitting) return;
        setSubmitting(true);
        reset();
        try {
            await requestEmailChange({ newEmail: newEmail.trim(), currentPassword });
            setSent(true);
        } catch (err) {
            if (err instanceof ApiError) {
                if (!captureFieldErrors(err)) {
                    toastError(err.message);
                }
            } else {
                toastError(t("genericError"));
            }
        } finally {
            setSubmitting(false);
        }
    };

    return (
        <Dialog open={open} onOpenChange={handleOpenChange}>
            <DialogContent className="sm:max-w-md">
                {sent ? (
                    <>
                        <DialogHeader>
                            <span
                                aria-hidden
                                className="mb-1 grid size-11 place-items-center rounded-xl bg-brand-light text-brand-dark"
                            >
                                <EnvelopeIcon className="size-5" />
                            </span>
                            <DialogTitle>{t("successTitle")}</DialogTitle>
                            <DialogDescription>{t("successBody", { email: newEmail.trim() })}</DialogDescription>
                        </DialogHeader>
                        <DialogFooter>
                            <DialogClose asChild>
                                <Button type="button" variant="brand">
                                    {t("close")}
                                </Button>
                            </DialogClose>
                        </DialogFooter>
                    </>
                ) : (
                    <>
                        <DialogHeader>
                            <DialogTitle>{t("title")}</DialogTitle>
                            <DialogDescription>{t("description")}</DialogDescription>
                        </DialogHeader>
                        <form
                            onSubmit={(e) => {
                                e.preventDefault();
                                void submit();
                            }}
                            className="space-y-4"
                        >
                            <div className="space-y-2">
                                <Label htmlFor="change-email-new">{t("newEmailLabel")}</Label>
                                <Input
                                    id="change-email-new"
                                    type="email"
                                    autoComplete="email"
                                    value={newEmail}
                                    onChange={(e) => {
                                        setNewEmail(e.target.value);
                                        clearError("newEmail");
                                    }}
                                    maxLength={255}
                                    aria-invalid={fieldErrors.newEmail ? true : undefined}
                                    aria-describedby={fieldErrors.newEmail ? "change-email-new-error" : undefined}
                                    autoFocus
                                    required
                                />
                                {fieldErrors.newEmail && (
                                    <p id="change-email-new-error" role="alert" className="text-sm text-destructive">
                                        {fieldErrors.newEmail}
                                    </p>
                                )}
                            </div>
                            <div className="space-y-2">
                                <Label htmlFor="change-email-password">{t("currentPasswordLabel")}</Label>
                                <Input
                                    id="change-email-password"
                                    type="password"
                                    autoComplete="current-password"
                                    value={currentPassword}
                                    onChange={(e) => {
                                        setCurrentPassword(e.target.value);
                                        clearError("currentPassword");
                                    }}
                                    maxLength={255}
                                    aria-invalid={fieldErrors.currentPassword ? true : undefined}
                                    aria-describedby={
                                        fieldErrors.currentPassword ? "change-email-password-error" : undefined
                                    }
                                    required
                                />
                                {fieldErrors.currentPassword && (
                                    <p
                                        id="change-email-password-error"
                                        role="alert"
                                        className="text-sm text-destructive"
                                    >
                                        {fieldErrors.currentPassword}
                                    </p>
                                )}
                            </div>
                            <DialogFooter>
                                <DialogClose asChild>
                                    <Button type="button" variant="outline" disabled={submitting}>
                                        {t("cancel")}
                                    </Button>
                                </DialogClose>
                                <Button
                                    type="submit"
                                    variant="brand"
                                    disabled={submitting || !newEmail.trim() || !currentPassword}
                                >
                                    {submitting ? (
                                        <>
                                            <Loader2Icon className="size-4 animate-spin" />
                                            {t("submitting")}
                                        </>
                                    ) : (
                                        t("submit")
                                    )}
                                </Button>
                            </DialogFooter>
                        </form>
                    </>
                )}
            </DialogContent>
        </Dialog>
    );
}
