export default function StatCard({
    label,
    value,
    subtitle,
    display,
}: {
    label: string;
    value: number;
    subtitle?: string;
    /** Overrides the rendered figure (e.g. a formatted percent) while {@code value} stays the raw number. */
    display?: string;
}) {
    return (
        <div className="flex flex-col rounded-2xl border border-border bg-card px-5 py-4">
            <span className="text-xs font-medium tracking-[0.12em] text-muted-foreground uppercase">
                {label}
            </span>
            <span className="mt-2 text-4xl leading-none tabular-nums text-foreground">{display ?? value}</span>
            {subtitle ? (
                <span className="mt-1 text-xs text-muted-foreground">{subtitle}</span>
            ) : null}
        </div>
    );
}
