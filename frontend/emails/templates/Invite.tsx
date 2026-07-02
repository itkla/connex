import * as React from "react";
import { Button, Heading, Link, Text } from "@react-email/components";
import { Layout, content } from "./Layout.js";

type InviteProps = {
    workspaceName?: string;
    inviterName?: string;
    role?: string;
    acceptUrl?: string;
};

/**
 * Workspace invitation email. Props default to {@code {{token}}} placeholders so
 * the rendered HTML is a template the backend fills in.
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
            eyebrow="Connex"
            footer="If you weren't expecting this invitation, you can ignore this email."
        >
            <Heading style={content.heading}>You've been invited to {workspaceName}</Heading>
            <Text style={content.paragraph}>
                {inviterName} invited you to join <strong style={content.strong}>{workspaceName}</strong> on Connex
                as a {role}. Accept the invitation to get started.
            </Text>
            <Button href={acceptUrl} style={content.button}>
                Accept invitation
            </Button>
            <Text style={content.fallback}>
                If the button doesn't work, copy and paste this link into your browser:
                <br />
                <Link href={acceptUrl} style={content.fallbackLink}>
                    {acceptUrl}
                </Link>
            </Text>
        </Layout>
    );
}
