import * as React from "react";
import {
    Body,
    Column,
    Container,
    Head,
    Heading as EmailHeading,
    Hr,
    Html,
    Link,
    Preview,
    Row,
    Section,
    Text,
} from "@react-email/components";
import { cardWidth, enhancementCss, fontStack, palette } from "./theme.js";

const shell = {
    page: {
        margin: 0,
        padding: 0,
        backgroundColor: palette.page,
    },
    outer: { padding: "40px 0", backgroundColor: palette.page },
    card: {
        width: `${cardWidth}px`,
        maxWidth: "100%",
        backgroundColor: palette.surface,
        borderRadius: "14px",
        border: `1px solid ${palette.hairline}`,
    },
    masthead: {
        backgroundColor: palette.masthead,
        padding: "20px 40px",
        borderRadius: "13px 13px 0 0",
    },
    mark: { width: "10px", verticalAlign: "middle" as const },
    markDot: {
        margin: 0,
        width: "10px",
        height: "10px",
        backgroundColor: palette.brand,
        borderRadius: "3px",
        fontSize: "1px",
        lineHeight: "10px",
    },
    wordmark: {
        margin: 0,
        paddingLeft: "10px",
        fontSize: "15px",
        lineHeight: "20px",
        fontWeight: 700,
        letterSpacing: "-0.01em",
        color: palette.mastheadInk,
    },
    category: {
        margin: 0,
        fontSize: "11px",
        lineHeight: "20px",
        fontWeight: 600,
        letterSpacing: "0.1em",
        textTransform: "uppercase" as const,
        color: palette.mastheadMuted,
        textAlign: "right" as const,
    },
    tail: { margin: 0, fontSize: "1px", lineHeight: "36px", color: palette.surface },
    footnote: {
        margin: "22px 0 0 0",
        padding: "0 40px",
        fontSize: "13px",
        lineHeight: "1.55",
        color: palette.mutedOnPage,
        textAlign: "center" as const,
    },
    signature: {
        margin: "10px 0 0 0",
        fontSize: "12px",
        lineHeight: "1.4",
        fontWeight: 600,
        letterSpacing: "0.02em",
        color: palette.mutedOnPage,
        textAlign: "center" as const,
    },
} as const;

type LayoutProps = {
    preview: string;
    category: string;
    lang?: "en" | "ja";
    footnote?: string;
    children: React.ReactNode;
};

/**
 * Shared shell for every Connex transactional email: the ink masthead carrying
 * the brand mark and a category label, the content card, and a muted closing
 * note. Renders to inline-styled, email-client-safe HTML via React Email.
 */
export function Layout({ preview, category, lang = "en", footnote, children }: LayoutProps) {
    return (
        <Html lang={lang}>
            <Head>
                <meta name="color-scheme" content="light dark" />
                <meta name="supported-color-schemes" content="light dark" />
                <style dangerouslySetInnerHTML={{ __html: enhancementCss }} />
            </Head>
            <Preview>{preview}</Preview>
            <Body
                className="cx-page"
                style={{
                    ...shell.page,
                    fontFamily: lang === "ja" ? fontStack.japanese : fontStack.latin,
                }}
            >
                <Section className="cx-page" style={shell.outer}>
                    <Container className="cx-card" style={shell.card}>
                        <Section className="cx-masthead cx-pad" style={shell.masthead}>
                            <Row>
                                <Column style={shell.mark}>
                                    <Text style={shell.markDot}>{"​"}</Text>
                                </Column>
                                <Column>
                                    <Text style={shell.wordmark}>Connex</Text>
                                </Column>
                                <Column>
                                    <Text style={shell.category}>{category}</Text>
                                </Column>
                            </Row>
                        </Section>
                        {children}
                        <Text style={shell.tail}>{"​"}</Text>
                    </Container>
                    {footnote ? (
                        <Text className="cx-footnote cx-pad" style={shell.footnote}>
                            {footnote}
                        </Text>
                    ) : null}
                    <Text className="cx-footnote" style={shell.signature}>
                        Connex
                    </Text>
                </Section>
            </Body>
        </Html>
    );
}

