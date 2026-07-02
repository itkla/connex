import * as React from "react";
import { Heading, Text } from "@react-email/components";
import { Layout, content } from "./Layout.js";

type TestProps = {
    recipient?: string;
};

/**
 * "Send test email" verification message. Props default to {@code {{token}}} placeholders.
 */
export default function Test({ recipient = "{{recipient}}" }: TestProps) {
    return (
        <Layout preview="Connex email test" eyebrow="Connex">
            <Heading style={content.heading}>Your email settings work</Heading>
            <Text style={{ ...content.paragraph, marginBottom: "32px" }}>
                This is a test message sent to {recipient}. If you're reading it, Connex can deliver email through
                your configured SMTP transport.
            </Text>
        </Layout>
    );
}
