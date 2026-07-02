import * as React from "react";
import { Button, Heading, Text } from "@react-email/components";
import { Layout, content } from "./Layout.js";

type NotificationProps = {
    title?: string;
    body?: string;
    actionUrl?: string;
    workspaceName?: string;
};

/**
 * Generic notification email (mentions, join requests, reminders). Props default
 * to {@code {{token}}} placeholders.
 */
export default function NotificationEmail({
    title = "{{title}}",
    body = "{{body}}",
    actionUrl = "{{actionUrl}}",
    workspaceName = "{{workspaceName}}",
}: NotificationProps) {
    return (
        <Layout
            preview={title}
            eyebrow={workspaceName}
            footer="You're receiving this because you enabled email for this notification type. Manage it in Settings → Notifications."
        >
            <Heading style={content.heading}>{title}</Heading>
            <Text style={content.paragraph}>{body}</Text>
            <Button href={actionUrl} style={content.button}>
                View in Connex
            </Button>
        </Layout>
    );
}
