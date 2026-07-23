import * as React from "react";
import { Button, Heading, Link, Text } from "@react-email/components";
import { Layout, content } from "./Layout.js";

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
            eyebrow="Connex"
            footer={japanese
                ? "この変更をリクエストしていない場合は、このメールを無視してください。現在のメールアドレスは変更されません。"
                : "If you didn't request this change, you can safely ignore this email and your address stays the same."}
        >
            <Heading style={content.heading}>
                {japanese ? "新しいメールアドレスを確認" : "Confirm your new email"}
            </Heading>
            <Text style={content.paragraph}>
                {japanese
                    ? <>{displayName}さん、<strong style={content.strong}>{newEmail}</strong> が Connex アカウントの新しいメールアドレスであることを確認してください。このリンクは {expiryMinutes} 分後に有効期限が切れます。</>
                    : <>Hi {displayName}, confirm that <strong style={content.strong}>{newEmail}</strong> is your new Connex email address. This link expires in {expiryMinutes} minutes.</>}
            </Text>
            <Button href={verifyUrl} style={content.button}>
                {japanese ? "メールアドレスを確認" : "Confirm email address"}
            </Button>
            <Text style={content.fallback}>
                {japanese
                    ? "ボタンが機能しない場合は、次のリンクをコピーし、ブラウザのアドレス欄に貼り付けてください。"
                    : "If the button doesn't work, copy and paste this link into your browser:"}
                <br />
                <Link href={verifyUrl} style={content.fallbackLink}>
                    {verifyUrl}
                </Link>
            </Text>
        </Layout>
    );
}
