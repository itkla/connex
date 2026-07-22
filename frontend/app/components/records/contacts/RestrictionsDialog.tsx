"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { useTranslations } from "next-intl";

import {
    Dialog,
    DialogContent,
    DialogHeader,
    DialogTitle,
    DialogDescription,
    DialogFooter,
    DialogClose,
} from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { Switch } from "@/components/ui/switch";
import { DialogStatusCover, resolveDialogStatus } from "@/components/ui/dialog-status-cover";
import type { Contact } from "@/app/lib/types";
import { updateContactRestrictions } from "@/app/lib/api";
import { toastError, toastSuccess } from "@/app/lib/toast";

type Props = {
    open: boolean;
    onOpenChange: (open: boolean) => void;
    contact: Contact;
};

/**
 * APPI processing-restriction controls for a contact (issue #221): suspend processing
 * (cease of use, Art. 35) and cease third-party provision. Cease-of-provision also
 * revokes every standing cross-workspace share, so its consequence line is explicit.
 */
export default function RestrictionsDialog({ open, onOpenChange, contact }: Props) {
    const t = useTranslations("ContactRestrictions");
    const router = useRouter();

    const [suspended, setSuspended] = useState(Boolean(contact.suspendedAt));
    const [provisionCeased, setProvisionCeased] = useState(Boolean(contact.provisionCeasedAt));
    const [isSaving, setIsSaving] = useState(false);
    const [succeeded, setSucceeded] = useState(false);

    const locked = isSaving || succeeded;
    const dirty = suspended !== Boolean(contact.suspendedAt) || provisionCeased !== Boolean(contact.provisionCeasedAt);
    const status = resolveDialogStatus({ isLoading: isSaving, hasErrors: false, isSuccess: succeeded });

    const handleOpenChange = (next: boolean) => {
        if (!next && locked) return;
        if (next) {
            setSuspended(Boolean(contact.suspendedAt));
            setProvisionCeased(Boolean(contact.provisionCeasedAt));
        }
        onOpenChange(next);
    };

    const handleSubmit = async () => {
        if (locked || !dirty) return;
        setIsSaving(true);
        try {
            await updateContactRestrictions(contact.id, { suspended, provisionCeased });
            setIsSaving(false);
            setSucceeded(true);
            toastSuccess(t("toastSaved"));
            setTimeout(() => {
                setSucceeded(false);
                onOpenChange(false);
                router.refresh();
            }, 900);
        } catch (err) {
            setIsSaving(false);
            toastError(err instanceof Error ? err.message : t("toastFailed"));
        }
    };

    return (
        <Dialog open={open} onOpenChange={handleOpenChange}>
            <DialogContent className="gap-0 overflow-hidden p-0 sm:max-w-md">
                <DialogStatusCover status={status} />
                <div className="px-6 pb-6">
                    <DialogHeader className="ncd-rise -mt-12 mb-5" style={{ animationDelay: "40ms" }}>
                        <DialogTitle className="text-xl font-semibold tracking-tight">{t("title")}</DialogTitle>
                        <DialogDescription>{t("description", { name: contact.name })}</DialogDescription>
                    </DialogHeader>

                    <form
                        onSubmit={(e) => {
                            e.preventDefault();
                            void handleSubmit();
                        }}
                        className="grid gap-5"
                    >
                        <div className="ncd-rise flex items-start justify-between gap-4" style={{ animationDelay: "90ms" }}>
                            <div className="grid gap-1">
                                <Label htmlFor="restrict-suspend">{t("suspendLabel")}</Label>
                                <p className="text-xs text-muted-foreground">{t("suspendHint")}</p>
                            </div>
                            <Switch
                                id="restrict-suspend"
                                checked={suspended}
                                onCheckedChange={setSuspended}
                                disabled={locked}
                            />
                        </div>

                        <div className="ncd-rise flex items-start justify-between gap-4" style={{ animationDelay: "140ms" }}>
                            <div className="grid gap-1">
                                <Label htmlFor="restrict-provision">{t("provisionLabel")}</Label>
                                <p className="text-xs text-muted-foreground">{t("provisionHint")}</p>
                            </div>
                            <Switch
                                id="restrict-provision"
                                checked={provisionCeased}
                                onCheckedChange={setProvisionCeased}
                                disabled={locked}
                            />
                        </div>

                        {provisionCeased && !contact.provisionCeasedAt && (
                            <p
                                className="ncd-rise rounded-md bg-destructive/10 px-3 py-2 text-xs text-destructive"
                                style={{ animationDelay: "160ms" }}
                                role="alert"
                            >
                                {t("provisionWarning")}
                            </p>
                        )}

                        <DialogFooter className="ncd-rise" style={{ animationDelay: "190ms" }}>
                            <DialogClose asChild>
                                <Button type="button" variant="outline" disabled={locked}>
                                    {t("cancel")}
                                </Button>
                            </DialogClose>
                            <Button
                                type="submit"
                                variant="brand"
                                disabled={locked || !dirty}
                                className="min-w-24 shadow-sm transition hover:shadow-md"
                            >
                                {t("save")}
                            </Button>
                        </DialogFooter>
                    </form>
                </div>
            </DialogContent>
        </Dialog>
    );
}
