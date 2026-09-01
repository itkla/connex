import * as React from "react";
import { Cta, Fallback, Heading, Layout, Lead, Panel, PanelRow } from "./Layout.js";

type PasskeyBootstrapConfirmationProps = {
    locale?: "en" | "ja";
    displayName?: string;
    confirmUrl?: string;
    expiryMinutes?: string;
};

/**
 * First-passkey enrollment confirmation email in English or Japanese. The
 * same-browser requirement is a panel row rather than inline emphasis so it
 * survives skimming. Props default to {@code {{token}}} placeholders.
 */
export default function PasskeyBootstrapConfirmation({
    locale = "en",
    displayName = "{{displayName}}",
    confirmUrl = "{{confirmUrl}}",
    expiryMinutes = "{{expiryMinutes}}",
}: PasskeyBootstrapConfirmationProps) {
    const japanese = locale === "ja";
    return (
        <Layout
            lang={locale}
            preview={japanese ? "Connex パスキー登録の確認" : "Confirm your Connex passkey enrollment"}
            category={japanese ? "セキュリティ" : "Security"}
            footnote={japanese
                ? "パスキーの登録をリクエストしていない場合は、このリンクを開かないでください。パスワードが第三者に知られている可能性があります。直ちにパスワードを変更し、管理者に連絡してください。"
                : "If you didn't start a passkey enrollment, do not open this link. Someone may know your password. Change it immediately and tell an administrator."}
        >
            <Heading>{japanese ? "パスキー登録を確認" : "Confirm passkey enrollment"}</Heading>
            <Lead>
                {japanese
                    ? `${displayName}さん、管理権限を持つアカウントで最初のパスキーを登録するには確認が必要です。`
                    : `Hi ${displayName}, enrolling the first passkey on an account with administrative access needs confirmation.`}
            </Lead>
            <Panel>
                <PanelRow
                    label={japanese ? "開くブラウザ" : "Open this link in"}
                    value={japanese
                        ? "登録を開始したブラウザと同じブラウザ"
                        : "The same browser you started enrolling in"}
                />
                <PanelRow
                    label={japanese ? "リンクの有効期限" : "Link expires in"}
                    value={japanese ? `${expiryMinutes} 分` : `${expiryMinutes} minutes`}
                />
            </Panel>
            <Cta href={confirmUrl}>{japanese ? "パスキー登録を確認" : "Confirm enrollment"}</Cta>
            <Fallback
                href={confirmUrl}
                label={japanese
                    ? "ボタンが機能しない場合は、次のリンクをコピーし、ブラウザのアドレス欄に貼り付けてください。"
                    : "If the button doesn't work, copy and paste this link into your browser:"}
            />
        </Layout>
    );
}
