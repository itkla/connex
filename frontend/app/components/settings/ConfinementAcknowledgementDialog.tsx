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
 * approves one of their passkeys in the organization (#1534). It names the change it asks about,
 * and its answers are bound to that question; closing it abandons the change.
 * @param props.acknowledgement the runner whose open question this dialog answers
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
                if (!open && pending) answer(pending.id, false);
            }}
        >
            {pending && (
                <DialogContent key={pending.id} className="sm:max-w-md">
                    <DialogHeader>
                        <DialogTitle>{t("title")}</DialogTitle>
                        <DialogDescription>
                            <span className="block font-medium text-foreground">{pending.subject}</span>
                            <span className="mt-2 block">
                                {pending.affectedCount != null
                                    ? t("body", { count: pending.affectedCount })
                                    : t("bodyUnknownCount")}
                            </span>
                        </DialogDescription>
                    </DialogHeader>
                    <DialogFooter>
                        <Button variant="outline" onClick={() => answer(pending.id, false)}>
                            {t("cancel")}
                        </Button>
                        <Button variant="brand" onClick={() => answer(pending.id, true)}>
                            {t("confirm")}
                        </Button>
                    </DialogFooter>
                </DialogContent>
            )}
        </Dialog>
    );
}
