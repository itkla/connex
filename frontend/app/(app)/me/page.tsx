import { headers } from "next/headers";
import { redirect } from "next/navigation";
import Link from "next/link";
import { Cog6ToothIcon } from "@heroicons/react/24/outline";
import { getLocale, getTranslations } from "next-intl/server";

import {
    getContactTemperaturesFromCookie,
    getContactsFromCookie,
    getCurrentUserResultFromCookie,
    getDealMetricsFromCookie,
    getDealRisksFromCookie,
    getDealsFromCookie,
    getMyWorkResultFromCookie,
    getMyWorkspacesFromCookie,
    getUserActivitiesFromCookie,
    getUserNotesFromCookie,
    getUserNoteActivityResultFromCookie,
    getUserTasksFromCookie,
    getUsers,
} from "@/app/lib/api";
import SectionUnavailable from "@/app/components/SectionUnavailable";
import WorkspaceUnavailablePage from "@/app/components/WorkspaceUnavailablePage";
import type { Activity, Contact, DealRisk, Note, RelationshipTemperature, Task, User } from "@/app/lib/types";
import { formatDate, formatDateTime } from "@/app/lib/utils";
import { settingsDestination } from "@/app/lib/settingsEntryPoints";
import { resolveWorkspaceTimezone } from "@/app/lib/workspaceSnapshot";

import Rise from "@/app/components/motion/Rise";
import { PageShell } from "@/app/components/PageShell";
import SectionHeader from "@/app/components/dashboard/SectionHeader";
import Timeline from "@/app/components/me/Timeline";
import MeHero from "@/app/components/me/MeHero";
import PersonalDealSignals from "@/app/components/me/PersonalDealSignals";
import NeedsYou from "@/app/components/me/NeedsYou";
import MyWorkQueue from "@/app/components/me/MyWorkQueue";
import PulseStrip from "@/app/components/me/PulseStrip";
import {
    activityPulse,
    constellationNodes,
    coolingRelationships,
    greetingKey,
    riskItems,
    warmthDistribution,
} from "@/app/components/me/meData";

export default async function MePage() {
    const t = await getTranslations("MePage");
    const locale = await getLocale();
    const cookie = (await headers()).get("cookie");
    const userResult = await getCurrentUserResultFromCookie(cookie);

    if (!userResult.ok) {
        return <WorkspaceUnavailablePage />;
    }
    const user = userResult.data;
    if (!user) {
        redirect("/auth/login");
    }

    const init = { headers: { cookie: cookie ?? "" } } as const;
    const [contacts, dealResult, tasks, activities, notes, users, myWorkResult, noteActivityResult, dealMetrics, workspaces]
        = await Promise.all([
            getContactsFromCookie(cookie).catch(() => [] as Contact[]),
            getDealsFromCookie(cookie).catch(() => null),
            getUserTasksFromCookie(user.id, cookie).catch(() => [] as Task[]),
            getUserActivitiesFromCookie(user.id, cookie).catch(() => [] as Activity[]),
            getUserNotesFromCookie(user.id, cookie, { size: 25 }).catch(() => [] as Note[]),
            getUsers(init).catch(() => [] as User[]),
            getMyWorkResultFromCookie(cookie),
            getUserNoteActivityResultFromCookie(user.id, cookie),
            getDealMetricsFromCookie(cookie, { scope: "me" }).catch(() => null),
            getMyWorkspacesFromCookie(cookie),
        ]);
    const deals = dealResult ?? [];
    const myDeals = deals.filter((deal) => deal.ownerId === user.id);
    const [temps, dealRisks] = await Promise.all([
        getContactTemperaturesFromCookie(cookie, contacts.map((contact) => contact.id))
            .catch(() => [] as RelationshipTemperature[]),
        getDealRisksFromCookie(cookie, myDeals.map((deal) => deal.id))
            .catch(() => [] as DealRisk[]),
    ]);
    const timezone = resolveWorkspaceTimezone(workspaces, user.timezone);
    const distribution = warmthDistribution(temps);
    const nodes = constellationNodes(temps, contacts, 18);
    const cooling = coolingRelationships(temps, contacts, 6);
    const risks = riskItems(dealRisks, myDeals, 6);
    const pulse = noteActivityResult.ok ? activityPulse(activities, tasks, noteActivityResult.data, 84) : null;
    const coolingCount = temps.filter((temp) => temp.trend === "cooling").length;
    const greeting = t(`greeting_${greetingKey(user.timezone)}`);
    const hasWork = tasks.length + activities.length + notes.length > 0;

    return (
        <PageShell>
                <Rise>
                    <MeHero
                        user={user}
                        greeting={greeting}
                        nodes={nodes}
                        distribution={distribution}
                        coolingCount={coolingCount}
                    />
                </Rise>

                <Rise delay={0.08}>
                    {dealResult ? (
                        <PersonalDealSignals
                            key={`${user.id}:${workspaces.activeWorkspaceId}`}
                            deals={myDeals}
                            dealMetrics={dealMetrics}
                            timezone={timezone}
                        />
                    ) : <SectionUnavailable />}
                </Rise>

                <Rise delay={0.14}>
                    <MyWorkQueue userId={user.id} initial={myWorkResult} />
                </Rise>

                <Rise delay={0.2}>
                    <NeedsYou cooling={cooling} risks={risks} />
                </Rise>

                <Rise delay={0.26}>
                    {pulse ? <PulseStrip days={pulse.days} totalTouches={pulse.totalTouches} streak={pulse.streak} />
                        : <SectionUnavailable />}
                </Rise>

                {hasWork && (
                    <Rise delay={0.32}>
                        <section>
                            <SectionHeader title={t("recentWork")} />
                            <div className="overflow-hidden rounded-2xl border border-border bg-card">
                                <Timeline
                                    tasks={tasks}
                                    activities={activities}
                                    notes={notes.slice(0, 25)}
                                    noteTarget={{ type: "user", id: user.id }}
                                    users={users}
                                    persons={contacts}
                                    deals={deals}
                                    currentUserId={user.id}
                                />
                            </div>
                        </section>
                    </Rise>
                )}

                <footer className="flex flex-wrap items-center justify-between gap-3 border-t border-border pt-6 text-sm text-muted-foreground">
                    <div className="flex flex-wrap gap-x-6 gap-y-1">
                        <span>{t("memberSince", { date: formatDate(user.createdAt, locale) })}</span>
                        <span>{t("lastActive", { date: formatDateTime(user.lastLoginAt, locale) })}</span>
                    </div>
                    <Link
                        href={settingsDestination("account.home").href}
                        className="inline-flex items-center gap-1.5 font-medium text-brand-text transition-colors hover:text-brand-text"
                    >
                        <Cog6ToothIcon className="size-4" />
                        {t("accountSettings")}
                    </Link>
                </footer>
        </PageShell>
    );
}
