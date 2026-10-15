"use client";

import { useTranslations } from "next-intl";

import type { ConfinementAcknowledgement } from "@/app/hooks/useConfinementAcknowledgement";
import { Button } from "@/components/ui/button";
import {
    Dialog,
    DialogContent,
    DialogDescription,
    DialogFooter,
    DialogHeader,
    DialogTitle,
} from "@/components/ui/dialog";

/**
 * Asks whether to go ahead with a role change that confines members until an administrator
 * approves one of their passkeys in the organization (#1534). Closing it abandons the change.
 * @param props.acknowledgement the runner whose question this dialog answers
 */
export default function ConfinementAcknowledgementDialog({
    acknowledgement,
}: {
    acknowledgement: ConfinementAcknowledgement;
}) {
    const t = useTranslations("ConfinementAcknowledgement");
    const { pending, answer } = acknowledgement;

    return (
        <Dialog
            open={pending !== null}
            onOpenChange={(open) => {
                if (!open) answer(false);
            }}
        >
            <DialogContent className="sm:max-w-md">
                <DialogHeader>
                    <DialogTitle>{t("title")}</DialogTitle>
                    <DialogDescription>
                        {pending?.affectedCount != null
                            ? t("body", { count: pending.affectedCount })
                            : t("bodyUnknownCount")}
                    </DialogDescription>
                </DialogHeader>
                <DialogFooter>
                    <Button variant="outline" onClick={() => answer(false)}>
                        {t("cancel")}
                    </Button>
                    <Button variant="brand" onClick={() => answer(true)}>
                        {t("confirm")}
                    </Button>
                </DialogFooter>
            </DialogContent>
        </Dialog>
    );
}
