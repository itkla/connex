import * as React from "react";
import { Button, Heading, Link, Text } from "@react-email/components";
import { Layout, content } from "./Layout.js";

type PasskeyBootstrapConfirmationProps = {
    locale?: "en" | "ja";
    displayName?: string;
    confirmUrl?: string;
    expiryMinutes?: string;
};

/**
 * First-passkey enrollment confirmation email in English or Japanese. Props default to
 * {@code {{token}}} placeholders.
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
            eyebrow="Connex"
            footer={japanese
                ? "パスキーの登録をリクエストしていない場合は、このリンクを開かないでください。パスワードが第三者に知られている可能性があります。直ちにパスワードを変更し、管理者に連絡してください。"
                : "If you didn't start a passkey enrollment, do not open this link. Someone may know your password — change it immediately and tell an administrator."}
        >
            <Heading style={content.heading}>
                {japanese ? "パスキー登録を確認" : "Confirm passkey enrollment"}
            </Heading>
            <Text style={content.paragraph}>
                {japanese
                    ? <>{displayName}さん、管理権限を持つアカウントで最初のパスキーを登録するには確認が必要です。<strong style={content.strong}>登録を開始したブラウザと同じブラウザ</strong>でこのリンクを開いてください。このリンクは {expiryMinutes} 分後に有効期限が切れます。</>
                    : <>Hi {displayName}, enrolling the first passkey on an account with administrative access needs confirmation. Open this link in <strong style={content.strong}>the same browser you started enrolling in</strong>. This link expires in {expiryMinutes} minutes.</>}
            </Text>
            <Button href={confirmUrl} style={content.button}>
                {japanese ? "パスキー登録を確認" : "Confirm enrollment"}
            </Button>
            <Text style={content.fallback}>
                {japanese
                    ? "ボタンが機能しない場合は、次のリンクをコピーし、ブラウザのアドレス欄に貼り付けてください。"
                    : "If the button doesn't work, copy and paste this link into your browser:"}
                <br />
                <Link href={confirmUrl} style={content.fallbackLink}>
                    {confirmUrl}
                </Link>
            </Text>
        </Layout>
    );
}
