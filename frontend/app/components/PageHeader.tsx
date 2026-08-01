import * as React from "react";

import { cn } from "@/lib/utils";

/**
 * The canonical page-title block: an `h1` at the product's page-title scale, an optional
 * description, and an optional right-aligned action cluster the caller composes. Pure
 * presentation and Server-Component-safe — callers own their entrance motion (wrap in
 * `Rise`) and their own action buttons, so this carries no motion of its own and can be
 * used from any route or layout.
 *
 * Domain identity headers — a record-detail page with a dynamic name and avatar, or the
 * Me and report-document heroes — are §17 domain expression and keep their bespoke
 * header rather than adopting this.
 */
export type PageHeaderProps = {
    title: string;
    description?: string;
    actions?: React.ReactNode;
    variant?: "default" | "compact";
    className?: string;
};

/**
 * Renders a page title, optional description, and optional action cluster in the shared
 * page-header grammar. `variant="compact"` is reserved for genuinely secondary pages.
 */
function PageHeader({ title, description, actions, variant = "default", className }: PageHeaderProps) {
    return (
        <header className={cn("flex flex-wrap items-start justify-between gap-4", className)}>
            <div className="min-w-0 space-y-1.5">
                <h1
                    className={cn(
                        "text-foreground",
                        variant === "compact"
                            ? "text-2xl font-semibold tracking-tight"
                            : "text-4xl font-extrabold tracking-tight text-balance",
                    )}
                >
                    {title}
                </h1>
                {description ? <p className="max-w-2xl text-sm text-muted-foreground">{description}</p> : null}
            </div>
            {actions ? <div className="flex shrink-0 items-center gap-2">{actions}</div> : null}
        </header>
    );
}

export { PageHeader };
