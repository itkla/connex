import type { Metadata } from "next";
import { headers } from "next/headers";
import { NextIntlClientProvider } from "next-intl";
import { getTranslations } from "next-intl/server";
import { cache } from "react";

import DocumentAcceptance from "@/app/components/marketing/campaigns/DocumentAcceptance";
import DocumentAcceptanceUnavailable, {
    type DocumentAcceptanceUnavailableCopy,
} from "@/app/components/marketing/campaigns/DocumentAcceptanceUnavailable";
import {
    documentAcceptanceFailureKind,
    getDocumentAcceptancePreview,
} from "@/app/lib/api";
import type {
    DocumentAcceptanceFailureKind,
    DocumentAcceptancePreview,
} from "@/app/lib/types";
import { defaultLocale, resolveLocale, type Locale } from "@/i18n/config";
import enDealsMessages from "@/messages/en/deals.json";
import enDocumentAcceptanceMessages from "@/messages/en/document-acceptance.json";
import jaDealsMessages from "@/messages/ja/deals.json";
import jaDocumentAcceptanceMessages from "@/messages/ja/document-acceptance.json";

const DOCUMENT_MESSAGES = {
    en: {
        ...enDocumentAcceptanceMessages,
        DealsDocuments: enDealsMessages.DealsDocuments,
    },
    ja: {
        ...jaDocumentAcceptanceMessages,
        DealsDocuments: jaDealsMessages.DealsDocuments,
    },
} satisfies Record<Locale, Record<string, unknown>>;

const getCachedDocumentAcceptancePreview = cache(
    async (token: string, forwardedFor: string | null) =>
        getDocumentAcceptancePreview(token, {
            headers: forwardedFor == null
                ? undefined
                : { "X-Forwarded-For": forwardedFor },
        }),
);

async function getRequestDocumentAcceptancePreview(token: string) {
    const requestHeaders = await headers();
    const forwardedFor = documentAcceptanceForwardedFor(requestHeaders);
    return getCachedDocumentAcceptancePreview(token, forwardedFor);
}

function documentAcceptanceForwardedFor(requestHeaders: Headers): string | null {
    const clientAddress = requestHeaders.get("x-forwarded-for")?.trim();
    if (!clientAddress || clientAddress.includes(",")) {
        return null;
    }
    return clientAddress;
}

export async function generateMetadata({
    params,
}: {
    params: Promise<{ token: string }>;
}): Promise<Metadata> {
    const { token } = await params;
    let locale: Locale = defaultLocale;
    try {
        const preview = await getRequestDocumentAcceptancePreview(token);
        locale = resolveLocale(preview.documentLocale);
    } catch {}
    const t = await getTranslations({ locale, namespace: "DocumentAcceptance" });
    return {
        title: `${t("metaTitle")} | Connex`,
        robots: { index: false, follow: false },
    };
}

export default async function DocumentAcceptancePage({
    params,
}: {
    params: Promise<{ token: string }>;
}) {
    const { token } = await params;
    let preview: DocumentAcceptancePreview | null = null;
    let failure: DocumentAcceptanceFailureKind = "unavailable";

    try {
        preview = await getRequestDocumentAcceptancePreview(token);
    } catch (error: unknown) {
        failure = documentAcceptanceFailureKind(error) ?? "service-unavailable";
    }

    if (!preview) {
        return <DocumentAcceptanceUnavailable copy={await unavailableCopy(failure)} />;
    }

    const locale = resolveLocale(preview.documentLocale);
    return (
        <NextIntlClientProvider locale={locale} messages={DOCUMENT_MESSAGES[locale]}>
            <div lang={locale}>
                <DocumentAcceptance initialPreview={preview} />
            </div>
        </NextIntlClientProvider>
    );
}

async function unavailableCopy(
    kind: DocumentAcceptanceFailureKind,
): Promise<DocumentAcceptanceUnavailableCopy> {
    const t = await getTranslations({
        locale: defaultLocale,
        namespace: "DocumentAcceptance",
    });
    if (kind === "unavailable") {
        return { title: t("unavailableTitle"), body: t("unavailableBody"), footer: t("footer") };
    }
    if (kind === "throttled") {
        return { title: t("throttledTitle"), body: t("throttledBody"), footer: t("footer") };
    }
    return {
        title: t("serviceUnavailableTitle"),
        body: t("serviceUnavailableBody"),
        footer: t("footer"),
    };
}
