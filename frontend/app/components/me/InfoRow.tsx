import type { ReactNode } from "react";

/**
 * One labeled field in a record's profile list.
 *
 * `badge` is the slot for a state the value itself cannot carry — a contact who opted out of
 * marketing is still reachable by a person, so the address stays readable and the exclusion is
 * stated beside it rather than instead of it.
 *
 * @param label - the field name
 * @param value - the field's value, already formatted for display
 * @param href - an external destination the value links to
 * @param badge - a state rendered beside the value
 */
export default
function InfoRow({
    label,
    value,
    href,
    badge,
}: {
    label: string;
    value: string;
    href?: string;
    badge?: ReactNode;
}) {
    return (
        <div className="flex flex-col gap-1 px-6 py-4">
            <dt className="text-sm text-muted-foreground">{label}</dt>
            <dd className="flex flex-wrap items-center gap-2 text-base wrap-break-word text-foreground">
                {href && value ? (
                    <a href={href} target="_blank" rel="noopener noreferrer" className="text-brand hover:underline">{value}</a>
                ) : value}
                {badge}
            </dd>
        </div>
    );
}
