import { headers } from "next/headers";
import { redirect } from "next/navigation";
import Link from "next/link";
import { Cog6ToothIcon } from "@heroicons/react/24/outline";
import { getLocale, getTranslations } from "next-intl/server";

import {
    getContactTemperaturesFromCookie,
    getContactsFromCookie,
    getCurrentUserFromCookie,
    getDealRisksFromCookie,
    getDealsFromCookie,
    getUserActivitiesFromCookie,
    getUserNotesFromCookie,
    getUserTasksFromCookie,
    getUsers,
} from "@/app/lib/api";
import type { Activity, Contact, Deal, DealRisk, Note, RelationshipTemperature, Task, User } from "@/app/lib/types";
import { formatDate, formatDateTime, pickDominantCurrency } from "@/app/lib/utils";
import { computeKpis } from "@/app/components/overview/analytics/metrics";

import Rise from "@/app/components/motion/Rise";
import { PageShell } from "@/app/components/PageShell";
import SectionHeader from "@/app/components/dashboard/SectionHeader";
import Timeline from "@/app/components/me/Timeline";
import MeHero from "@/app/components/me/MeHero";
import SignalStrip from "@/app/components/me/SignalStrip";
import NeedsYou from "@/app/components/me/NeedsYou";
import PulseStrip from "@/app/components/me/PulseStrip";
import {
    activityPulse,
    constellationNodes,
    coolingRelationships,
    greetingKey,
    riskItems,
    warmthDistribution,
} from "@/app/components/me/meData";

/**
 * Computes the current user's deal KPIs over the trailing 90 days.
 * Kept at module scope so the `Date.now()` read stays out of the component render body.
 */
function personalKpis(deals: Deal[]) {
    return computeKpis(deals, Date.now(), 90);
}

export default async function MePage() {
    const t = await getTranslations("MePage");
    const locale = await getLocale();
    const cookie = (await headers()).get("cookie");
    const user = await getCurrentUserFromCookie(cookie);

    if (!user) {
        redirect("/auth/login");
    }

    const init = { headers: { cookie: cookie ?? "" } } as const;
    const [contacts, deals, tasks, activities, notes, users] = await Promise.all([
        getContactsFromCookie(cookie).catch(() => [] as Contact[]),
        getDealsFromCookie(cookie).catch(() => [] as Deal[]),
        getUserTasksFromCookie(user.id, cookie).catch(() => [] as Task[]),
        getUserActivitiesFromCookie(user.id, cookie).catch(() => [] as Activity[]),
        getUserNotesFromCookie(user.id, cookie).catch(() => [] as Note[]),
        getUsers(init).catch(() => [] as User[]),
    ]);
    const myDeals = deals.filter((deal) => deal.ownerId === user.id);
    const [temps, dealRisks] = await Promise.all([
        getContactTemperaturesFromCookie(cookie, contacts.map((contact) => contact.id))
            .catch(() => [] as RelationshipTemperature[]),
        getDealRisksFromCookie(cookie, myDeals.map((deal) => deal.id))
            .catch(() => [] as DealRisk[]),
    ]);
    const currency = pickDominantCurrency(myDeals);
    const kpis = personalKpis(myDeals);
    const distribution = warmthDistribution(temps);
    const nodes = constellationNodes(temps, contacts, 18);
    const cooling = coolingRelationships(temps, contacts, 6);
    const risks = riskItems(dealRisks, myDeals, 6);
    const pulse = activityPulse(activities, tasks, notes, 84);
    const coolingCount = temps.filter((temp) => temp.trend === "cooling").length;
    const greeting = t(`greeting_${greetingKey(user.timezone)}`);
    const hasWork = tasks.length + activities.length + notes.length > 0;

    return (
        <PageShell tier="wide">
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
                    <SignalStrip kpis={kpis} currency={currency} />
                </Rise>

                <Rise delay={0.14}>
                    <NeedsYou cooling={cooling} risks={risks} />
                </Rise>

                <Rise delay={0.2}>
                    <PulseStrip days={pulse.days} totalTouches={pulse.totalTouches} streak={pulse.streak} />
                </Rise>

                {hasWork && (
                    <Rise delay={0.26}>
                        <section>
                            <SectionHeader title={t("recentWork")} />
                            <div className="overflow-hidden rounded-2xl border border-border bg-card">
                                <Timeline
                                    tasks={tasks}
                                    activities={activities}
                                    notes={notes}
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
                        href="/account"
                        className="inline-flex items-center gap-1.5 font-medium text-brand transition-colors hover:text-brand-hover"
                    >
                        <Cog6ToothIcon className="size-4" />
                        {t("accountSettings")}
                    </Link>
                </footer>
        </PageShell>
    );
}
