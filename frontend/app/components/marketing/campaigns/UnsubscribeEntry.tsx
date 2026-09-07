"use client";

import { useEffect, useState } from "react";
import { useTranslations } from "next-intl";
import { Loader2Icon } from "lucide-react";

import UnsubscribeConfirm from "@/app/components/marketing/campaigns/UnsubscribeConfirm";
import {
    ApiError,
    exchangeUnsubscribeToken,
    getUnsubscribeInfo,
} from "@/app/lib/api";
import { takeOneTimeLinkToken } from "@/app/lib/oneTimeLink";
import type { DeliveryUnsubscribeInfo } from "@/app/lib/types";

type EntryState =
    | { status: "loading" }
    | { status: "invalid" }
    | { status: "throttled" }
    | { status: "ready"; info: DeliveryUnsubscribeInfo };

/**
 * Classifies a failed exchange or preview read.
 *
 * <p>The exchange shares one per-source budget with every other one-time link, so a recipient
 * behind a busy NAT can be refused with 429 while their link is perfectly good. Reporting that as
 * "invalid or expired" would tell them to go and ask the sender instead of simply retrying, so the
 * throttle keeps its own state. Every other status stays uniform and non-diagnostic.
 * @param error the rejection from the exchange or preview call
 * @returns the entry state the failure should render
 */
function failureStatus(error: unknown): "invalid" | "throttled" {
    return error instanceof ApiError && error.status === 429 ? "throttled" : "invalid";
}

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

        establish().catch((error: unknown) => {
            if (active) setState({ status: failureStatus(error) });
        });

        return () => {
            active = false;
        };
    }, []);

    useEffect(() => {
        const reopen = () => window.location.reload();
        window.addEventListener("hashchange", reopen);
        return () => window.removeEventListener("hashchange", reopen);
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
                            {t(state.status === "throttled" ? "throttledTitle" : "invalidTitle")}
                        </h1>
                        <p className="mt-2 text-sm text-muted-foreground text-pretty">
                            {t(state.status === "throttled" ? "throttledBody" : "invalidBody")}
                        </p>
                    </div>
                )}
            </div>
        </main>
    );
}
