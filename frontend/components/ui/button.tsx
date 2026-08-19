import * as React from "react"
import { cva, type VariantProps } from "class-variance-authority"
import { Slot } from "radix-ui"
import { ChevronDownIcon } from "@heroicons/react/24/solid"

import { cn } from "@/lib/utils"

/**
 * The four button contexts of the D4 law (`docs/PRODUCT.md` §6 "Buttons"): one consistent height
 * per context. The heights are read off the live reference pages rather than invented — a page
 * header's action cluster and a dialog footer both commit at `h-9`, a browser toolbar sits at
 * `h-8`, and a control living inside a row, cell, or card sits at `h-6`.
 *
 * `page` and `dialog` currently resolve to the same height. They stay separate names because they
 * are separate laws: a change to the dialog-footer tier must not silently move every page header.
 */
const HEIGHT_COMMIT =
  "h-9 gap-1.5 px-2.5 in-data-[slot=button-group]:rounded-md has-data-[icon=inline-end]:pr-2 has-data-[icon=inline-start]:pl-2"
const HEIGHT_TOOLBAR =
  "h-8 gap-1 rounded-full px-2.5 in-data-[slot=button-group]:rounded-md has-data-[icon=inline-end]:pr-1.5 has-data-[icon=inline-start]:pl-1.5"
const HEIGHT_INLINE =
  "h-6 gap-1 rounded-full px-2 text-xs in-data-[slot=button-group]:rounded-md has-data-[icon=inline-end]:pr-1.5 has-data-[icon=inline-start]:pl-1.5 [&_svg:not([class*='size-'])]:size-3"
const ICON_COMMIT = "size-9"
const ICON_TOOLBAR = "size-8 rounded-full in-data-[slot=button-group]:rounded-md"
const ICON_INLINE =
  "size-6 rounded-full in-data-[slot=button-group]:rounded-md [&_svg:not([class*='size-'])]:size-3"

const buttonVariants = cva(
  "group/button inline-flex shrink-0 items-center justify-center rounded-full border border-transparent bg-clip-padding text-sm font-medium whitespace-nowrap transition-all duration-(--motion-micro) outline-none select-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 disabled:pointer-events-none disabled:opacity-50 aria-invalid:border-destructive aria-invalid:ring-3 aria-invalid:ring-destructive/20 dark:aria-invalid:border-destructive/50 dark:aria-invalid:ring-destructive/40 [&_svg]:pointer-events-none [&_svg]:shrink-0 [&_svg:not([class*='size-'])]:size-4",
  {
    variants: {
      variant: {
        default: "bg-primary text-primary-foreground hover:bg-primary/80",
        brand:
          "bg-brand text-brand-foreground hover:bg-brand-hover aria-expanded:bg-brand-hover aria-expanded:text-brand-foreground data-[state=open]:bg-brand-hover data-[state=open]:text-brand-foreground",
        outline:
          "border-border bg-background shadow-xs hover:bg-muted hover:text-foreground aria-expanded:bg-muted aria-expanded:text-foreground dark:border-input dark:bg-input/30 dark:hover:bg-input/50",
        secondary:
          "bg-secondary text-secondary-foreground hover:bg-secondary/80 aria-expanded:bg-secondary aria-expanded:text-secondary-foreground",
        ghost:
          "hover:bg-muted hover:text-foreground aria-expanded:bg-muted aria-expanded:text-foreground dark:hover:bg-muted/50",
        destructive:
          "bg-destructive/10 text-destructive hover:bg-destructive/20 focus-visible:border-destructive/40 focus-visible:ring-destructive/20 dark:bg-destructive/20 dark:hover:bg-destructive/30 dark:focus-visible:ring-destructive/40",
        link: "text-primary underline-offset-4 hover:underline",
      },
      size: {
        page: HEIGHT_COMMIT,
        dialog: HEIGHT_COMMIT,
        toolbar: HEIGHT_TOOLBAR,
        inline: HEIGHT_INLINE,
        "icon-page": ICON_COMMIT,
        "icon-dialog": ICON_COMMIT,
        "icon-toolbar": ICON_TOOLBAR,
        "icon-inline": ICON_INLINE,
        default: HEIGHT_COMMIT,
        xs: HEIGHT_INLINE,
        sm: HEIGHT_TOOLBAR,
        lg: "h-10 gap-1.5 px-2.5 has-data-[icon=inline-end]:pr-2 has-data-[icon=inline-start]:pl-2",
        icon: ICON_COMMIT,
        "icon-xs": ICON_INLINE,
        "icon-sm": ICON_TOOLBAR,
        "icon-lg": "size-10",
      },
      press: {
        dip: "motion-safe:active:not-aria-[haspopup]:translate-y-px",
        none: "",
      },
    },
    defaultVariants: {
      variant: "default",
      size: "default",
      press: "dip",
    },
  }
)

/**
 * The canonical button. Pill-shaped in every standalone size (#509's two-tier radius language:
 * pill for actions, soft corners reserved for text inputs and joined group members), one height
 * per context, and one press character.
 *
 * `size` is the context height scale. Reach for `page`, `dialog`, `toolbar`, or `inline` — the
 * legacy `default`/`sm`/`xs`/`icon-*` names remain as aliases of the same heights so existing call
 * sites keep working, and `lint/adHocButtons.mjs` burns them down.
 *
 * `menu` renders the D4 chevron: a button that opens a menu always says so, so an action-looking
 * button never surprises with one. `asChild` renders the caller's own element and can only carry a
 * single child, so `menu` has no effect there — such a call site draws its own chevron.
 *
 * `press` is the press-feedback character. It exists so a composed capsule — the split button —
 * can move as one object instead of letting one half dip away from the other; ordinary call sites
 * never set it.
 */
export type ButtonProps = React.ComponentProps<"button"> &
  VariantProps<typeof buttonVariants> & {
    asChild?: boolean
    /** Appends the chevron that marks this button as a menu trigger. Ignored under `asChild`. */
    menu?: boolean
  }

function Button({
  className,
  variant = "default",
  size = "default",
  press = "dip",
  asChild = false,
  menu = false,
  children,
  ...props
}: ButtonProps) {
  const Comp = asChild ? Slot.Root : "button"

  return (
    <Comp
      data-slot="button"
      data-variant={variant}
      data-size={size}
      className={cn(buttonVariants({ variant, size, press, className }))}
      {...props}
    >
      {asChild ? (
        children
      ) : (
        <>
          {children}
          {menu ? (
            <ChevronDownIcon
              data-icon="inline-end"
              aria-hidden="true"
              className="size-3.5 opacity-70 transition-transform duration-(--motion-micro) group-data-[size=inline]/button:size-3 group-data-[size=xs]/button:size-3 group-aria-expanded/button:rotate-180 group-data-[state=open]/button:rotate-180 motion-reduce:transition-none"
            />
          ) : null}
        </>
      )}
    </Comp>
  )
}

export { Button, buttonVariants }
