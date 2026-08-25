"use client";

import { useState } from "react";
import { useTranslations } from "next-intl";
import { CheckCircleIcon, EnvelopeIcon } from "@heroicons/react/24/outline";
import { Loader2Icon } from "lucide-react";
import { Button } from "@/components/ui/button";
import { confirmUnsubscribe } from "@/app/lib/api";
import { type DeliveryUnsubscribeInfo } from "@/app/lib/types";
import { toastError } from "@/app/lib/toast";

/**
 * Public unsubscribe confirmation card. Shows the masked recipient address and channel, confirms the
 * opt-out against the token, then settles into a calm done state. Idempotent: an already-unsubscribed
 * token opens directly in the done state.
 */
export default function UnsubscribeConfirm({
    token,
    info,
}: {
    token: string;
    info: DeliveryUnsubscribeInfo;
}) {
    const t = useTranslations("Unsubscribe");
    const [done, setDone] = useState(info.unsubscribed);
    const [isSubmitting, setIsSubmitting] = useState(false);

    const channelLabel = info.channel === "email" ? t("channels.email") : info.channel;

    const confirm = async () => {
        setIsSubmitting(true);
        try {
            await confirmUnsubscribe(token);
            setDone(true);
        } catch {
            toastError(t("errorTitle"), { description: t("errorBody") });
        } finally {
            setIsSubmitting(false);
        }
    };

    if (done) {
        return (
            <div
                className="flex flex-col items-center text-center transition duration-300 ease-out transform-gpu starting:scale-[0.98] starting:opacity-0 motion-reduce:transition-none"
            >
                <span
                    aria-hidden
                    className="grid size-12 place-items-center rounded-full bg-brand-light text-brand-dark"
                >
                    <CheckCircleIcon className="size-6" />
                </span>
                <h1 className="mt-5 text-xl font-semibold tracking-tight text-foreground text-balance">
                    {info.unsubscribed ? t("alreadyTitle") : t("doneTitle")}
                </h1>
                <p className="mt-2 text-sm text-muted-foreground text-pretty">
                    {info.unsubscribed ? t("alreadyBody") : t("doneBody")}
                </p>
                <p className="mt-8 text-xs text-muted-foreground">{t("footer")}</p>
            </div>
        );
    }

    return (
        <div className="flex flex-col">
            <span
                aria-hidden
                className="grid size-11 place-items-center rounded-xl bg-muted text-muted-foreground"
            >
                <EnvelopeIcon className="size-5" />
            </span>
            <h1 className="mt-5 text-xl font-semibold tracking-tight text-foreground text-balance">
                {t("title")}
            </h1>
            <p className="mt-2 text-sm text-muted-foreground text-pretty">{t("description")}</p>

            <dl className="mt-6 divide-y divide-border rounded-xl border border-border">
                <div className="flex items-center justify-between gap-4 px-4 py-3">
                    <dt className="text-sm text-muted-foreground">{t("channelLabel")}</dt>
                    <dd className="text-sm font-medium text-foreground">{channelLabel}</dd>
                </div>
                <div className="flex items-center justify-between gap-4 px-4 py-3">
                    <dt className="text-sm text-muted-foreground">{t("addressLabel")}</dt>
                    <dd className="truncate text-sm font-medium text-foreground">{info.address}</dd>
                </div>
            </dl>

            <Button
                type="button"
                variant="brand"
                onClick={confirm}
                disabled={isSubmitting}
                className="mt-6 h-10 w-full"
            >
                {isSubmitting ? (
                    <>
                        <Loader2Icon className="size-4 animate-spin" />
                        {t("confirming")}
                    </>
                ) : (
                    t("confirm")
                )}
            </Button>

            <p className="mt-8 text-center text-xs text-muted-foreground">{t("footer")}</p>
        </div>
    );
}
