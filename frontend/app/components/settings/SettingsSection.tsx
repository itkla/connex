import { cn } from "@/lib/utils";

/**
 * Consistent section header for the workspace settings panels: a clear title with an optional
 * description and a right-aligned action, aligned flush with the cards beneath it. Replaces the
 * legacy uppercase eyebrow so every settings tab reads with the same hierarchy. When `children`
 * are passed the header and content are grouped together; otherwise it renders the header alone.
 *
 * `headingLevel` exists for the consolidated destinations of #1340, where a panel that names its
 * own sections is rendered inside a section named for the job it came from. The nested headings step
 * down to `h3` so the page stays one coherent outline rather than repeating `h2` inside `h2`; the
 * rendered size is unchanged, because the level is a structural fact and not a visual one.
 */
export function SettingsSection({
    title,
    description,
    action,
    children,
    className,
    headingLevel = 2,
}: {
    title: string;
    description?: string;
    action?: React.ReactNode;
    children?: React.ReactNode;
    className?: string;
    headingLevel?: 2 | 3;
}) {
    const Heading = headingLevel === 3 ? "h3" : "h2";
    return (
        <section className={cn("space-y-4", className)}>
            <div className="flex flex-wrap items-start justify-between gap-x-4 gap-y-2">
                <div className="space-y-1">
                    <Heading className="text-base font-semibold tracking-tight text-foreground text-balance">
                        {title}
                    </Heading>
                    {description ? (
                        <p className="max-w-prose text-sm text-pretty text-muted-foreground">{description}</p>
                    ) : null}
                </div>
                {action ? <div className="shrink-0">{action}</div> : null}
            </div>
            {children}
        </section>
    );
}
