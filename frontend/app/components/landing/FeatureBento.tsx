import { getTranslations } from "next-intl/server";
import Reveal from "@/app/components/landing/Reveal";

/**
 * Feature grid for the landing page. Each cell carries a small diagram of the
 * thing it names rather than a generic icon, and the cells are deliberately
 * unequal so the grid reads as a composition instead of six identical boxes.
 */

const titleClass = "text-lg font-semibold tracking-tight text-foreground";
const bodyClass = "mt-2 text-[15px] leading-relaxed text-muted-foreground text-pretty [word-break:auto-phrase]";

const WARMTH_MOTIF_BANDS = ["bg-warmth-cold", "bg-warmth-cool", "bg-warmth-warm", "bg-warmth-hot"];

function WarmthMotif() {
    return (
        <div className="mt-7 flex max-w-[260px] items-end gap-1.5" aria-hidden="true">
            {WARMTH_MOTIF_BANDS.map((band, i) => (
                <span
                    key={band}
                    className={`flex-1 rounded-full ${band}`}
                    style={{ height: `${10 + i * 8}px` }}
                />
            ))}
        </div>
    );
}

function RadarMotif() {
    return (
        <svg
            viewBox="0 0 200 96"
            className="mt-7 h-24 w-full max-w-[230px]"
            preserveAspectRatio="xMinYMid meet"
            fill="none"
            aria-hidden="true"
        >
            {[26, 46, 66].map((r) => (
                <circle key={r} cx="26" cy="86" r={r} className="stroke-border" strokeWidth="1" />
            ))}
            <circle cx="72" cy="52" r="4" className="fill-warmth-cool" />
            <circle cx="118" cy="34" r="4" className="fill-warmth-cold" />
            <circle cx="52" cy="70" r="4" className="fill-warmth-warm" />
            <path d="M 26 86 L 150 18" className="stroke-brand" strokeWidth="1.5" strokeLinecap="round" />
        </svg>
    );
}

function IntroPathMotif() {
    return (
        <svg
            viewBox="0 0 200 96"
            className="mt-7 h-24 w-full max-w-[230px]"
            preserveAspectRatio="xMinYMid meet"
            fill="none"
            aria-hidden="true"
        >
            <path d="M 20 74 L 78 34 L 140 60 L 186 26" className="stroke-border" strokeWidth="1.5" />
            <path d="M 20 74 L 78 34 L 186 26" className="stroke-brand" strokeWidth="2" strokeLinecap="round" />
            <circle cx="20" cy="74" r="5.5" className="fill-brand" />
            <circle cx="78" cy="34" r="5.5" className="fill-background stroke-brand" strokeWidth="2" />
            <circle cx="140" cy="60" r="4.5" className="fill-background stroke-border" strokeWidth="2" />
            <circle cx="186" cy="26" r="5.5" className="fill-background stroke-brand" strokeWidth="2" />
        </svg>
    );
}

function HistoryMotif({ nowLabel }: { nowLabel: string }) {
    return (
        <div className="mt-7 flex max-w-sm items-center gap-2 text-xs" aria-hidden="true">
            <span className="rounded-md border border-border bg-muted px-2.5 py-1 text-muted-foreground">2019</span>
            <span className="h-px flex-1 bg-border" />
            <span className="rounded-md border border-border bg-muted px-2.5 py-1 text-muted-foreground">2023</span>
            <span className="h-px flex-1 bg-brand" />
            <span className="rounded-md bg-brand px-2.5 py-1 font-medium text-brand-foreground">{nowLabel}</span>
        </div>
    );
}

function IsolationMotif() {
    return (
        <div className="mt-7 grid max-w-[260px] grid-cols-2 gap-2" aria-hidden="true">
            <div className="space-y-1.5 rounded-lg border border-brand/40 bg-brand-light/40 p-2.5">
                <span className="block h-1.5 w-3/4 rounded-full bg-brand/60" />
                <span className="block h-1.5 w-1/2 rounded-full bg-brand/40" />
            </div>
            <div className="space-y-1.5 rounded-lg border border-dashed border-border p-2.5">
                <span className="block h-1.5 w-2/3 rounded-full bg-border" />
                <span className="block h-1.5 w-1/3 rounded-full bg-border" />
            </div>
        </div>
    );
}

function AuditMotif() {
    return (
        <div className="mt-7 flex flex-wrap items-center gap-3" aria-hidden="true">
            {["w-16", "w-24", "w-14", "w-20", "w-28"].map((w, i) => (
                <div key={w} className="flex items-center gap-3">
                    <span className={`size-1.5 shrink-0 rounded-full ${i === 4 ? "bg-brand" : "bg-border"}`} />
                    <span className={`h-1.5 rounded-full ${i === 4 ? "bg-brand/50" : "bg-border"} ${w}`} />
                </div>
            ))}
        </div>
    );
}

export default async function FeatureBento() {
    const t = await getTranslations("CommonHome");

    const cells = [
        { key: "Warmth", span: "sm:col-span-2 lg:col-span-7", motif: <WarmthMotif /> },
        { key: "Radar", span: "sm:col-span-2 lg:col-span-5", motif: <RadarMotif /> },
        { key: "Intro", span: "sm:col-span-1 lg:col-span-5", motif: <IntroPathMotif /> },
        { key: "History", span: "sm:col-span-1 lg:col-span-7", motif: <HistoryMotif nowLabel={t("bentoHistoryNow")} /> },
        { key: "Isolation", span: "sm:col-span-1 lg:col-span-4", motif: <IsolationMotif /> },
        { key: "Audit", span: "sm:col-span-1 lg:col-span-8", motif: <AuditMotif /> },
    ];

    return (
        <div className="mt-14 grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-12">
            {cells.map((cell, i) => (
                <Reveal key={cell.key} delay={i * 0.05} className={cell.span}>
                    <div className="group relative flex h-full grow flex-col overflow-hidden rounded-2xl border border-border bg-card p-6 transition-[transform,border-color,box-shadow] duration-(--motion-standard) ease-(--motion-ease-calm) hover:-translate-y-0.5 hover:border-brand/40 hover:shadow-[0_18px_40px_-24px_var(--color-brand)] motion-reduce:transition-none motion-reduce:hover:translate-y-0">
                        <div>
                            <h3 className={titleClass}>{t(`bento${cell.key}Title`)}</h3>
                            <p className={bodyClass}>{t(`bento${cell.key}Body`)}</p>
                        </div>
                        {cell.motif}
                    </div>
                </Reveal>
            ))}
        </div>
    );
}
