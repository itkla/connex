"use client";

import { useState } from "react";
import { useTranslations } from "next-intl";
import { Loader2Icon } from "lucide-react";

import { acceptInvite, WorkspaceSelectionUnavailableError } from "@/app/lib/api";
import { toastError } from "@/app/lib/toast";
import { Button } from "@/components/ui/button";

/** Accepts the emailed invite represented by the exact flow identity shown in its preview. */
export default function AcceptInvite({ flowId }: { flowId: string }) {
    const t = useTranslations("InviteAccept");
    const [busy, setBusy] = useState(false);

    const accept = async () => {
        setBusy(true);
        try {
            await acceptInvite(flowId);
            window.location.replace("/dashboard");
        } catch (err) {
            if (err instanceof WorkspaceSelectionUnavailableError) {
                window.location.replace("/dashboard");
                return;
            }
            toastError(err instanceof Error ? err.message : t("acceptFailed"));
            setBusy(false);
        }
    };

    return (
        <Button
            onClick={accept}
            variant="brand"
            disabled={busy}
            className="h-11 w-full shadow-sm transition hover:shadow-md"
        >
            {busy ? <Loader2Icon className="size-4 animate-spin" /> : t("accept")}
        </Button>
    );
}
