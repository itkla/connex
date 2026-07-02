import * as React from "react";
import { Button, Heading, Link, Text } from "@react-email/components";
import { Layout, content } from "./Layout.js";

type PasswordResetProps = {
    displayName?: string;
    resetUrl?: string;
    expiryMinutes?: string;
};

/**
 * Password-reset email. Props default to {@code {{token}}} placeholders.
 */
export default function PasswordReset({
    displayName = "{{displayName}}",
    resetUrl = "{{resetUrl}}",
    expiryMinutes = "{{expiryMinutes}}",
}: PasswordResetProps) {
    return (
        <Layout
            preview="Reset your Connex password"
            eyebrow="Connex"
            footer="If you didn't request a password reset, you can safely ignore this email."
        >
            <Heading style={content.heading}>Reset your password</Heading>
            <Text style={content.paragraph}>
                Hi {displayName}, we received a request to reset your Connex password. Click below to choose a new
                one. This link expires in {expiryMinutes} minutes.
            </Text>
            <Button href={resetUrl} style={content.button}>
                Reset password
            </Button>
            <Text style={content.fallback}>
                If the button doesn't work, copy and paste this link into your browser:
                <br />
                <Link href={resetUrl} style={content.fallbackLink}>
                    {resetUrl}
                </Link>
            </Text>
        </Layout>
    );
}
