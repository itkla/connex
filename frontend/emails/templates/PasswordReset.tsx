import * as React from "react";
import { Cta, Fallback, Heading, Layout, Lead, Panel, PanelRow } from "./Layout.js";

type PasswordResetProps = {
    locale?: "en" | "ja";
    displayName?: string;
    resetUrl?: string;
    expiryMinutes?: string;
};

/**
 * Password-reset email in English or Japanese. Props default to {@code {{token}}} placeholders.
 */
export default function PasswordReset({
    locale = "en",
    displayName = "{{displayName}}",
    resetUrl = "{{resetUrl}}",
    expiryMinutes = "{{expiryMinutes}}",
}: PasswordResetProps) {
    const japanese = locale === "ja";
    return (
        <Layout
            lang={locale}
            preview={japanese ? "Connex パスワードのリセット" : "Reset your Connex password"}
            category={japanese ? "セキュリティ" : "Security"}
            footnote={japanese
                ? "パスワードのリセットをリクエストしていない場合は、このメールを無視してください。パスワードは変更されません。"
                : "If you didn't request a password reset, you can safely ignore this email. Your password stays the same."}
        >
            <Heading>{japanese ? "パスワードをリセット" : "Reset your password"}</Heading>
            <Lead>
                {japanese
                    ? `${displayName}さん、Connex アカウントのパスワードをリセットするリクエストを受け付けました。下のボタンから新しいパスワードを設定してください。`
                    : `Hi ${displayName}, we received a request to reset the password on your Connex account. Choose a new one below.`}
            </Lead>
            <Panel>
                <PanelRow
                    label={japanese ? "リンクの有効期限" : "Link expires in"}
                    value={japanese ? `${expiryMinutes} 分` : `${expiryMinutes} minutes`}
                />
            </Panel>
            <Cta href={resetUrl}>{japanese ? "パスワードをリセット" : "Reset password"}</Cta>
            <Fallback
                href={resetUrl}
                label={japanese
                    ? "ボタンが機能しない場合は、次のリンクをコピーし、ブラウザのアドレス欄に貼り付けてください。"
                    : "If the button doesn't work, copy and paste this link into your browser:"}
            />
        </Layout>
    );
}
