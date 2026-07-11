"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { useTranslations } from "next-intl";
import { Loader2Icon } from "lucide-react";

import { acceptInvite } from "@/app/lib/api";
import { toastError } from "@/app/lib/toast";
import { Button } from "@/components/ui/button";

export default function AcceptInvite({ token }: { token: string }) {
    const t = useTranslations("InviteAccept");
    const router = useRouter();
    const [busy, setBusy] = useState(false);

    const accept = async () => {
        setBusy(true);
        try {
            const workspace = await acceptInvite(token);
            document.cookie = `connex_workspace=${workspace.id};path=/;max-age=31536000;samesite=lax`;
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
