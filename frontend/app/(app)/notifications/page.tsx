import NotificationsInbox from "@/app/components/notifications/NotificationsInbox";
import NotificationEmailLink from "@/app/components/notifications/NotificationEmailLink";
import { NOTIFICATION_OPEN_URL_KEY, parseDeepLinkId } from "@/app/hooks/listStateUrl";

export default async function NotificationsPage({
    searchParams,
}: {
    searchParams: Promise<{ open?: string | string[] }>;
}) {
    const open = (await searchParams)[NOTIFICATION_OPEN_URL_KEY];
    if (open !== undefined) {
        const notificationId = parseDeepLinkId(typeof open === "string" ? open : null);
        return <NotificationEmailLink key={notificationId ?? "invalid"} notificationId={notificationId} />;
    }
    return <NotificationsInbox />;
}
