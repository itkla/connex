import Link from 'next/link';
import { BriefcaseIcon, BuildingOffice2Icon, UserIcon } from '@heroicons/react/24/outline';
import type { NoteRecordLink } from '@/app/lib/noteRecordLinks';

const RECORD_ICONS = { person: UserIcon, deal: BriefcaseIcon, company: BuildingOffice2Icon };

/** Shares record labels between the note header and its already-linked list card. */
export default function NoteRecordLinks({ links, interactive = true }: {
    links: NoteRecordLink[];
    interactive?: boolean;
}) {
    return links.map((link) => {
        const Icon = RECORD_ICONS[link.type];
        const content = <><Icon className="size-3.5 shrink-0" aria-hidden /><span className="truncate">{link.label}</span></>;
        return interactive ? (
            <Link key={`${link.type}:${link.id}`} href={link.href}
                className="inline-flex max-w-full items-center gap-1 rounded-md bg-muted px-1.5 py-0.5 text-foreground transition-colors hover:bg-accent">
                {content}
            </Link>
        ) : (
            <span key={`${link.type}:${link.id}`} className="inline-flex max-w-[12rem] items-center gap-1.5">{content}</span>
        );
    });
}
