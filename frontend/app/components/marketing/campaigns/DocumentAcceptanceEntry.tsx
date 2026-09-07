"use client";

import { useEffect, useState } from "react";
import { NextIntlClientProvider } from "next-intl";
import { Loader2Icon } from "lucide-react";

import DocumentAcceptance from "@/app/components/marketing/campaigns/DocumentAcceptance";
import DocumentAcceptanceUnavailable, {
    type DocumentAcceptanceUnavailableCopy,
} from "@/app/components/marketing/campaigns/DocumentAcceptanceUnavailable";
import {
    documentAcceptanceFailureKind,
    exchangeDocumentAcceptanceToken,
    getDocumentAcceptancePreview,
} from "@/app/lib/api";
import { takeOneTimeLinkToken } from "@/app/lib/oneTimeLink";
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

/**
 * How often the open acceptance page re-reads its preview.
 *
 * <p>The grant lives 60 minutes but its owner is the browser binding cookie plus the servlet
 * session lineage, and the session expires after 30 idle minutes. A signer who reads a long
 * contract without clicking would otherwise lose the lineage, so the still-valid grant would be
 * refused. This read records nothing and only refreshes the session, so it can never forge view
 * evidence.
 */
const SESSION_KEEP_ALIVE_MS = 10 * 60 * 1000;

type EntryState =
    | { status: "loading" }
    | { status: "failed"; kind: DocumentAcceptanceFailureKind }
    | { status: "ready"; preview: DocumentAcceptancePreview };

function unavailableCopy(kind: DocumentAcceptanceFailureKind): DocumentAcceptanceUnavailableCopy {
    const copy = DOCUMENT_MESSAGES[defaultLocale].DocumentAcceptance;
    if (kind === "unavailable") {
        return { title: copy.unavailableTitle, body: copy.unavailableBody, footer: copy.footer };
    }
    if (kind === "throttled") {
        return { title: copy.throttledTitle, body: copy.throttledBody, footer: copy.footer };
    }
    return {
        title: copy.serviceUnavailableTitle,
        body: copy.serviceUnavailableBody,
        footer: copy.footer,
    };
}

/**
 * Exchanges the emailed document-acceptance fragment bearer for a purpose-bound browser grant and
 * then renders the recipient surface entirely from that grant, so no request ever names the bearer.
 * While a preview is on screen the page re-reads it periodically, which keeps the servlet session
 * that owns the grant lineage alive for as long as the grant itself lasts.
 */
export default function DocumentAcceptanceEntry() {
    const [state, setState] = useState<EntryState>({ status: "loading" });

    useEffect(() => {
        let active = true;

        const establish = async () => {
            const token = takeOneTimeLinkToken();
            if (token) {
                await exchangeDocumentAcceptanceToken(token);
                window.location.replace("/document-acceptance");
                return;
            }
            const preview = await getDocumentAcceptancePreview();
            if (active) setState({ status: "ready", preview });
        };

        establish().catch((error: unknown) => {
            if (!active) return;
            setState({
                status: "failed",
                kind: documentAcceptanceFailureKind(error) ?? "service-unavailable",
            });
        });

        return () => {
            active = false;
        };
    }, []);

    useEffect(() => {
        if (state.status !== "ready") return;
        let active = true;
        const timer = window.setInterval(() => {
            getDocumentAcceptancePreview().catch((error: unknown) => {
                if (!active) return;
                setState({
                    status: "failed",
                    kind: documentAcceptanceFailureKind(error) ?? "service-unavailable",
                });
            });
        }, SESSION_KEEP_ALIVE_MS);

        return () => {
            active = false;
            window.clearInterval(timer);
        };
    }, [state.status]);

    useEffect(() => {
        const reopen = () => window.location.reload();
        window.addEventListener("hashchange", reopen);
        return () => window.removeEventListener("hashchange", reopen);
    }, []);

    if (state.status === "loading") {
        return (
            <main className="grid min-h-dvh place-items-center bg-muted/30 px-5 py-12">
                <div
                    role="status"
                    className="flex items-center gap-3 text-sm text-muted-foreground"
                >
                    <Loader2Icon aria-hidden="true" className="size-4 animate-spin" />
                    {DOCUMENT_MESSAGES[defaultLocale].DocumentAcceptance.loading}
                </div>
            </main>
        );
    }

    if (state.status === "failed") {
        return <DocumentAcceptanceUnavailable copy={unavailableCopy(state.kind)} />;
    }

    const locale = resolveLocale(state.preview.documentLocale);
    return (
        <NextIntlClientProvider locale={locale} messages={DOCUMENT_MESSAGES[locale]}>
            <div lang={locale}>
                <DocumentTitle title={DOCUMENT_MESSAGES[locale].DocumentAcceptance.metaTitle} />
                <DocumentAcceptance initialPreview={state.preview} />
            </div>
        </NextIntlClientProvider>
    );
}

function DocumentTitle({ title }: { title: string }) {
    useEffect(() => {
        document.title = `${title} | Connex`;
    }, [title]);
    return null;
}
