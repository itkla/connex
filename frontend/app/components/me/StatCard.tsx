export default function StatCard({
    label,
    value,
    subtitle,
}: {
    label: string;
    value: number;
    subtitle?: string;
}) {
    return (
        <div className="flex flex-col rounded-2xl border border-border bg-card px-5 py-4">
            <span className="text-xs font-medium tracking-[0.12em] text-muted-foreground uppercase">
                {label}
            </span>
            <span className="mt-2 text-4xl leading-none text-foreground">{value}</span>
            {subtitle ? (
                <span className="mt-1 text-xs text-muted-foreground">{subtitle}</span>
            ) : null}
        </div>
    );
}
