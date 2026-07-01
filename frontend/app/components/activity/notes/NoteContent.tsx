import { Fragment } from "react";

import { parseNoteContent } from "@/app/lib/references";
import { type NoteReference } from "@/app/lib/types";

import MentionChip from "./MentionChip";
import RecordChip from "./RecordChip";

/**
 * Renders note content with inline references resolved to chips. Members become
 * {@link MentionChip}s; contacts, deals, and companies become {@link RecordChip}s;
 * unresolved tokens fall back to plain text so raw token syntax is never shown.
 */
export default function NoteContent({
    content,
    references,
    className,
}: {
    content: string;
    references?: NoteReference[];
    className?: string;
}) {
    const segments = parseNoteContent(content, references ?? []);
    return (
        <span className={className}>
            {segments.map((segment, index) => {
                if (segment.kind === "text") {
                    return <Fragment key={index}>{segment.value}</Fragment>;
                }
                if (segment.refType === "user") {
                    return <MentionChip key={index} id={segment.id} label={segment.label} />;
                }
                return <RecordChip key={index} type={segment.refType} id={segment.id} label={segment.label} />;
            })}
        </span>
    );
}
