import type { Metadata } from "next";
import { getTranslations } from "next-intl/server";

import { getUnsubscribeInfo } from "@/app/lib/api";
import { type DeliveryUnsubscribeInfo } from "@/app/lib/types";
import UnsubscribeConfirm from "@/app/components/marketing/campaigns/UnsubscribeConfirm";

const TOKEN_PATTERN = /^[a-f0-9]{64}$/;

export async function generateMetadata(): Promise<Metadata> {
    const t = await getTranslations("Unsubscribe");
    return { title: `${t("title")} — Connex`, robots: { index: false, follow: false } };
}

export default async function UnsubscribePage({
    params,
}: {
    params: Promise<{ token: string }>;
}) {
    const { token } = await params;
    const t = await getTranslations("Unsubscribe");

    let info: DeliveryUnsubscribeInfo | null = null;
    if (TOKEN_PATTERN.test(token)) {
        try {
            info = await getUnsubscribeInfo(token);
        } catch {
            info = null;
        }
    }

    return (
        <main className="grid min-h-dvh place-items-center bg-background px-6 py-12">
            <div className="w-full max-w-md rounded-2xl border border-border bg-card p-8 shadow-sm">
                {info ? (
                    <UnsubscribeConfirm token={token} info={info} />
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
