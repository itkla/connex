"use client";

import { useTranslations } from "next-intl";
import { Loader2Icon } from "lucide-react";

import type { SelfOwnershipOffers } from "@/app/hooks/useSelfOwnershipOffers";
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

/**
 * Asks the offeree to acknowledge confinement before an ownership acceptance is retried.
 * @param props.offers the self-offer state whose acceptance needs the acknowledgement
 */
export default function OwnershipConfinementDialog({ offers }: { offers: SelfOwnershipOffers }) {
    const t = useTranslations("AccountInvites");
    const { busyOfferId, confinementOffer, scopeLabel, accept, dismissConfinement } = offers;

    return (
        <Dialog
            open={confinementOffer !== null}
            onOpenChange={(open) => {
                if (!open) dismissConfinement();
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
                            if (confinementOffer) void accept(confinementOffer, true);
                        }}
                    >
                        {busyOfferId !== null
                            ? <Loader2Icon className="size-4 animate-spin" />
                            : t("ownershipConfinementAccept")}
                    </Button>
                </DialogFooter>
            </DialogContent>
        </Dialog>
    );
}
