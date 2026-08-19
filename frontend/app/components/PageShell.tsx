import * as React from "react";

import { cn } from "@/lib/utils";

/**
 * The standard page wrapper for every routed surface that renders inside the app shell's `<main>`. Encodes
 * the design-system page rhythm — the responsive page gutter, vertical padding, and the standard `gap-10`
 * between stacked sections — in one place so pages stop copy-pasting the wrapper string. Compose section
 * content as direct children; each becomes a rhythm row.
 *
 * The column is deliberately uncapped: every page spans the full content area at every viewport, so no
 * surface pays for a dead gutter. A surface that needs a readable measure applies it to the text block
 * itself — never to the page — and form and data layouts stay responsive from the inside. The gutter is
 * tight by default and steps up once at `2xl`, where the content band is wide enough that content would
 * otherwise sit against the frame.
 */
function PageShell({ className, children, ...props }: React.ComponentProps<"div">) {
  return (
    <div
      data-slot="page-shell"
      className={cn("min-h-full bg-background px-2 pt-8 pb-12 2xl:px-6", className)}
      {...props}
    >
      <div className="flex w-full flex-col gap-10">{children}</div>
    </div>
  );
}

export { PageShell };
