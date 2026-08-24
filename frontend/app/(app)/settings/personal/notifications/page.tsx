import type { Metadata } from "next";
import { getTranslations } from "next-intl/server";

import PersonalNotifications from "@/app/components/settings/PersonalNotifications";

export async function generateMetadata(): Promise<Metadata> {
    const [tNav, t] = await Promise.all([
        getTranslations("SettingsNav"),
        getTranslations("SettingsPersonalNotifications"),
    ]);
    return {
        title: tNav("groupNotificationPreferences"),
        description: t("metaDescription"),
    };
}

/**
 * The canonical personal Notification preferences destination (#1340 WS4.4).
 *
 * Nothing is read here. Both panels resolve and save their own preferences, and each keeps its own
 * failed-read state, so one of them failing leaves the other usable rather than taking the page down
 * with it.
 */
export default function PersonalNotificationsPage() {
    return <PersonalNotifications />;
}
