import * as React from "react";
import { Cta, Fallback, Heading, Layout, Lead, Panel, PanelRow } from "./Layout.js";

type InviteProps = {
    workspaceName?: string;
    inviterName?: string;
    role?: string;
    acceptUrl?: string;
};

/**
 * Workspace invitation email. The workspace name is carried in a panel rather
 * than the headline because it is caller-supplied and can be long; the subject
 * line and preview text still lead with it. Props default to
 * {@code {{token}}} placeholders so the rendered HTML is a template the backend
 * fills in.
 */
export default function Invite({
    workspaceName = "{{workspaceName}}",
    inviterName = "{{inviterName}}",
    role = "{{role}}",
    acceptUrl = "{{acceptUrl}}",
}: InviteProps) {
    return (
        <Layout
            preview={`You've been invited to ${workspaceName}`}
            category="Invitation"
            footnote="If you weren't expecting this invitation, you can ignore this email."
        >
            <Heading>You've been invited</Heading>
            <Lead>
                {`${inviterName} invited you to join them on Connex. Accept below to set up your account and start working together.`}
            </Lead>
            <Panel>
                <PanelRow label="Workspace" value={workspaceName} />
                <PanelRow label="Your role" value={role} />
            </Panel>
            <Cta href={acceptUrl}>Accept invitation</Cta>
            <Fallback href={acceptUrl} label="If the button doesn't work, copy and paste this link into your browser:" />
        </Layout>
    );
}
