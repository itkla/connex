import { Skeleton } from "@/components/ui/skeleton";

const ADMINISTRATOR_ROWS = 4;
const DOMAIN_ROWS = 2;
const SSO_FIELDS = 4;

/**
 * First-load stand-in for Identity & administrators: the page heading, then the three sections in
 * their reading order — the administrator roster, the domain policy with its add row, and the
 * single sign-on form.
 *
 * The roster's owner-only controls are left out on purpose, as the legacy route's skeleton leaves
 * them out: they are resolved after the panel knows the reader's organization role, so drawing them
 * would promise actions most administrators never get.
 */
export default function OrganizationIdentityLoading() {
    return (
        <div className="flex flex-col gap-12">
            <div className="space-y-2">
                <Skeleton className="h-10 w-64" />
                <Skeleton className="h-4 w-96 max-w-full" />
            </div>

            <section className="space-y-4">
                <div className="space-y-1.5">
                    <Skeleton className="h-4 w-32" />
                    <Skeleton className="h-3.5 w-80 max-w-full" />
                </div>
                <ul className="divide-y divide-border overflow-hidden rounded-2xl border border-border bg-card">
                    {Array.from({ length: ADMINISTRATOR_ROWS }, (_, row) => (
                        <li key={row} className="flex items-center gap-3 px-4 py-3">
                            <Skeleton className="size-9 shrink-0 rounded-full" />
                            <Skeleton className="h-4 w-40" />
                        </li>
                    ))}
                </ul>
            </section>

            <section className="space-y-4">
                <div className="space-y-1.5">
                    <Skeleton className="h-4 w-32" />
                    <Skeleton className="h-3.5 w-80 max-w-full" />
                </div>
                <div className="flex flex-col gap-3 sm:flex-row sm:items-start">
                    <Skeleton className="h-9 w-full flex-1 rounded-md" />
                    <Skeleton className="h-9 w-full rounded-md sm:w-28" />
                </div>
                <ul className="divide-y divide-border overflow-hidden rounded-2xl border border-border bg-card">
                    {Array.from({ length: DOMAIN_ROWS }, (_, row) => (
                        <li key={row} className="flex items-center gap-3 px-4 py-3">
                            <Skeleton className="size-8 shrink-0 rounded-full" />
                            <Skeleton className="h-4 w-40" />
                        </li>
                    ))}
                </ul>
            </section>

            <section className="space-y-4">
                <div className="space-y-1.5">
                    <Skeleton className="h-4 w-32" />
                    <Skeleton className="h-3.5 w-80 max-w-full" />
                </div>
                <div className="space-y-3 rounded-2xl border border-border bg-card p-4">
                    {Array.from({ length: SSO_FIELDS }, (_, field) => (
                        <Skeleton
                            key={field}
                            className={field === SSO_FIELDS - 1 ? "h-9 w-2/3 rounded-md" : "h-9 w-full rounded-md"}
                        />
                    ))}
                </div>
            </section>
        </div>
    );
}
