import * as React from "react";
import { Cta, Fallback, Heading, Layout, Lead, Panel, PanelRow } from "./Layout.js";

type DocumentSignatureProps = {
    locale?: "en" | "ja";
    recipientName?: string;
    documentTitle?: string;
    message?: string;
    acceptanceUrl?: string;
};

/**
 * Document review-and-acceptance request in English or Japanese. The document
 * title is carried in a panel rather than the headline because titles are
 * caller-supplied and can be long. Props default to {@code {{token}}} placeholders.
 */
export default function DocumentSignature({
    locale = "en",
    recipientName = "{{recipientName}}",
    documentTitle = "{{documentTitle}}",
    message = "{{message}}",
    acceptanceUrl = "{{acceptanceUrl}}",
}: DocumentSignatureProps) {
    const japanese = locale === "ja";
    return (
        <Layout
            lang={locale}
            preview={japanese ? "ご確認とご承諾をお願いする書類が届いています" : "A document is waiting for your review"}
            category={japanese ? "書類" : "Document"}
            footnote={japanese
                ? "このメールに心当たりがない場合は、送信元の担当者にご確認ください。"
                : "If you weren't expecting this document, check with the person who sent it before opening the link."}
        >
            <Heading>{japanese ? "ご確認をお願いします" : "A document needs your review"}</Heading>
            <Lead>
                {japanese
                    ? `${recipientName} 様、ご確認とご承諾をお願いする書類が届いています。`
                    : `Hello ${recipientName}, you have received a commercial document for review and acceptance.`}
            </Lead>
            <Panel>
                <PanelRow label={japanese ? "書類" : "Document"} value={documentTitle} />
            </Panel>
            <Lead>{message}</Lead>
            <Cta href={acceptanceUrl}>{japanese ? "書類を確認する" : "Review document"}</Cta>
            <Fallback
                href={acceptanceUrl}
                label={japanese
                    ? "ボタンが機能しない場合は、次のリンクをコピーし、ブラウザのアドレス欄に貼り付けてください。"
                    : "If the button doesn't work, copy and paste this link into your browser:"}
            />
        </Layout>
    );
}
