import * as React from "react";
import { Button, Column, Heading, Row, Section, Text } from "@react-email/components";
import { Layout, content } from "./Layout.js";

type ReportDeliveryProps = {
    locale?: "en" | "ja";
    reportName?: string;
    period?: string;
    summary?: string;
    headlineOneLabel?: string;
    headlineOneValue?: string;
    headlineTwoLabel?: string;
    headlineTwoValue?: string;
    actionUrl?: string;
};

const styles = {
    period: {
        margin: "0 0 20px 0",
        padding: "0 40px",
        fontSize: "13px",
        lineHeight: "1.5",
        color: "#71717a",
    },
    figures: {
        margin: "0 40px 24px 40px",
        width: "400px",
        maxWidth: "calc(100% - 80px)",
        border: "1px solid #e4e4e7",
        borderRadius: "10px",
    },
    figure: {
        padding: "16px",
        verticalAlign: "top" as const,
    },
    figureLabel: {
        margin: 0,
        fontSize: "12px",
        lineHeight: "1.4",
        color: "#71717a",
    },
    figureValue: {
        margin: "4px 0 0 0",
        fontSize: "20px",
        lineHeight: "1.3",
        fontWeight: 600,
        color: "#18181b",
    },
    buttonWrap: { paddingBottom: "32px" },
} as const;

/** Scheduled report summary email in English or Japanese. */
export default function ReportDelivery({
    locale = "en",
    reportName = "{{reportName}}",
    period = "{{period}}",
    summary = "{{summary}}",
    headlineOneLabel = "{{headlineOneLabel}}",
    headlineOneValue = "{{headlineOneValue}}",
    headlineTwoLabel = "{{headlineTwoLabel}}",
    headlineTwoValue = "{{headlineTwoValue}}",
    actionUrl = "{{actionUrl}}",
}: ReportDeliveryProps) {
    const japanese = locale === "ja";
    return (
        <Layout
            lang={locale}
            preview={japanese ? `${reportName} の定期レポート` : `Scheduled report: ${reportName}`}
            eyebrow="Connex Reports"
            footer={japanese
                ? "このメールは、ワークスペースのレポート配信先に指定されているため送信されました。"
                : "You received this because you are an active member selected for this report delivery."}
        >
            <Heading style={content.heading}>{reportName}</Heading>
            <Text style={styles.period}>{period}</Text>
            <Text style={content.paragraph}>{summary}</Text>
            <Section style={styles.figures}>
                <Row>
                    <Column style={styles.figure}>
                        <Text style={styles.figureLabel}>{headlineOneLabel}</Text>
                        <Text style={styles.figureValue}>{headlineOneValue}</Text>
                    </Column>
                    <Column style={styles.figure}>
                        <Text style={styles.figureLabel}>{headlineTwoLabel}</Text>
                        <Text style={styles.figureValue}>{headlineTwoValue}</Text>
                    </Column>
                </Row>
            </Section>
            <Section style={styles.buttonWrap}>
                <Button href={actionUrl} style={content.button}>
                    {japanese ? "Connex で表示" : "View report"}
                </Button>
            </Section>
        </Layout>
    );
}
