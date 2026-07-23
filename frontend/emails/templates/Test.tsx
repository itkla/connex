import * as React from "react";
import { Heading, Text } from "@react-email/components";
import { Layout, content } from "./Layout.js";

type TestProps = {
    locale?: "en" | "ja";
    recipient?: string;
};

/**
 * "Send test email" verification message in English or Japanese.
 */
export default function Test({ locale = "en", recipient = "{{recipient}}" }: TestProps) {
    const japanese = locale === "ja";
    return (
        <Layout
            lang={locale}
            preview={japanese ? "Connex テストメール" : "Connex email test"}
            eyebrow="Connex"
        >
            <Heading style={content.heading}>
                {japanese ? "メール設定は正常です" : "Your email settings work"}
            </Heading>
            <Text style={{ ...content.paragraph, marginBottom: "32px" }}>
                {japanese
                    ? <>これは {recipient} 宛てに送信されたテストメールです。このメールを受信できていれば、設定済みのSMTPサーバーを通じて Connex からメールを送信できます。</>
                    : <>This is a test message sent to {recipient}. If you're reading it, Connex can deliver email through your configured SMTP transport.</>}
            </Text>
        </Layout>
    );
}
