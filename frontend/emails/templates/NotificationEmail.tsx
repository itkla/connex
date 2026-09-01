import * as React from "react";
import { Cta, Heading, Layout, Lead, Subline } from "./Layout.js";

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
            category="Notification"
            footnote="You're receiving this because you enabled email for this notification type. Manage it in Settings → Notifications."
        >
            <Heading>{title}</Heading>
            <Subline>{workspaceName}</Subline>
            <Lead>{body}</Lead>
            <Cta href={actionUrl}>View in Connex</Cta>
        </Layout>
    );
}
