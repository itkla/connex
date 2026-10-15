"use client";

import { useCallback, useEffect, useState } from "react";
import { usePathname, useRouter } from "next/navigation";
import { useTranslations } from "next-intl";

import type { OwnershipOffer } from "@/app/lib/types";
import {
    acceptOwnershipOffer,
    ApiError,
    declineOwnershipOffer,
    getMyOwnershipOffers,
    privilegedMfaConfinementDestination,
} from "@/app/lib/api";
import { useApiErrorToast } from "@/app/hooks/useApiErrorToast";
import { usePasskeyStepUpErrorHandler } from "@/app/hooks/usePasskeyStepUpError";
import { toastError, toastSuccess } from "@/app/lib/toast";

const ONBOARDING_ROUTE = "/onboarding";

/** The caller's own pending ownership offers and the consent actions on them. */
export type SelfOwnershipOffers = {
    /** Open offers addressed to the signed-in account. */
    offers: OwnershipOffer[];
    /** The offer whose accept or decline is in flight, if any. */
    busyOfferId: string | null;
    /** The offer awaiting the confinement acknowledgement, if any. */
    confinementOffer: OwnershipOffer | null;
    /** The scope's display name, or a generic scope label when the name is unavailable. */
    scopeLabel: (offer: OwnershipOffer) => string;
    /** Accepts an offer; a confinement refusal opens the acknowledgement instead of failing. */
    accept: (offer: OwnershipOffer, acknowledgeConfinement?: boolean) => Promise<void>;
    /** Declines an offer; no step-up is needed. */
    decline: (offer: OwnershipOffer) => Promise<void>;
    /** Closes the confinement acknowledgement unless an accept is in flight. */
    dismissConfinement: () => void;
};

/**
 * Loads and acts on the signed-in account's ownership offers. It works on any authenticated
 * surface, including onboarding, so an organization offer stays reachable for an account that
 * has no workspace. Acceptance retries with the confinement acknowledgement only after the
 * offeree confirms it, and the page refreshes so new ownership shows at once. An acceptance that
 * leaves the account confined goes to the Security step instead, except on onboarding, which
 * shows the confinement itself.
 * @returns the open offers and their consent actions
 */
export function useSelfOwnershipOffers(): SelfOwnershipOffers {
    const t = useTranslations("AccountInvites");
    const showApiError = useApiErrorToast("AccountInvites");
    const handlePasskeyStepUpError = usePasskeyStepUpErrorHandler();
    const pathname = usePathname();
    const router = useRouter();
    const [offers, setOffers] = useState<OwnershipOffer[]>([]);
    const [busyOfferId, setBusyOfferId] = useState<string | null>(null);
    const [confinementOffer, setConfinementOffer] = useState<OwnershipOffer | null>(null);

    useEffect(() => {
        let cancelled = false;
        getMyOwnershipOffers().then((loaded) => {
            if (!cancelled) setOffers(loaded.filter((offer) => offer.state === "OPEN"));
        }, () => {
            if (!cancelled) toastError(t("offersLoadFailed"));
        });
        return () => {
            cancelled = true;
        };
    }, [t]);

    const scopeLabel = useCallback((offer: OwnershipOffer) =>
        offer.scopeName?.trim()
        || (offer.scopeKind === "ORGANIZATION" ? t("ownershipScopeOrganization") : t("ownershipScopeWorkspace")),
    [t]);

    const accept = useCallback(async (offer: OwnershipOffer, acknowledgeConfinement = false) => {
        setBusyOfferId(offer.id);
        try {
            await acceptOwnershipOffer(offer.id, acknowledgeConfinement);
            setOffers((prev) => prev.filter((existing) => existing.id !== offer.id));
            setConfinementOffer(null);
            toastSuccess(t("ownershipAccepted", { scope: scopeLabel(offer) }));
            const destination = privilegedMfaConfinementDestination();
            if (destination !== null && pathname !== ONBOARDING_ROUTE) {
                router.replace(destination);
            } else {
                router.refresh();
            }
        } catch (err) {
            if (err instanceof ApiError && err.code === "PRIVILEGED_MFA_CONFINEMENT_ACKNOWLEDGEMENT_REQUIRED") {
                setConfinementOffer(offer);
            } else if (!handlePasskeyStepUpError(err)) {
                showApiError(err, "ownershipAcceptFailed");
            }
        } finally {
            setBusyOfferId(null);
        }
    }, [handlePasskeyStepUpError, pathname, router, scopeLabel, showApiError, t]);

    const decline = useCallback(async (offer: OwnershipOffer) => {
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
    }, [showApiError, t]);

    const dismissConfinement = useCallback(() => {
        if (busyOfferId === null) setConfinementOffer(null);
    }, [busyOfferId]);

    return { offers, busyOfferId, confinementOffer, scopeLabel, accept, decline, dismissConfinement };
}
