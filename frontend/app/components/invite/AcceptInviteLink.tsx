"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { useTranslations } from "next-intl";
import { Loader2Icon } from "lucide-react";

import { acceptInviteLink } from "@/app/lib/api";
import { toastError } from "@/app/lib/toast";
import { Button } from "@/components/ui/button";

export default function AcceptInviteLink({ token }: { token: string }) {
    const t = useTranslations("InviteLinkAccept");
    const router = useRouter();
    const [busy, setBusy] = useState(false);

    const accept = async () => {
        setBusy(true);
        try {
            await acceptInviteLink(token);
            router.replace("/dashboard");
            router.refresh();
        } catch (err) {
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
