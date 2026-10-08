"use client";

import { useState } from "react";
import { useTranslations } from "next-intl";
import { Loader2Icon } from "lucide-react";
import { startAuthentication } from "@simplewebauthn/browser";

import {
    ApiError,
    beginMfaAttestation,
    completePrivilegedMfaEnrollment,
    MFA_ATTESTATION_REFUSED_CODE,
    redeemMfaAttestation,
} from "@/app/lib/api";
import { formatPasskeyApprovalCode, isCompletePasskeyApprovalCode } from "@/app/lib/passkeyApprovalCode";
import { isWebAuthnCancellation } from "@/app/lib/webauthnCancellation";
import { useApiErrorToast } from "@/app/hooks/useApiErrorToast";
import { toastError, toastInfo } from "@/app/lib/toast";
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

const CODE_INPUT_ID = "passkey-approval-code";
const CODE_ERROR_ID = "passkey-approval-code-error";

/**
 * Redeems a passkey approval code (#1534). The member enters the code an administrator gave them,
 * then confirms with a passkey, and the passkey that confirms is the one the code approves.
 *
 * Every reason a code can't be used reads the same, because the server answers them alike: the
 * dialog never tells someone guessing whether a code was wrong, already used, turned off or expired.
 * Each attempt asks the server for a fresh passkey challenge, since a refused attempt spends the
 * last one. The server answers a passkey it could not verify with a 401, which elsewhere means the
 * session ended; once the prompt has been answered, that 401 is reported as a failed passkey check
 * and the dialog stays open for another try, rather than sending a signed-in member to sign in.
 * A redemption that succeeds releases the browser's privileged-MFA confinement, as enrolling a
 * passkey does; the server stays the authority and confines the browser again if it still must.
 *
 * @param open whether the dialog is showing
 * @param onOpenChange called when the dialog asks to open or close; ignored while a step is running
 * @param onApproved called with the organization whose administrator access the passkey now counts for
 */
export default function PasskeyApprovalDialog({
    open,
    onOpenChange,
    onApproved,
}: {
    open: boolean;
    onOpenChange: (open: boolean) => void;
    onApproved: (orgId: number) => void;
}) {
    const t = useTranslations("AccountSecurity");
    const tStepUp = useTranslations("PasskeyStepUp");
    const showApiError = useApiErrorToast("AccountSecurity");
    const [code, setCode] = useState("");
    const [refused, setRefused] = useState(false);
    const [busy, setBusy] = useState(false);
    const complete = isCompletePasskeyApprovalCode(code);

    const reset = () => {
        setCode("");
        setRefused(false);
    };

    const submit = async () => {
        if (busy || !complete) return;
        setBusy(true);
        setRefused(false);
        let answered = false;
        try {
            const optionsJSON = await beginMfaAttestation();
            const credential = await startAuthentication({ optionsJSON });
            answered = true;
            const redeemed = await redeemMfaAttestation(code, credential);
            completePrivilegedMfaEnrollment();
            reset();
            onOpenChange(false);
            onApproved(redeemed.orgId);
        } catch (err) {
            if (isWebAuthnCancellation(err)) {
                toastInfo(t("stepUpCanceled"));
            } else if (err instanceof ApiError && err.code === MFA_ATTESTATION_REFUSED_CODE) {
                setRefused(true);
            } else if (answered && err instanceof ApiError && err.status === 401) {
                toastError(tStepUp("failed"));
            } else {
                showApiError(err, "approvalFailed");
            }
        } finally {
            setBusy(false);
        }
    };

    return (
        <Dialog
            open={open}
            onOpenChange={(next) => {
                if (busy) return;
                if (!next) reset();
                onOpenChange(next);
            }}
        >
            <DialogContent className="sm:max-w-md">
                <DialogHeader>
                    <DialogTitle>{t("approvalDialogTitle")}</DialogTitle>
                    <DialogDescription>{t("approvalDialogDescription")}</DialogDescription>
                </DialogHeader>
                <form
                    onSubmit={(e) => {
                        e.preventDefault();
                        void submit();
                    }}
                    className="space-y-4"
                >
                    <div className="space-y-2">
                        <Label htmlFor={CODE_INPUT_ID}>{t("approvalCodeLabel")}</Label>
                        <Input
                            id={CODE_INPUT_ID}
                            value={code}
                            onChange={(e) => {
                                setCode(formatPasskeyApprovalCode(e.target.value));
                                setRefused(false);
                            }}
                            placeholder={t("approvalCodePlaceholder")}
                            autoComplete="one-time-code"
                            autoCapitalize="characters"
                            autoCorrect="off"
                            spellCheck={false}
                            className="font-mono tracking-widest"
                            aria-invalid={refused}
                            aria-describedby={refused ? CODE_ERROR_ID : undefined}
                            autoFocus
                            required
                        />
                        {refused && (
                            <p id={CODE_ERROR_ID} role="alert" className="text-sm text-destructive">
                                {t("approvalRefused")}
                            </p>
                        )}
                    </div>
                    <DialogFooter>
                        <DialogClose asChild>
                            <Button type="button" variant="outline" disabled={busy}>
                                {t("cancel")}
                            </Button>
                        </DialogClose>
                        <Button type="submit" variant="brand" disabled={busy || !complete} aria-busy={busy}>
                            {busy ? <Loader2Icon className="size-4 animate-spin" /> : t("continue")}
                        </Button>
                    </DialogFooter>
                </form>
            </DialogContent>
        </Dialog>
    );
}
