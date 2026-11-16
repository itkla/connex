"use client";

import { useEffect, useState } from "react";
import { useTranslations } from "next-intl";
import { Loader2Icon } from "lucide-react";

import type { Passkey } from "@/app/lib/types";
import { getPasskeys } from "@/app/lib/api";
import type { PrivilegedMfaStandingState } from "@/app/hooks/usePrivilegedMfaStanding";
import { useConfinementExits } from "@/app/hooks/useConfinementExits";
import { usePasskeyEnrollment } from "@/app/hooks/usePasskeyEnrollment";
import { usePasskeySupport } from "@/app/hooks/usePasskeySupport";
import PasskeyApprovalDialog from "@/app/components/account/PasskeyApprovalDialog";
import PasskeyEnrollmentPasswordDialog from "@/app/components/account/PasskeyEnrollmentPasswordDialog";
import PrivilegedMfaConfinementCard from "@/app/components/account/PrivilegedMfaConfinementCard";
import { toastError, toastSuccess } from "@/app/lib/toast";
import { Button } from "@/components/ui/button";

/**
 * Lets a confined privileged account with no workspace act from onboarding (#1534). The app's
 * pages, Security among them, need a workspace, so an account privileged only through an
 * organization would otherwise have nowhere to add its first passkey, redeem an approval code or
 * leave the organization. It shows the same confinement card as Security, with the enrollment and
 * redemption ceremonies and the organization exits, and a recheck for when another tab or another
 * person changed the standing.
 * @param props.standing the account's standing as onboarding reads it
 */
export default function OnboardingConfinement({ standing }: { standing: PrivilegedMfaStandingState }) {
    const t = useTranslations("AccountSecurity");
    const tOnboarding = useTranslations("Onboarding");
    const supported = usePasskeySupport();
    const [passkeys, setPasskeys] = useState<Passkey[]>([]);
    const [passkeysState, setPasskeysState] = useState<"loading" | "ready" | "failed">("loading");
    const [passkeysReload, setPasskeysReload] = useState(0);
    const [approvalOpen, setApprovalOpen] = useState(false);
    const { requirements, reason, refresh } = standing;
    const exits = useConfinementExits();

    useEffect(() => {
        let cancelled = false;
        (async () => {
            setPasskeysState("loading");
            try {
                const loaded = await getPasskeys();
                if (cancelled) return;
                setPasskeys(loaded);
                setPasskeysState("ready");
            } catch {
                if (!cancelled) setPasskeysState("failed");
            }
        })();
        return () => {
            cancelled = true;
        };
    }, [requirements, passkeysReload]);

    const enrollment = usePasskeyEnrollment(
        {
            hasPasskeys: passkeys.length > 0,
            currentPasswordRequired: requirements?.currentPasswordRequired ?? false,
            emailConfirmationRequired: requirements?.emailConfirmationRequired ?? false,
            emailConfirmationSatisfied: requirements?.emailConfirmationSatisfied ?? false,
        },
        {
            onEnrolled: async () => {
                const [loaded, standing] = await Promise.all([getPasskeys(), refresh("ceremony")]);
                setPasskeys(loaded);
                return standing;
            },
            onProofRejected: () => void refresh("check"),
        },
    );

    const cardReason = reason
        ?? (requirements?.standing === "ATTESTATION_REQUIRED" ? "attestation" : "enrollment");
    const awaitingConfirmation = passkeys.length === 0
        && (requirements?.emailConfirmationRequired ?? false)
        && !(requirements?.emailConfirmationSatisfied ?? false);

    const action = !supported ? (
        <p className="text-sm text-muted-foreground">{t("unsupported")}</p>
    ) : cardReason === "attestation" && passkeysState === "failed" ? (
        <>
            <p className="text-sm text-muted-foreground">{t("loadFailed")}</p>
            <Button
                type="button"
                variant="outline"
                size="inline"
                onClick={() => setPasskeysReload((count) => count + 1)}
            >
                {t("retry")}
            </Button>
        </>
    ) : cardReason === "attestation" ? (
        <Button
            type="button"
            variant="brand"
            size="inline"
            disabled={passkeysState !== "ready"}
            aria-busy={passkeysState === "loading"}
            onClick={() => setApprovalOpen(true)}
        >
            {passkeysState === "loading" ? <Loader2Icon className="size-4 animate-spin" /> : t("approvalEnter")}
        </Button>
    ) : awaitingConfirmation ? (
        <>
            <p className="w-full max-w-prose text-sm text-muted-foreground">{t("confirmationDescription")}</p>
            <Button
                type="button"
                variant="brand"
                size="inline"
                disabled={enrollment.sendingConfirmation}
                onClick={() => void enrollment.sendConfirmation()}
            >
                {enrollment.sendingConfirmation ? <Loader2Icon className="size-4 animate-spin" /> : t("confirmationSend")}
            </Button>
        </>
    ) : (
        <Button
            type="button"
            variant="brand"
            size="inline"
            disabled={enrollment.adding}
            aria-busy={enrollment.adding}
            onClick={() => void enrollment.addPasskey()}
        >
            {enrollment.adding ? <Loader2Icon className="size-4 animate-spin" /> : t("add")}
        </Button>
    );

    return (
        <div className="mt-8">
            <PrivilegedMfaConfinementCard
                reason={cardReason}
                requirements={requirements}
                passkeys={passkeys}
                action={action}
                exits={exits}
                canLeaveWorkspaces={false}
                footer={(
                    <Button
                        type="button"
                        variant="ghost"
                        size="inline"
                        className="mt-3"
                        onClick={() => void refresh("check")}
                    >
                        {tOnboarding("confinedRecheck")}
                    </Button>
                )}
            />
            <PasskeyEnrollmentPasswordDialog password={enrollment.password} />
            <PasskeyApprovalDialog
                open={approvalOpen}
                onOpenChange={setApprovalOpen}
                onApproved={(orgId) => {
                    const organization = requirements?.approvalGaps?.find((gap) => gap.orgId === orgId)?.orgName;
                    toastSuccess(t("approvalDone"), {
                        description: organization
                            ? t("approvalDoneDescription", { organization })
                            : t("approvalDoneDescriptionGeneric"),
                    });
                    void Promise.allSettled([getPasskeys(), refresh("ceremony")]).then(([loaded, settled]) => {
                        if (loaded.status === "fulfilled") setPasskeys(loaded.value);
                        if (loaded.status === "rejected" || settled.status === "rejected" || settled.value === "failed") {
                            toastError(t("approvalRefreshFailed"), {
                                description: t("approvalRefreshFailedDescription"),
                            });
                        }
                    });
                }}
            />
        </div>
    );
}
