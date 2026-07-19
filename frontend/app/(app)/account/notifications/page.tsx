import NotificationsPanel from "@/app/components/account/NotificationsPanel";
import QuietHoursPanel from "@/app/components/account/QuietHoursPanel";

export default function AccountNotificationsPage() {
    return (
        <div className="space-y-10">
            <NotificationsPanel />
            <QuietHoursPanel />
        </div>
    );
}
