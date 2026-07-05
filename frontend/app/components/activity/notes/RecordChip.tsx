"use client";

import Link from "next/link";
import {
    BriefcaseIcon,
    BuildingOffice2Icon,
    DocumentTextIcon,
    PaperClipIcon,
    UserIcon,
} from "@heroicons/react/24/outline";

import { HoverCard, HoverCardContent, HoverCardTrigger } from "@/components/ui/hover-card";

import RecordPreview from "./RecordPreview";

type RecordType = "person" | "deal" | "company" | "note" | "file";

const RECORD: Record<RecordType, { href: (id: number) => string; Icon: typeof UserIcon }> = {
    person: { href: (id) => `/records/contacts/${id}`, Icon: UserIcon },
    deal: { href: (id) => `/records/deals/${id}`, Icon: BriefcaseIcon },
    company: { href: (id) => `/records/companies/${id}`, Icon: BuildingOffice2Icon },
    note: { href: (id) => `/activity/notes/${id}`, Icon: DocumentTextIcon },
    file: { href: (id) => `/library/files?file=${id}`, Icon: PaperClipIcon },
};

/**
 * Renders an inline reference to a CRM record (contact, deal, or company) inside
 * note content: a compact pill linking to the record, with a hover preview card.
 * The chip is a real link — the hover card is a supplementary enhancement.
 */
export default function RecordChip({ type, id, label }: { type: RecordType; id: number; label: string }) {
    const record = RECORD[type];
    const link = (
        <Link
            href={record.href(id)}
            onClick={(event) => event.stopPropagation()}
            className="mx-px inline-flex max-w-[16rem] items-center gap-1 rounded-md bg-muted px-1 py-px align-baseline text-[0.95em] font-medium text-foreground transition-colors hover:bg-muted/70 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/50"
        >
            <record.Icon className="size-3 shrink-0 text-muted-foreground" />
            <span className="truncate">{label}</span>
        </Link>
    );
    if (type === "person" || type === "deal" || type === "company") {
        return (
            <HoverCard>
                <HoverCardTrigger render={link} />
                <HoverCardContent>
                    <RecordPreview type={type} id={id} />
                </HoverCardContent>
            </HoverCard>
        );
    }
    return link;
}
