"use client";

import { useTranslations } from "next-intl";

import NotificationsPanel from "@/app/components/account/NotificationsPanel";
import QuietHoursPanel from "@/app/components/account/QuietHoursPanel";
import Rise from "@/app/components/motion/Rise";
import { PageHeader } from "@/app/components/PageHeader";

/**
 * Notification preferences: what reaches the reader, and when it should not (#1340 WS4.4).
 *
 * Both panels `/account/notifications` composed, under the name the epic gives the group. The group
 * is called "Notification preferences" rather than "Notifications" deliberately: the top-level
 * inbox is called Notifications, §7 gives every destination one name, and a settings-search hit
 * reading "Notifications" that opened a preferences form instead of the inbox is exactly the
 * collision this epic exists to remove.
 *
 * Neither panel's heading is redrawn here. "Notifications" and "Quiet hours" are the two jobs this
 * destination holds and both already carry those names, so the page adds the title that says what
 * they have in common rather than a third heading above each of them.
 *
 * Ungated: a person's own notification preferences are theirs, and the workspace defaults that a
 * `WORKSPACE_SETTINGS` holder would set live on Communications, plainly scope-labelled.
 */
export default function PersonalNotifications() {
    const t = useTranslations("SettingsPersonalNotifications");
    const tNav = useTranslations("SettingsNav");

    return (
        <div className="flex flex-col gap-12">
            <Rise>
                <PageHeader
                    title={tNav("groupNotificationPreferences")}
                    description={t("description")}
                />
            </Rise>

            <div className="flex flex-col gap-10">
                <NotificationsPanel />
                <QuietHoursPanel />
            </div>
        </div>
    );
}
