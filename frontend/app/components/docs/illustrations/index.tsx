import {
    BellAlertIcon,
    BuildingOffice2Icon,
    CalendarDaysIcon,
    ChatBubbleLeftRightIcon,
    DocumentTextIcon,
    MagnifyingGlassIcon,
    PhoneIcon,
    UsersIcon,
} from "@heroicons/react/24/outline";
import type { IllustrationName } from "@/app/lib/docs/types";

function Avatar({ initials, tone = "brand" }: { initials: string; tone?: "brand" | "muted" }) {
    const toneClass =
        tone === "brand" ? "bg-brand-light text-brand-dark" : "bg-muted text-muted-foreground";
    return (
        <span
            className={`flex size-8 shrink-0 items-center justify-center rounded-full text-xs font-semibold ${toneClass}`}
        >
            {initials}
        </span>
    );
}

function WarmthDot({ band }: { band: "hot" | "warm" | "cool" | "cold" }) {
    const map = {
        hot: "bg-warmth-hot",
        warm: "bg-warmth-warm",
        cool: "bg-warmth-cool",
        cold: "bg-warmth-cold",
    } as const;
    return <span className={`size-2 rounded-full ${map[band]}`} aria-hidden="true" />;
}

function WarmthScale() {
    const bands = [
        { band: "hot", label: "Hot" },
        { band: "warm", label: "Warm" },
        { band: "cool", label: "Cool" },
        { band: "cold", label: "Cold" },
    ] as const;
    return (
        <div className="space-y-3">
            <div className="flex overflow-hidden rounded-full">
                {bands.map((b) => (
                    <span
                        key={b.band}
                        className={`h-2.5 flex-1 ${
                            b.band === "hot"
                                ? "bg-warmth-hot"
                                : b.band === "warm"
                                  ? "bg-warmth-warm"
                                  : b.band === "cool"
                                    ? "bg-warmth-cool"
                                    : "bg-warmth-cold"
                        }`}
                    />
                ))}
            </div>
            <div className="flex justify-between">
                {bands.map((b) => (
                    <span key={b.band} className="flex items-center gap-1.5 text-xs text-muted-foreground">
                        <WarmthDot band={b.band} />
                        {b.label}
                    </span>
                ))}
            </div>
        </div>
    );
}

function ContactCard() {
    return (
        <div className="mx-auto max-w-xs rounded-xl border border-border bg-background p-4">
            <div className="flex items-center gap-3">
                <Avatar initials="MT" />
                <div className="min-w-0 flex-1">
                    <div className="text-sm font-semibold text-foreground">Mika Tanaka</div>
                    <div className="text-xs text-muted-foreground">Head of Sales, Acme Inc.</div>
                </div>
                <span className="flex items-center gap-1.5 rounded-full bg-muted px-2 py-0.5 text-xs font-medium text-foreground">
                    <WarmthDot band="warm" />
                    Warm
                </span>
            </div>
            <div className="mt-3 flex flex-wrap gap-1.5">
                {["Key account", "Champion"].map((tag) => (
                    <span
                        key={tag}
                        className="rounded-full bg-brand-light px-2 py-0.5 text-xs font-medium text-brand-dark"
                    >
                        {tag}
                    </span>
                ))}
            </div>
        </div>
    );
}

function PipelineBoard() {
    const columns = [
        { name: "Lead", deals: [{ n: "Acme Inc.", v: "$12k" }], accent: false },
        { name: "Talking", deals: [{ n: "Globex", v: "$28k" }], accent: false },
        { name: "Won", deals: [{ n: "Initech", v: "$45k" }], accent: true },
    ];
    return (
        <div className="grid grid-cols-3 gap-3">
            {columns.map((col) => (
                <div key={col.name} className="space-y-2">
                    <div className="flex items-center justify-between">
                        <span className="text-xs font-medium text-muted-foreground">{col.name}</span>
                        <span className="text-xs text-muted-foreground">{col.deals.length}</span>
                    </div>
                    {col.deals.map((deal) => (
                        <div
                            key={deal.n}
                            className={`rounded-lg border p-2.5 ${
                                col.accent ? "border-brand/40 bg-brand-light" : "border-border bg-background"
                            }`}
                        >
                            <div
                                className={`truncate text-xs font-medium ${
                                    col.accent ? "text-brand-dark" : "text-foreground"
                                }`}
                            >
                                {deal.n}
                            </div>
                            <div className="mt-1 text-xs tabular-nums text-muted-foreground">{deal.v}</div>
                        </div>
                    ))}
                </div>
            ))}
        </div>
    );
}

