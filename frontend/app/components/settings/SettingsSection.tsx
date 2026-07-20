import { cn } from "@/lib/utils";

/**
 * Consistent section header for the workspace settings panels: a clear title with an optional
 * description and a right-aligned action, aligned flush with the cards beneath it. Replaces the
 * legacy uppercase eyebrow so every settings tab reads with the same hierarchy. When `children`
 * are passed the header and content are grouped together; otherwise it renders the header alone.
 */
export function SettingsSection({
    title,
    description,
    action,
    children,
    className,
}: {
    title: string;
    description?: string;
    action?: React.ReactNode;
    children?: React.ReactNode;
    className?: string;
}) {
    return (
        <section className={cn("space-y-4", className)}>
            <div className="flex flex-wrap items-start justify-between gap-x-4 gap-y-2">
                <div className="space-y-1">
                    <h2 className="text-base font-semibold tracking-tight text-foreground text-balance">{title}</h2>
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
