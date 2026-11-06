import { Suspense } from "react";
import { headers } from "next/headers";
import { redirect } from "next/navigation";
import Link from "next/link";
import { Cog6ToothIcon } from "@heroicons/react/24/outline";
import { getTimeZone, getLocale, getTranslations } from "next-intl/server";

import { getCurrentUserResultFromCookie, getMyWorkReadResultFromCookie, getMyWorkResultFromCookie } from "@/app/lib/api";
import WorkspaceUnavailablePage from "@/app/components/WorkspaceUnavailablePage";
import { formatDate, formatDateTime } from "@/app/lib/utils";
import { settingsDestination } from "@/app/lib/settingsEntryPoints";
import { requestNow } from "@/app/lib/requestClock";
import { Skeleton } from "@/components/ui/skeleton";
import { PageShell } from "@/app/components/PageShell";
import { greetingKey } from "@/app/components/me/meData";
import {
    MyWorkAttentionSection, MyWorkDealSection, MyWorkHero, MyWorkHistorySection,
    MyWorkPulseSection, MyWorkQueueSection,
} from "@/app/components/me/MyWorkSections";

export default async function MePage({ searchParams }: {
    searchParams: Promise<{ currency?: string | string[] }>;
}) {
    const [timeZone, t, locale, requestHeaders, params] = await Promise.all([
        getTimeZone(), getTranslations("MePage"), getLocale(), headers(), searchParams,
    ]);
    const cookie = requestHeaders.get("cookie");
    const userResult = await getCurrentUserResultFromCookie(cookie);
    if (!userResult.ok) {
        if (userResult.unauthenticated) redirect("/auth/login");
        return <WorkspaceUnavailablePage />;
    }
    const user = userResult.data;
    if (!user) redirect("/auth/login");

    const currency = typeof params.currency === "string" ? params.currency : undefined;
    const relationships = getMyWorkReadResultFromCookie("relationships", cookie);
    const deals = getMyWorkReadResultFromCookie("deal-kpis", cookie, currency);
    const queue = getMyWorkResultFromCookie(cookie);
    const risks = getMyWorkReadResultFromCookie("risks", cookie);
    const pulse = getMyWorkReadResultFromCookie("pulse", cookie);
    const history = getMyWorkReadResultFromCookie("history", cookie);
    const greeting = t(`greeting_${greetingKey(timeZone, requestNow())}`);

    return (
        <PageShell>
            <Suspense fallback={<section className="rounded-3xl border border-border bg-card p-6 sm:p-8">
                <p className="text-sm font-medium text-muted-foreground">{greeting}</p>
                <h1 className="mt-1 text-4xl font-extrabold tracking-tight sm:text-5xl">{user.displayName}</h1>
                <Skeleton className="mt-5 min-h-[22rem]" />
            </section>}>
                <MyWorkHero read={relationships} user={user} greeting={greeting} />
            </Suspense>
            <Suspense fallback={<Skeleton className="h-40 rounded-2xl" />}>
                <MyWorkDealSection read={deals} />
            </Suspense>
            <Suspense fallback={<Skeleton className="h-64 rounded-2xl" />}>
                <MyWorkQueueSection read={queue} userId={user.id} />
            </Suspense>
            <Suspense fallback={<Skeleton className="h-48 rounded-2xl" />}>
                <MyWorkAttentionSection relationships={relationships} risks={risks} />
            </Suspense>
            <Suspense fallback={<Skeleton className="h-48 rounded-2xl" />}>
                <MyWorkPulseSection read={pulse} />
            </Suspense>
            <Suspense fallback={<Skeleton className="h-64 rounded-2xl" />}>
                <MyWorkHistorySection read={history} user={user} />
            </Suspense>
            <footer className="flex flex-wrap items-center justify-between gap-3 border-t border-border pt-6 text-sm text-muted-foreground">
                <div className="flex flex-wrap gap-x-6 gap-y-1">
                    <span>{t("memberSince", { date: formatDate(user.createdAt, locale, timeZone) })}</span>
                    <span>{t("lastActive", { date: formatDateTime(user.lastLoginAt, locale, timeZone) })}</span>
                </div>
                <Link href={settingsDestination("account.home").href}
                    className="inline-flex items-center gap-1.5 font-medium text-brand-text transition-colors hover:text-brand-text">
                    <Cog6ToothIcon className="size-4" />
                    {t("accountSettings")}
                </Link>
            </footer>
        </PageShell>
    );
}