function RelationshipMap() {
    const nodes = [
        { id: "you", x: 18, y: 50, r: 7, label: "You", accent: true },
        { id: "a", x: 50, y: 22, r: 6, accent: false },
        { id: "b", x: 52, y: 78, r: 6, accent: false },
        { id: "target", x: 84, y: 40, r: 6, accent: true },
    ];
    return (
        <svg viewBox="0 0 100 100" className="h-40 w-full" preserveAspectRatio="xMidYMid meet" aria-hidden="true">
            <line x1="18" y1="50" x2="50" y2="22" stroke="var(--color-brand)" strokeWidth="1" vectorEffect="non-scaling-stroke" />
            <line x1="50" y1="22" x2="84" y2="40" stroke="var(--color-brand)" strokeWidth="1" vectorEffect="non-scaling-stroke" />
            <line x1="18" y1="50" x2="52" y2="78" stroke="var(--border)" strokeWidth="1" vectorEffect="non-scaling-stroke" />
            <line x1="52" y1="78" x2="84" y2="40" stroke="var(--border)" strokeWidth="1" vectorEffect="non-scaling-stroke" />
            {nodes.map((n) => (
                <circle
                    key={n.id}
                    cx={n.x}
                    cy={n.y}
                    r={n.r}
                    fill={n.accent ? "var(--color-brand)" : "var(--muted)"}
                    stroke="var(--border)"
                    strokeWidth="0.5"
                />
            ))}
        </svg>
    );
}

function DashboardOverview() {
    const stats = [
        { label: "Companies", value: "128" },
        { label: "Contacts", value: "512" },
        { label: "Open deals", value: "$1.2M" },
    ];
    const bars = [40, 62, 48, 78, 90, 70];
    return (
        <div className="space-y-4">
            <div className="grid grid-cols-3 gap-2">
                {stats.map((s) => (
                    <div key={s.label} className="rounded-lg border border-border bg-background px-3 py-2">
                        <div className="text-[10px] uppercase tracking-wide text-muted-foreground">{s.label}</div>
                        <div className="mt-0.5 text-lg font-semibold tabular-nums text-foreground">{s.value}</div>
                    </div>
                ))}
            </div>
            <div className="flex h-16 items-end gap-1.5">
                {bars.map((h, i) => (
                    <div
                        key={i}
                        style={{ height: `${h}%` }}
                        className={`flex-1 rounded-t ${i >= 4 ? "bg-brand" : "bg-brand-light"}`}
                    />
                ))}
            </div>
        </div>
    );
}

function ActivityTimeline() {
    const entries = [
        { Icon: DocumentTextIcon, text: "Note: intro call went well", time: "2h" },
        { Icon: PhoneIcon, text: "Call with Mika Tanaka", time: "1d" },
        { Icon: CalendarDaysIcon, text: "Meeting: quarterly review", time: "3d" },
    ];
    return (
        <ol className="space-y-3">
            {entries.map((e, i) => (
                <li key={i} className="flex items-center gap-3">
                    <span className="flex size-7 shrink-0 items-center justify-center rounded-full bg-brand-light text-brand-dark">
                        <e.Icon className="size-4" />
                    </span>
                    <span className="flex-1 text-xs text-foreground">{e.text}</span>
                    <span className="text-xs text-muted-foreground">{e.time}</span>
                </li>
            ))}
        </ol>
    );
}

function ImportWizard() {
    const rows = [
        { from: "Full Name", to: "Name" },
        { from: "Email Address", to: "Email" },
        { from: "Company", to: "Company" },
    ];
    return (
        <div className="space-y-2">
            <div className="grid grid-cols-[1fr_auto_1fr] items-center gap-2 text-[10px] uppercase tracking-wide text-muted-foreground">
                <span>Your file</span>
                <span />
                <span>Connex field</span>
            </div>
            {rows.map((r) => (
                <div key={r.from} className="grid grid-cols-[1fr_auto_1fr] items-center gap-2">
                    <span className="truncate rounded-md border border-border bg-muted px-2.5 py-1.5 text-xs text-foreground">
                        {r.from}
                    </span>
                    <span className="text-muted-foreground">&rarr;</span>
                    <span className="truncate rounded-md border border-brand/30 bg-brand-light px-2.5 py-1.5 text-xs font-medium text-brand-dark">
                        {r.to}
                    </span>
                </div>
            ))}
        </div>
    );
}

