import * as React from "react";
import { Column, Row, Section, Text } from "@react-email/components";
import { Cta, Heading, Layout, Lead, Subline } from "./Layout.js";
import { palette } from "./theme.js";

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
    inset: { padding: "28px 40px 0 40px" },
    band: {
        borderTop: `1px solid ${palette.hairline}`,
        borderBottom: `1px solid ${palette.hairline}`,
        padding: "22px 0",
    },
    figure: { width: "50%", paddingRight: "16px", verticalAlign: "top" as const },
    rule: {
        margin: 0,
        width: "26px",
        borderTop: `2px solid ${palette.brand}`,
        fontSize: "1px",
        lineHeight: "1px",
    },
    figureLabel: {
        margin: "14px 0 0 0",
        fontSize: "11px",
        lineHeight: "1.4",
        fontWeight: 600,
        letterSpacing: "0.08em",
        textTransform: "uppercase" as const,
        color: palette.muted,
    },
    figureValue: {
        margin: "6px 0 0 0",
        fontSize: "30px",
        lineHeight: "1.2",
        fontWeight: 700,
        letterSpacing: "-0.02em",
        color: palette.ink,
    },
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
            category={japanese ? "レポート" : "Report"}
            footnote={japanese
                ? "このメールは、ワークスペースのレポート配信先に指定されているため送信されました。"
                : "You received this because you are an active member selected for this report delivery."}
        >
            <Heading>{reportName}</Heading>
            <Subline>{period}</Subline>
            <Lead>{summary}</Lead>
            <Section className="cx-pad" style={styles.inset}>
                <Section className="cx-rule" style={styles.band}>
                    <Row>
                        <Column className="cx-figure" style={styles.figure}>
                            <Text style={styles.rule}>{"​"}</Text>
                            <Text className="cx-figure-label" style={styles.figureLabel}>
                                {headlineOneLabel}
                            </Text>
                            <Text className="cx-figure-value" style={styles.figureValue}>
                                {headlineOneValue}
                            </Text>
                        </Column>
                        <Column className="cx-figure" style={styles.figure}>
                            <Text style={styles.rule}>{"​"}</Text>
                            <Text className="cx-figure-label" style={styles.figureLabel}>
                                {headlineTwoLabel}
                            </Text>
                            <Text className="cx-figure-value" style={styles.figureValue}>
                                {headlineTwoValue}
                            </Text>
                        </Column>
                    </Row>
                </Section>
            </Section>
            <Cta href={actionUrl}>{japanese ? "Connex で表示" : "View report"}</Cta>
        </Layout>
    );
}
