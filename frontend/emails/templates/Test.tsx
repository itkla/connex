import * as React from "react";
import { Heading, Layout, Lead, Panel, PanelRow } from "./Layout.js";

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
            category={japanese ? "診断" : "Diagnostics"}
            footnote={japanese
                ? "このメールは、ワークスペースのメール設定からテスト送信が実行されたため送信されました。"
                : "You received this because a test message was sent from your workspace email settings."}
        >
            <Heading>{japanese ? "メール設定は正常です" : "Your email settings work"}</Heading>
            <Lead>
                {japanese
                    ? "これは Connex からのテストメールです。このメールが届いているため、設定済みの SMTP 経由でメールを配信できることが確認できました。"
                    : "This is a test message from Connex. Because it arrived, your workspace can deliver email through the SMTP transport you configured."}
            </Lead>
            <Panel>
                <PanelRow label={japanese ? "送信先" : "Delivered to"} value={recipient} />
            </Panel>
        </Layout>
    );
}
