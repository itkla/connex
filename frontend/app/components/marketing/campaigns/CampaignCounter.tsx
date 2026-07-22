/**
 * A single labelled counter used across the campaign delivery, engagement, and export surfaces.
 * Callers pre-format the value (localized number, percentage, or an em dash when unavailable) so
 * this stays a pure presentational cell.
 */
export default function CampaignCounter({ label, value }: { label: string; value: string }) {
    return (
        <div className="flex flex-col gap-0.5">
            <span className="text-[0.6875rem] font-medium uppercase tracking-[0.1em] text-muted-foreground">
                {label}
            </span>
            <span className="tabular-nums text-sm font-semibold text-foreground">{value}</span>
        </div>
    );
}
