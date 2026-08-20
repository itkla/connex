/**
 * The row-level building blocks the workspace settings panels share, extracted so a panel and a
 * section carved out of it keep the same shape in both of their homes.
 *
 * `MembersPanel` owned all four while allowed domains lived inside its invite tab strip. #1340
 * gives allowed domains its own addressable section on the consolidated People & access page, so
 * the two now render the same rows from one place rather than from two copies that could drift.
 * The organization scope has its own equivalent in `app/components/organization/OrgPrimitives.tsx`;
 * these keep the settings panels' own spacing rather than being folded into it.
 */

/** Shared row-action trigger: a subtle ellipsis button that reveals on row hover. */
export const rowActionTrigger =
    "flex size-7 items-center justify-center rounded-full text-muted-foreground opacity-0 transition hover:bg-muted/70 hover:text-foreground group-hover:opacity-100 focus:opacity-100 focus-visible:opacity-100 data-[state=open]:opacity-100";

/** The bordered card a settings list of rows sits in, with hairlines between the rows. */
export function ListCard({ children }: { children: React.ReactNode }) {
    return (
        <ul className="divide-y divide-border overflow-hidden rounded-2xl border border-border bg-card">
            {children}
        </ul>
    );
}

/** The dashed placeholder that stands in for an empty settings list. */
export function EmptyRow({ children }: { children: React.ReactNode }) {
    return (
        <p className="rounded-2xl border border-dashed border-border bg-card/40 px-4 py-6 text-center text-sm text-muted-foreground">
            {children}
        </p>
    );
}

/** Small heading for a state list nested inside a settings section, with an optional count. */
export function TabListHeading({ title, count }: { title: string; count?: number }) {
    return (
        <div className="flex items-center gap-2">
            <h3 className="text-sm font-medium text-foreground">{title}</h3>
            {count != null && count > 0 ? (
                <span className="text-xs text-muted-foreground tabular-nums">{count}</span>
            ) : null}
        </div>
    );
}
