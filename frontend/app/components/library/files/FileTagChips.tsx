import type { Tag } from '@/app/lib/types';
import { readableTextColor } from '@/app/lib/utils';

/**
 * Compact, read-only tag chips for a file card / row. Shows up to `max` tags,
 * then a "+N" overflow count.
 * @param tags - The tags to display.
 * @param max - The maximum number of tags to display.
 * @param className - The class name to apply to the span.
 * @returns 
 */
export default function FileTagChips({
    tags,
    max = 2,
    className = '',
}: {
    tags?: Tag[];
    max?: number;
    className?: string;
}) {
    if (!tags || tags.length === 0) return null;
    const shown = tags.slice(0, max);
    const extra = tags.length - shown.length;
    return (
        <span className={`flex min-w-0 items-center gap-1 ${className}`}>
            {shown.map((tag) => (
                <span
                    key={tag.id}
                    title={tag.name}
                    className="inline-flex max-w-[6rem] items-center truncate rounded-full px-1.5 py-0.5 text-[10px] font-medium leading-none"
                    style={{ backgroundColor: tag.color, color: readableTextColor(tag.color) }}
                >
                    <span className="truncate">{tag.name}</span>
                </span>
            ))}
            {extra > 0 && <span className="shrink-0 text-[10px] text-muted-foreground">+{extra}</span>}
        </span>
    );
}