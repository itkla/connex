"use client";

import Link from "next/link";

/**
 * Renders a member @-mention inside note content: an accent-coloured, focusable
 * link to the member's profile. The chip is reachable without hover — the hover
 * preview card is a later enhancement (issue #190).
 */
export default function MentionChip({ id, label }: { id: number; label: string }) {
    return (
        <Link
            href={`/users/${id}`}
            onClick={(event) => event.stopPropagation()}
            className="rounded-sm px-0.5 font-medium text-brand-dark transition-colors duration-150 hover:bg-brand-light/50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand/40"
        >
            @{label}
        </Link>
    );
}
