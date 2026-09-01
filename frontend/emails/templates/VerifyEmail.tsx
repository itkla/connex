import * as React from "react";
import { Cta, Fallback, Heading, Layout, Lead, Panel, PanelRow } from "./Layout.js";

type VerifyEmailProps = {
    displayName?: string;
    verifyUrl?: string;
    expiryMinutes?: string;
};

/**
 * Registration verification email. Props default to {@code {{token}}} placeholders.
 */
export default function VerifyEmail({
    displayName = "{{displayName}}",
    verifyUrl = "{{verifyUrl}}",
    expiryMinutes = "{{expiryMinutes}}",
}: VerifyEmailProps) {
    return (
        <Layout
            preview="Verify your Connex email"
            category="Security"
            footnote="If you didn't create a Connex account, you can safely ignore this email."
        >
            <Heading>Verify your email</Heading>
            <Lead>
                {`Hi ${displayName}, confirm this is your email address to finish setting up your Connex account.`}
            </Lead>
            <Panel>
                <PanelRow label="Link expires in" value={`${expiryMinutes} minutes`} />
            </Panel>
            <Cta href={verifyUrl}>Verify email address</Cta>
            <Fallback href={verifyUrl} label="If the button doesn't work, copy and paste this link into your browser:" />
        </Layout>
    );
}
