/** Whether a catalog entry can be put on a deal today, as a dot-and-label status pill. */
export default function ProductAvailability({
    active,
    activeLabel,
    inactiveLabel,
}: {
    active: boolean;
    activeLabel: string;
    inactiveLabel: string;
}) {
    return (
        <span
            className={active
                ? 'inline-flex shrink-0 items-center gap-1.5 rounded-full bg-chart-won/10 px-2 py-1 text-xs font-medium text-chart-won'
                : 'inline-flex shrink-0 items-center gap-1.5 rounded-full bg-muted px-2 py-1 text-xs font-medium text-muted-foreground'}
        >
            <span aria-hidden="true" className="size-1.5 rounded-full bg-current" />
            {active ? activeLabel : inactiveLabel}
        </span>
    );
}