function WarmIntroPath() {
    const people = [
        { initials: "You", label: "You", accent: true },
        { initials: "RS", label: "Riku S.", accent: false },
        { initials: "MT", label: "Target", accent: true },
    ];
    return (
        <div className="flex items-center justify-center gap-2">
            {people.map((p, i) => (
                <div key={p.label} className="flex items-center gap-2">
                    <div className="flex flex-col items-center gap-1.5">
                        <Avatar initials={p.initials} tone={p.accent ? "brand" : "muted"} />
                        <span className="text-[11px] text-muted-foreground">{p.label}</span>
                    </div>
                    {i < people.length - 1 ? (
                        <span className="mb-5 h-px w-8 bg-brand" aria-hidden="true" />
                    ) : null}
                </div>
            ))}
        </div>
    );
}

function NotificationInbox() {
    const items = [
        { Icon: ChatBubbleLeftRightIcon, text: "Riku mentioned you on Acme Inc.", time: "5m", unread: true },
        { Icon: BellAlertIcon, text: "Task due today: follow up with Globex", time: "1h", unread: false },
        { Icon: UsersIcon, text: "Mika Tanaka changed companies", time: "2h", unread: false },
    ];
    return (
        <ul className="divide-y divide-border overflow-hidden rounded-lg border border-border">
            {items.map((it, i) => (
                <li key={i} className="flex items-center gap-3 bg-background px-3 py-2.5">
                    <span className="flex size-7 shrink-0 items-center justify-center rounded-full bg-muted text-muted-foreground">
                        <it.Icon className="size-4" />
                    </span>
                    <span className="flex-1 truncate text-xs text-foreground">{it.text}</span>
                    <span className="text-xs text-muted-foreground">{it.time}</span>
                    {it.unread ? <span className="size-2 rounded-full bg-brand" aria-hidden="true" /> : null}
                </li>
            ))}
        </ul>
    );
}

function GlobalSearch() {
    const groups = [
        { label: "People", Icon: UsersIcon, items: ["Mika Tanaka", "Riku Sato"] },
        { label: "Companies", Icon: BuildingOffice2Icon, items: ["Acme Inc."] },
    ];
    return (
        <div className="mx-auto max-w-sm space-y-2">
            <div className="flex items-center gap-2 rounded-lg border border-border bg-background px-3 py-2">
                <MagnifyingGlassIcon className="size-4 text-muted-foreground" />
                <span className="text-xs text-muted-foreground">Search people, companies, deals</span>
            </div>
            <div className="overflow-hidden rounded-lg border border-border bg-background">
                {groups.map((g) => (
                    <div key={g.label} className="border-b border-border p-2 last:border-0">
                        <div className="px-1 pb-1 text-[10px] uppercase tracking-wide text-muted-foreground">
                            {g.label}
                        </div>
                        {g.items.map((item) => (
                            <div key={item} className="flex items-center gap-2 rounded-md px-1 py-1.5">
                                <g.Icon className="size-3.5 text-muted-foreground" />
                                <span className="text-xs text-foreground">{item}</span>
                            </div>
                        ))}
                    </div>
                ))}
            </div>
        </div>
    );
}

/**
 * Registry mapping each {@link IllustrationName} to its static, theme-aware
 * illustration component. Completeness is enforced by the `Record` type.
 */
export const ILLUSTRATIONS: Record<IllustrationName, React.ComponentType> = {
    "warmth-scale": WarmthScale,
    "contact-card": ContactCard,
    "pipeline-board": PipelineBoard,
    "relationship-map": RelationshipMap,
    "dashboard-overview": DashboardOverview,
    "activity-timeline": ActivityTimeline,
    "import-wizard": ImportWizard,
    "warm-intro-path": WarmIntroPath,
    "notification-inbox": NotificationInbox,
    "global-search": GlobalSearch,
};
