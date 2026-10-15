"use client";

import { useTranslations } from "next-intl";
import { Loader2Icon } from "lucide-react";

import type { PasskeyEnrollmentPassword } from "@/app/hooks/usePasskeyEnrollment";
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

/**
 * Asks for the current password a privileged, password-backed account's first passkey needs.
 * @param props.password the enrollment's password step
 */
export default function PasskeyEnrollmentPasswordDialog({ password }: { password: PasskeyEnrollmentPassword }) {
    const t = useTranslations("AccountSecurity");

    return (
        <Dialog open={password.open} onOpenChange={password.setOpen}>
            <DialogContent className="sm:max-w-md">
                <DialogHeader>
                    <DialogTitle>{t("passwordTitle")}</DialogTitle>
                    <DialogDescription>{t("passwordDescription")}</DialogDescription>
                </DialogHeader>
                <form
                    onSubmit={(e) => {
                        e.preventDefault();
                        void password.confirm();
                    }}
                    className="space-y-4"
                >
                    <div className="space-y-2">
                        <Label htmlFor="passkey-current-password">{t("passwordLabel")}</Label>
                        <Input
                            id="passkey-current-password"
                            type="password"
                            autoComplete="current-password"
                            value={password.value}
                            onChange={(e) => password.setValue(e.target.value)}
                            maxLength={255}
                            aria-invalid={password.error !== null}
                            aria-describedby={password.error ? "passkey-current-password-error" : undefined}
                            autoFocus
                            required
                        />
                        {password.error && (
                            <p id="passkey-current-password-error" role="alert" className="text-sm text-destructive">
                                {password.error}
                            </p>
                        )}
                    </div>
                    <DialogFooter>
                        <DialogClose asChild>
                            <Button type="button" variant="outline" disabled={password.confirming}>
                                {t("cancel")}
                            </Button>
                        </DialogClose>
                        <Button
                            type="submit"
                            disabled={password.confirming || !password.value}
                            className="bg-brand text-white hover:bg-brand-hover"
                        >
                            {password.confirming ? <Loader2Icon className="size-4 animate-spin" /> : t("continue")}
                        </Button>
                    </DialogFooter>
                </form>
            </DialogContent>
        </Dialog>
    );
}
