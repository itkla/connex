import * as React from "react";
import { Body, Container, Head, Html, Preview, Section, Text } from "@react-email/components";

const styles = {
    body: {
        margin: 0,
        padding: 0,
        backgroundColor: "#f4f4f5",
        fontFamily:
            "-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif",
    },
    outer: { padding: "32px 0" },
    card: {
        width: "480px",
        maxWidth: "100%",
        backgroundColor: "#ffffff",
        borderRadius: "12px",
        border: "1px solid #e4e4e7",
        overflow: "hidden",
    },
    eyebrow: {
        margin: 0,
        padding: "32px 40px 8px 40px",
        fontSize: "12px",
        fontWeight: 700,
        color: "#71717a",
        letterSpacing: "0.12em",
        textTransform: "uppercase" as const,
    },
    footer: {
        margin: "16px 0 0 0",
        fontSize: "12px",
        color: "#a1a1aa",
        textAlign: "center" as const,
    },
} as const;

type LayoutProps = {
    preview: string;
    eyebrow: string;
    lang?: string;
    footer?: string;
    children: React.ReactNode;
};

/**
 * Shared shell for every Connex transactional email: the card, the uppercase
 * wordmark eyebrow, and an optional muted footer line. Renders to inline-styled,
 * email-client-safe HTML via React Email primitives.
 */
export function Layout({ preview, eyebrow, lang = "en", footer, children }: LayoutProps) {
    return (
        <Html lang={lang}>
            <Head />
            <Preview>{preview}</Preview>
            <Body style={styles.body}>
                <Section style={styles.outer}>
                    <Container style={styles.card}>
                        <Text style={styles.eyebrow}>{eyebrow}</Text>
                        {children}
                    </Container>
                    {footer ? <Text style={styles.footer}>{footer}</Text> : null}
                </Section>
            </Body>
        </Html>
    );
}

export const content = {
    heading: {
        margin: "8px 0 12px 0",
        padding: "0 40px",
        fontSize: "28px",
        lineHeight: "1.25",
        fontWeight: 600,
        color: "#18181b",
        letterSpacing: "-0.02em",
    } as const,
    paragraph: {
        margin: "0 0 24px 0",
        padding: "0 40px",
        fontSize: "15px",
        lineHeight: "1.6",
        color: "#52525b",
    } as const,
    button: {
        margin: "0 40px",
        backgroundColor: "#18181b",
        color: "#ffffff",
        fontSize: "15px",
        fontWeight: 600,
        padding: "12px 24px",
        borderRadius: "8px",
    } as const,
    fallback: {
        margin: 0,
        padding: "24px 40px 32px 40px",
        fontSize: "13px",
        lineHeight: "1.6",
        color: "#a1a1aa",
    } as const,
    fallbackLink: { color: "#71717a", wordBreak: "break-all" as const },
    strong: { color: "#18181b" },
};