const block = {
    heading: {
        margin: "36px 0 0 0",
        padding: "0 40px",
        fontSize: "30px",
        lineHeight: "1.22",
        fontWeight: 700,
        letterSpacing: "-0.022em",
        color: palette.ink,
    } as const,
    subline: {
        margin: "10px 0 0 0",
        padding: "0 40px",
        fontSize: "14px",
        lineHeight: "1.5",
        color: palette.muted,
    } as const,
    lead: {
        margin: "20px 0 0 0",
        padding: "0 40px",
        fontSize: "16px",
        lineHeight: "1.65",
        color: palette.body,
    } as const,
    inset: { padding: "28px 40px 0 40px" } as const,
    panel: {
        backgroundColor: palette.surfaceSunken,
        border: `1px solid ${palette.hairline}`,
        borderRadius: "10px",
        padding: "2px 18px 16px 18px",
    } as const,
    panelLabel: {
        margin: "14px 0 0 0",
        fontSize: "12px",
        lineHeight: "1.4",
        fontWeight: 600,
        letterSpacing: "0.04em",
        color: palette.muted,
    } as const,
    panelValue: {
        margin: "3px 0 0 0",
        fontSize: "15px",
        lineHeight: "1.45",
        fontWeight: 600,
        color: palette.ink,
        wordBreak: "break-word" as const,
    } as const,
    ctaRow: { margin: 0 } as const,
    cta: {
        display: "inline-block",
        backgroundColor: palette.brand,
        color: palette.brandInk,
        fontSize: "15px",
        lineHeight: "1.2",
        fontWeight: 700,
        letterSpacing: "-0.005em",
        padding: "14px 28px",
        borderRadius: "10px",
    } as const,
    rule: {
        margin: "32px 40px 0 40px",
        width: "auto",
        borderTop: `1px solid ${palette.hairline}`,
    } as const,
    fallback: {
        margin: "18px 0 0 0",
        padding: "0 40px",
        fontSize: "13px",
        lineHeight: "1.6",
        color: palette.muted,
    } as const,
    fallbackLink: { color: palette.muted, wordBreak: "break-all" as const } as const,
};

/** Primary message headline. */
export function Heading({ children }: { children: string }) {
    return (
        <EmailHeading as="h1" className="cx-heading" style={block.heading}>
            {children}
        </EmailHeading>
    );
}

/** Muted qualifier directly beneath the headline (a reporting period, a workspace). */
export function Subline({ children }: { children: string }) {
    return (
        <Text className="cx-muted" style={block.subline}>
            {children}
        </Text>
    );
}

/** Body prose. Pass one interpolated string so clients receive unbroken text. */
export function Lead({ children }: { children: string }) {
    return (
        <Text className="cx-lead" style={block.lead}>
            {children}
        </Text>
    );
}

/** Sunken panel holding the factual detail of the message. */
export function Panel({ children }: { children: React.ReactNode }) {
    return (
        <Section className="cx-pad" style={block.inset}>
            <Section className="cx-panel" style={block.panel}>
                {children}
            </Section>
        </Section>
    );
}

/** One label/value pair inside a {@link Panel}. */
export function PanelRow({ label, value }: { label: string; value: string }) {
    return (
        <>
            <Text className="cx-panel-label" style={block.panelLabel}>
                {label}
            </Text>
            <Text className="cx-panel-value" style={block.panelValue}>
                {value}
            </Text>
        </>
    );
}

/** Primary call to action. The label is the anchor's only child. */
export function Cta({ href, children }: { href: string; children: string }) {
    return (
        <Section className="cx-pad" style={block.inset}>
            <Text style={block.ctaRow}>
                <Link className="cx-cta" href={href} style={block.cta}>
                    {children}
                </Link>
            </Text>
        </Section>
    );
}

/** Hairline separating the action from the supporting detail. */
export function Divider() {
    return <Hr className="cx-rule" style={block.rule} />;
}

/** Copy-and-paste escape hatch for clients that strip the button. */
export function Fallback({ href, label }: { href: string; label: string }) {
    return (
        <>
            <Divider />
            <Text className="cx-fallback cx-pad" style={block.fallback}>
                {label}
            </Text>
            <Text className="cx-fallback cx-pad" style={{ ...block.fallback, margin: "6px 0 0 0" }}>
                <Link className="cx-fallback-link" href={href} style={block.fallbackLink}>
                    {href}
                </Link>
            </Text>
        </>
    );
}

export { block };
