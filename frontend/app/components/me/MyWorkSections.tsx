import { redirect } from "next/navigation";
import Link from "next/link";
import { getTranslations } from "next-intl/server";

import type { CookieResult, MyWorkReadResult } from "@/app/lib/api";
import type { MyWorkReadSections, User, WorkItemPage } from "@/app/lib/types";
import SectionUnavailable from "@/app/components/SectionUnavailable";
import { Button } from "@/components/ui/button";
import SectionHeader from "@/app/components/dashboard/SectionHeader";
import MeHero from "./MeHero";
import MyWorkQueue from "./MyWorkQueue";
import NeedsYou from "./NeedsYou";
import PersonalDealSignals from "./PersonalDealSignals";
import PulseStrip from "./PulseStrip";
import Timeline from "./Timeline";
import { activityPulse, constellationNodes, coolingRelationships, riskItems } from "./meData";

type SectionRead<K extends keyof MyWorkReadSections> = Promise<MyWorkReadResult<MyWorkReadSections[K]>>;

/** Shared section recovery with distinct expired-session and permission handling. */
export async function MyWorkSectionFailure({ status }: { status: number }) {
    if (status === 401) redirect("/auth/login");
    const t = await getTranslations("MePage");
    return status === 403
        ? <SectionUnavailable title={t("accessDeniedTitle")} body={t("accessDeniedBody")} />
        : <SectionUnavailable />;
}

/** Streams relationship intelligence without holding the personal queue behind it. */
export async function MyWorkHero({ read, user, greeting }: {
    read: SectionRead<"relationships">; user: User; greeting: string;
}) {
    const result = await read;
    if (!result.ok) return <section className="space-y-5">
        <header>
            <p className="text-sm font-medium text-muted-foreground">{greeting}</p>
            <h1 className="mt-1 text-4xl font-extrabold tracking-tight sm:text-5xl">{user.displayName}</h1>
        </header>
        <MyWorkSectionFailure status={result.status} />
    </section>;
    const { contacts, temperatures, counts } = result.data;
    return <MeHero user={user} greeting={greeting} nodes={constellationNodes(temperatures, contacts, 18)}
        distribution={counts} coolingCount={counts.cooling} />;
}

/** The existing source-owned queue streams independently of supporting projections. */
export async function MyWorkQueueSection({ read, userId }: { read: Promise<CookieResult<WorkItemPage>>; userId: number }) {
    const result = await read;
    if (!result.ok && result.unauthenticated) redirect("/auth/login");
    return <MyWorkQueue userId={userId} initial={result} />;
}

/** Every scalar, comparison, and sparkline comes from the selected server-side currency partition. */
export async function MyWorkDealSection({ read }: { read: SectionRead<"deal-kpis"> }) {
    const result = await read;
    if (result.ok) return <PersonalDealSignals data={result.data} />;
    const t = await getTranslations("MePage");
    return <section aria-label={t("dealSignals")}>
        <SectionHeader title={t("dealSignals")} />
        <MyWorkSectionFailure status={result.status} />
    </section>;
}

/** Failure of either attention source cannot fabricate an all-clear message. */
export async function MyWorkAttentionSection({ relationships, risks }: {
    relationships: SectionRead<"relationships">; risks: SectionRead<"risks">;
}) {
    const [network, deals] = await Promise.all([relationships, risks]);
    const t = await getTranslations("MePage");
    return <NeedsYou
        cooling={network.ok ? coolingRelationships(network.data.temperatures, network.data.contacts, 6) : []}
        risks={deals.ok ? riskItems(deals.data.risks, deals.data.deals, 6) : []}
        coolingFailure={network.ok ? undefined : <MyWorkSectionFailure status={network.status} />}
        riskFailure={!deals.ok ? <MyWorkSectionFailure status={deals.status} /> : deals.data.inputLimitExceeded
            ? <div>
                <SectionUnavailable title={t("riskCapacityTitle")} body={t("riskCapacityBody")} />
                <div className="px-6 pb-5">
                    <Button asChild variant="outline" size="inline"><Link href="/records/deals">{t("viewDeals")}</Link></Button>
                </div>
            </div> : undefined}
    />;
}

/** Daily aggregates stay complete even when history previews are capped. */
export async function MyWorkPulseSection({ read }: { read: SectionRead<"pulse"> }) {
    const result = await read;
    if (!result.ok) return <MyWorkSectionFailure status={result.status} />;
    const pulse = activityPulse([], [], result.data, 84);
    return <PulseStrip days={pulse.days} totalTouches={pulse.totalTouches} streak={pulse.streak} />;
}

/** Failed recent-work reads remain visible with Retry; successful empty history stays empty. */
export async function MyWorkHistorySection({ read, user }: { read: SectionRead<"history">; user: User }) {
    const result = await read;
    const t = await getTranslations("MePage");
    return <section aria-label={t("recentWork")}>
        <SectionHeader title={t("recentWork")} />
        {result.ok ? <>
            <p className="mb-3 text-sm text-muted-foreground">{t("recentWorkScope")}</p>
            <div className="overflow-hidden rounded-2xl border border-border bg-card">
                <Timeline {...result.data} noteTarget={{ type: "user", id: user.id }} users={[user]} currentUserId={user.id} />
            </div>
        </> : <MyWorkSectionFailure status={result.status} />}
    </section>;
}
