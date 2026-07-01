import { Fragment } from "react";

import { parseNoteContent } from "@/app/lib/references";
import { type NoteReference } from "@/app/lib/types";

import MentionChip from "./MentionChip";

/**
 * Renders note content with inline references resolved to chips. Members become
 * {@link MentionChip}s; unresolved tokens fall back to plain text so raw token
 * syntax is never shown.
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
                if (segment.kind === "reference" && segment.refType === "user") {
                    return <MentionChip key={index} id={segment.id} label={segment.label} />;
                }
                if (segment.kind === "reference") {
                    return <Fragment key={index}>@{segment.label}</Fragment>;
                }
                return <Fragment key={index}>{segment.value}</Fragment>;
            })}
        </span>
    );
}
