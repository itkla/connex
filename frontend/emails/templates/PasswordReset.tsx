import * as React from "react";
import { Button, Heading, Link, Text } from "@react-email/components";
import { Layout, content } from "./Layout.js";

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
            eyebrow="Connex"
            footer={japanese
                ? "パスワードのリセットをリクエストしていない場合は、このメールを無視してください。"
                : "If you didn't request a password reset, you can safely ignore this email."}
        >
            <Heading style={content.heading}>{japanese ? "パスワードをリセット" : "Reset your password"}</Heading>
            <Text style={content.paragraph}>
                {japanese
                    ? <>{displayName}さん、Connex パスワードのリセットがリクエストされました。下のボタンから新しいパスワードを設定してください。このリンクは {expiryMinutes} 分後に有効期限が切れます。</>
                    : <>Hi {displayName}, we received a request to reset your Connex password. Click below to choose a new one. This link expires in {expiryMinutes} minutes.</>}
            </Text>
            <Button href={resetUrl} style={content.button}>
                {japanese ? "パスワードをリセット" : "Reset password"}
            </Button>
            <Text style={content.fallback}>
                {japanese
                    ? "ボタンが機能しない場合は、次のリンクをコピーし、ブラウザのアドレス欄に貼り付けてください。"
                    : "If the button doesn't work, copy and paste this link into your browser:"}
                <br />
                <Link href={resetUrl} style={content.fallbackLink}>
                    {resetUrl}
                </Link>
            </Text>
        </Layout>
    );
}
