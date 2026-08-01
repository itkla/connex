import * as React from "react";
import type { ComponentType } from "react";

import { cn } from "@/lib/utils";

/** Visual weight of the empty-state icon tile. */
export type EmptyStateTone = "brand" | "muted";

/**
 * The shared empty-state card: an icon tile, a heading, an optional body line, and an
 * optional call to action. `tone="brand"` teaches and encourages on a genuine first-run
 * empty; `tone="muted"` is the neutral "no results" presentation. Pure presentation —
 * the caller supplies already-localized copy and composes its own CTA, so this stays
 * Server-Component-safe and reusable from any surface.
 */
export type EmptyStateProps = {
    icon: ComponentType<{ className?: string }>;
    title: string;
    body?: string;
    action?: React.ReactNode;
    tone?: EmptyStateTone;
    className?: string;
};

const toneTile: Record<EmptyStateTone, string> = {
    brand: "bg-brand-light text-brand-dark",
    muted: "bg-muted text-muted-foreground",
};

/**
 * Renders the shared empty-state card in the product's empty-state grammar.
 */
function EmptyState({ icon: Icon, title, body, action, tone = "brand", className }: EmptyStateProps) {
    return (
        <div className={cn("rounded-2xl border border-border bg-card px-6 py-20 text-center", className)}>
            <div className={cn("mx-auto flex size-14 items-center justify-center rounded-2xl", toneTile[tone])}>
                <Icon className="size-7" />
            </div>
            <h2 className="mt-5 text-lg font-semibold text-foreground">{title}</h2>
            {body ? <p className="mx-auto mt-1.5 max-w-sm text-sm text-muted-foreground">{body}</p> : null}
            {action ? <div className="mt-6 flex items-center justify-center">{action}</div> : null}
        </div>
    );
}

export { EmptyState };
