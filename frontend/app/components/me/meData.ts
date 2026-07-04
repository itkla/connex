import type {
    Activity,
    Contact,
    Deal,
    DealRisk,
    Note,
    RelationshipTemperature,
    Task,
    TemperatureBand,
} from "@/app/lib/types";
import type { ConstellationNode } from "@/app/components/me/MeHero";
import type { CoolingItem, RiskItem } from "@/app/components/me/NeedsYou";
import type { PulseDay } from "@/app/components/me/PulseStrip";

const BANDS: TemperatureBand[] = ["hot", "warm", "cool", "cold"];
const RISK_ORDER = { high: 3, medium: 2, low: 1 } as const;

/** Counts of relationships in each warmth band. */
export function warmthDistribution(temps: RelationshipTemperature[]): Record<TemperatureBand, number> {
    const dist: Record<TemperatureBand, number> = { hot: 0, warm: 0, cool: 0, cold: 0 };
    for (const temp of temps) dist[temp.band] += 1;
    return dist;
}

/** The warmest relationships, joined to their contact, for the constellation (capped). */
export function constellationNodes(
    temps: RelationshipTemperature[],
    contacts: Contact[],
    cap: number,
): ConstellationNode[] {
    const byId = new Map(contacts.map((c) => [c.id, c]));
    return [...temps]
        .sort((a, b) => b.score - a.score)
        .slice(0, cap)
        .map((temp) => {
            const contact = byId.get(temp.id);
            return {
                id: temp.id,
                name: contact?.name ?? "",
                company: contact?.company?.name ?? null,
                imageUrl: contact?.imageUrl ?? null,
                band: temp.band,
                score: temp.score,
                daysSinceTouch: temp.daysSinceTouch ?? null,
                lastTouchAt: temp.lastTouchAt ?? null,
                trend: temp.trend,
            };
        })
        .filter((node) => node.name.length > 0);
}

/** Cooling relationships that need a touch, most-urgent first, joined to their contact. */
export function coolingRelationships(
    temps: RelationshipTemperature[],
    contacts: Contact[],
    cap: number,
): CoolingItem[] {
    const byId = new Map(contacts.map((c) => [c.id, c]));
    return temps
        .filter((temp) => temp.trend === "cooling")
        .sort((a, b) => (a.daysUntilCold ?? Infinity) - (b.daysUntilCold ?? Infinity))
        .slice(0, cap)
        .map((temp) => {
            const contact = byId.get(temp.id);
            return { id: temp.id, name: contact?.name ?? "", company: contact?.company?.name ?? null, temp };
        })
        .filter((item) => item.name.length > 0);
}

/** The current user's at-risk deals, highest risk first, joined to display fields. */
export function riskItems(dealRisks: DealRisk[], myDeals: Deal[], cap: number): RiskItem[] {
    const byId = new Map(myDeals.map((d) => [d.id, d]));
    return dealRisks
        .filter((r) => r.level !== "none" && byId.has(r.dealId))
        .sort((a, b) => b.score - a.score)
        .slice(0, cap)
        .map((r) => {
            const deal = byId.get(r.dealId)!;
            const topFactor = [...r.factors].sort((a, b) => RISK_ORDER[b.severity] - RISK_ORDER[a.severity])[0];
            return {
                id: r.dealId,
                name: deal.name,
                value: deal.value ?? 0,
                currency: deal.currency ?? "USD",
                level: r.level,
                topFactor: topFactor?.code ?? null,
            };
        });
}

function dayKey(input: string | Date): string {
    const s = typeof input === "string" ? input : input.toISOString();
    return s.slice(0, 10);
}

/** Per-day personal activity over the last `dayCount` days, plus streak + total. */
export function activityPulse(
    activities: Activity[],
    tasks: Task[],
    notes: Note[],
    dayCount: number,
): { days: PulseDay[]; totalTouches: number; streak: number } {
    const counts = new Map<string, number>();
    const bump = (ts?: string | null) => {
        if (!ts) return;
        const key = dayKey(ts.replace(" ", "T"));
        counts.set(key, (counts.get(key) ?? 0) + 1);
    };
    for (const a of activities) bump(a.timestamp);
    for (const n of notes) bump(n.createdAt);
    for (const t of tasks) if (t.completed) bump(t.updatedAt);

    const days: PulseDay[] = [];
    const cursor = new Date();
    cursor.setHours(12, 0, 0, 0);
    for (let i = dayCount - 1; i >= 0; i -= 1) {
        const d = new Date(cursor);
        d.setDate(cursor.getDate() - i);
        const key = dayKey(d);
        days.push({ date: key, count: counts.get(key) ?? 0 });
    }

    const totalTouches = days.reduce((sum, d) => sum + d.count, 0);

    let streak = 0;
    let start = days.length - 1;
    if (start >= 0 && days[start].count === 0) start -= 1;
    for (let i = start; i >= 0; i -= 1) {
        if (days[i].count > 0) streak += 1;
        else break;
    }

    return { days, totalTouches, streak };
}

/** Time-of-day greeting key resolved in the user's timezone. */
export function greetingKey(timezone: string | undefined): "morning" | "afternoon" | "evening" | "night" {
    let hour = new Date().getHours();
    if (timezone) {
        const parsed = Number(
            new Intl.DateTimeFormat("en-US", { hour: "2-digit", hour12: false, timeZone: timezone }).format(new Date()),
        );
        if (Number.isFinite(parsed)) hour = parsed % 24;
    }
    if (hour < 5) return "night";
    if (hour < 12) return "morning";
    if (hour < 18) return "afternoon";
    return "evening";
}

/** The band with the most relationships (defaults to warm when empty). */
export function dominantBand(dist: Record<TemperatureBand, number>): TemperatureBand {
    return BANDS.reduce((best, b) => (dist[b] > dist[best] ? b : best), "warm" as TemperatureBand);
}
