"use client";

import { useEffect, useState } from "react";
import { useTranslations } from "next-intl";
import { Loader2Icon } from "lucide-react";

import UnsubscribeConfirm from "@/app/components/marketing/campaigns/UnsubscribeConfirm";
import {
    exchangeUnsubscribeToken,
    getUnsubscribeInfo,
} from "@/app/lib/api";
import { takeOneTimeLinkToken } from "@/app/lib/oneTimeLink";
import type { DeliveryUnsubscribeInfo } from "@/app/lib/types";

type EntryState =
    | { status: "loading" }
    | { status: "invalid" }
    | { status: "ready"; info: DeliveryUnsubscribeInfo };

/**
 * Exchanges the emailed unsubscribe fragment bearer for a purpose-bound browser grant and renders
 * the opt-out confirmation entirely from that grant, so no request ever names the bearer.
 */
export default function UnsubscribeEntry() {
    const t = useTranslations("Unsubscribe");
    const [state, setState] = useState<EntryState>({ status: "loading" });

    useEffect(() => {
        let active = true;

        const establish = async () => {
            const token = takeOneTimeLinkToken();
            if (token) {
                await exchangeUnsubscribeToken(token);
                window.location.replace("/unsubscribe");
                return;
            }
            const info = await getUnsubscribeInfo();
            if (active) setState({ status: "ready", info });
        };

        establish().catch(() => {
            if (active) setState({ status: "invalid" });
        });

        return () => {
            active = false;
        };
    }, []);

    return (
        <main className="grid min-h-dvh place-items-center bg-background px-6 py-12">
            <div className="w-full max-w-md rounded-2xl border border-border bg-card p-8 shadow-sm">
                {state.status === "loading" ? (
                    <div
                        role="status"
                        className="flex items-center justify-center gap-3 text-sm text-muted-foreground"
                    >
                        <Loader2Icon aria-hidden="true" className="size-4 animate-spin" />
                        {t("loading")}
                    </div>
                ) : state.status === "ready" ? (
                    <UnsubscribeConfirm info={state.info} />
                ) : (
                    <div className="flex flex-col text-center">
                        <h1 className="text-xl font-semibold tracking-tight text-foreground text-balance">
                            {t("invalidTitle")}
                        </h1>
                        <p className="mt-2 text-sm text-muted-foreground text-pretty">
                            {t("invalidBody")}
                        </p>
                    </div>
                )}
            </div>
        </main>
    );
}
