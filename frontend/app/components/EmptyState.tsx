import * as React from "react";
import type { ComponentType } from "react";

import { cn } from "@/lib/utils";

/** Visual weight of the empty-state icon tile. */
export type EmptyStateTone = "brand" | "muted";

/**
 * How the empty state sits on the page. `card` is the standalone presentation for a whole view;
 * `inline` drops the border and background so the state can sit inside a panel that already has
 * them, without stacking a card inside a card.
 */
export type EmptyStateVariant = "card" | "inline";

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
    variant?: EmptyStateVariant;
    className?: string;
};

const toneTile: Record<EmptyStateTone, string> = {
    brand: "bg-brand-light text-brand-dark",
    muted: "bg-muted text-muted-foreground",
};

const variantShell: Record<EmptyStateVariant, string> = {
    card: "rounded-2xl border border-border bg-card px-6 py-20",
    inline: "px-6 py-12",
};

const variantTile: Record<EmptyStateVariant, string> = {
    card: "size-14 rounded-2xl",
    inline: "size-11 rounded-xl",
};

const variantIcon: Record<EmptyStateVariant, string> = {
    card: "size-7",
    inline: "size-5",
};

const variantTitle: Record<EmptyStateVariant, string> = {
    card: "mt-5 text-lg",
    inline: "mt-4 text-base",
};

const variantAction: Record<EmptyStateVariant, string> = {
    card: "mt-6",
    inline: "mt-5",
};

/**
 * Renders the shared empty-state card in the product's empty-state grammar.
 */
function EmptyState({
    icon: Icon,
    title,
    body,
    action,
    tone = "brand",
    variant = "card",
    className,
}: EmptyStateProps) {
    return (
        <div className={cn(variantShell[variant], "text-center", className)}>
            <div
                className={cn(
                    "mx-auto flex items-center justify-center",
                    variantTile[variant],
                    toneTile[tone],
                )}
            >
                <Icon className={variantIcon[variant]} />
            </div>
            <h2 className={cn(variantTitle[variant], "font-semibold text-foreground")}>{title}</h2>
            {body ? <p className="mx-auto mt-1.5 max-w-sm text-sm text-muted-foreground">{body}</p> : null}
            {action ? (
                <div className={cn(variantAction[variant], "flex items-center justify-center")}>{action}</div>
            ) : null}
        </div>
    );
}

export { EmptyState };
