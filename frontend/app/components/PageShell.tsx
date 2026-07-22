import * as React from "react";

import { cn } from "@/lib/utils";

/**
 * The three canonical page-width tiers. `wide` for lists, dashboards, overviews, and settings; `reading`
 * for record-detail and long-form pages; `form` for focused single-column forms. Full-bleed surfaces (the
 * relationship map) don't use PageShell.
 */
export type PageTier = "wide" | "reading" | "form";

const tierMaxWidth: Record<PageTier, string> = {
  wide: "max-w-[100rem]",
  reading: "max-w-5xl",
  form: "max-w-3xl",
};

/**
 * The standard page wrapper for every routed surface that renders inside the app shell's `<main>`. Encodes
 * the design-system page rhythm — outer gutter and vertical padding, a centered max-width column keyed to
 * {@link PageTier}, and the standard `gap-10` between stacked sections — in one place so pages stop
 * copy-pasting the wrapper string. Compose section content as direct children; each becomes a rhythm row.
 */
function PageShell({
  tier = "wide",
  className,
  children,
  ...props
}: React.ComponentProps<"div"> & { tier?: PageTier }) {
  return (
    <div
      data-slot="page-shell"
      className={cn("min-h-full bg-background px-2 pt-8 pb-12", className)}
      {...props}
    >
      <div className={cn("mx-auto flex w-full flex-col gap-10", tierMaxWidth[tier])}>
        {children}
      </div>
    </div>
  );
}

export { PageShell };
