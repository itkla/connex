import * as React from "react";
import { Button, Heading, Link, Text } from "@react-email/components";
import { Layout, content } from "./Layout.js";

type EmailChangeProps = {
    displayName?: string;
    newEmail?: string;
    verifyUrl?: string;
    expiryMinutes?: string;
};

/**
 * Email-change verification email. Props default to {@code {{token}}} placeholders.
 */
export default function EmailChange({
    displayName = "{{displayName}}",
    newEmail = "{{newEmail}}",
    verifyUrl = "{{verifyUrl}}",
    expiryMinutes = "{{expiryMinutes}}",
}: EmailChangeProps) {
    return (
        <Layout
            preview="Confirm your new Connex email"
            eyebrow="Connex"
            footer="If you didn't request this change, you can safely ignore this email and your address stays the same."
        >
            <Heading style={content.heading}>Confirm your new email</Heading>
            <Text style={content.paragraph}>
                Hi {displayName}, confirm that <strong style={content.strong}>{newEmail}</strong> is your new Connex
                email address. This link expires in {expiryMinutes} minutes.
            </Text>
            <Button href={verifyUrl} style={content.button}>
                Confirm email address
            </Button>
            <Text style={content.fallback}>
                If the button doesn't work, copy and paste this link into your browser:
                <br />
                <Link href={verifyUrl} style={content.fallbackLink}>
                    {verifyUrl}
                </Link>
            </Text>
        </Layout>
    );
}
