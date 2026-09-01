import * as React from "react";
import { Cta, Fallback, Heading, Layout, Lead, Panel, PanelRow } from "./Layout.js";

type EmailChangeProps = {
    locale?: "en" | "ja";
    displayName?: string;
    newEmail?: string;
    verifyUrl?: string;
    expiryMinutes?: string;
};

/**
 * Email-change verification email in English or Japanese. Props default to {@code {{token}}} placeholders.
 */
export default function EmailChange({
    locale = "en",
    displayName = "{{displayName}}",
    newEmail = "{{newEmail}}",
    verifyUrl = "{{verifyUrl}}",
    expiryMinutes = "{{expiryMinutes}}",
}: EmailChangeProps) {
    const japanese = locale === "ja";
    return (
        <Layout
            lang={locale}
            preview={japanese ? "Connex の新しいメールアドレスを確認" : "Confirm your new Connex email"}
            category={japanese ? "セキュリティ" : "Security"}
            footnote={japanese
                ? "この変更をリクエストしていない場合は、このメールを無視してください。現在のメールアドレスは変更されません。"
                : "If you didn't request this change, you can safely ignore this email and your address stays the same."}
        >
            <Heading>{japanese ? "新しいメールアドレスを確認" : "Confirm your new email"}</Heading>
            <Lead>
                {japanese
                    ? `${displayName}さん、下のアドレスを Connex アカウントの新しいメールアドレスとして確認してください。`
                    : `Hi ${displayName}, confirm the address below so Connex can start sending your account email there.`}
            </Lead>
            <Panel>
                <PanelRow label={japanese ? "新しいメールアドレス" : "New address"} value={newEmail} />
                <PanelRow
                    label={japanese ? "リンクの有効期限" : "Link expires in"}
                    value={japanese ? `${expiryMinutes} 分` : `${expiryMinutes} minutes`}
                />
            </Panel>
            <Cta href={verifyUrl}>{japanese ? "メールアドレスを確認" : "Confirm email address"}</Cta>
            <Fallback
                href={verifyUrl}
                label={japanese
                    ? "ボタンが機能しない場合は、次のリンクをコピーし、ブラウザのアドレス欄に貼り付けてください。"
                    : "If the button doesn't work, copy and paste this link into your browser:"}
            />
        </Layout>
    );
}
