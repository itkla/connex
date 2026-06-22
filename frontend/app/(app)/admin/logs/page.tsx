import { headers } from "next/headers";
import { redirect } from "next/navigation";
import { getCurrentUserFromCookie, getAuditLogs } from "@/app/lib/api";
import { type AuditLogEntry } from "@/app/lib/types";
import AuditLogBrowser from "@/app/components/admin/AuditLogBrowser";

const RECENT_LIMIT = 200;

export default async function AuditLogPage() {
    const cookie = (await headers()).get("cookie");
    const currentUser = await getCurrentUserFromCookie(cookie);

    if (!currentUser) {
        redirect("/auth/login");
    }

    const entries = await getAuditLogs(
        { limit: RECENT_LIMIT },
        { headers: { cookie: cookie ?? "" }, cache: "no-store" },
    ).catch(() => [] as AuditLogEntry[]);

    return <AuditLogBrowser entries={entries} />;
}