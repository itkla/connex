import type { Metadata } from "next";
import { headers } from "next/headers";
import { redirect } from "next/navigation";
import { getCurrentUserFromCookie, getAuditLogs } from "@/app/lib/api";
import { type AuditLogEntry } from "@/app/lib/types";
import AuditLogBrowser from "@/app/components/admin/AuditLogBrowser";

export const metadata: Metadata = {
    title: "Audit log",
    description: "Recent activity across your workspace",
};

const PAGE_SIZE = 200;

export default async function AuditLogPage() {
    const cookie = (await headers()).get("cookie");
    const currentUser = await getCurrentUserFromCookie(cookie);

    if (!currentUser) {
        redirect("/auth/login");
    }

    const entries = await getAuditLogs(
        { limit: PAGE_SIZE, offset: 0 },
        { headers: { cookie: cookie ?? "" }, cache: "no-store" },
    ).catch(() => [] as AuditLogEntry[]);

    return <AuditLogBrowser initialEntries={entries} pageSize={PAGE_SIZE} />;
}