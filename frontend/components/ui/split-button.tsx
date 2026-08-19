"use client"

import * as React from "react"
import { cva } from "class-variance-authority"
import { ChevronDownIcon } from "@heroicons/react/24/solid"

import { cn } from "@/lib/utils"
import { Button } from "@/components/ui/button"
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu"
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip"

/**
 * The capsule. The two halves are separate buttons because they are separate actions, but the
 * object the user sees is one pill: only the outer caps are rounded, the halves meet on a straight
 * edge, and the press dip lives on the capsule rather than on either half — press the verb or the
 * chevron and the whole shape moves, which is what stops a split button reading as two buttons
 * that happen to touch.
 */
const CAPSULE =
  "group/split-button inline-flex w-fit items-stretch rounded-full transition-transform duration-(--motion-micro) motion-safe:has-[button:active]:translate-y-px motion-reduce:transition-none *:focus-visible:relative *:focus-visible:z-10"

/**
 * The divider: an inset hairline in the capsule's own ink at low alpha — not a border token, and
 * never full-bleed. A seam that runs cap to cap reads as two abutted objects; a score line that
 * stops short of both reads as one object divided, which is the D4 contract.
 *
 * It is the capsule's only vertical line. Every button variant carries `border border-transparent`
 * under `bg-clip-padding`, so two halves set side by side meet as three lines — the action half's
 * right border, this hairline, the chevron half's left border. On `outline` all three are inked; on
 * the filled variants the outer two read as 1px transparent gaps either side of the seam. The
 * halves therefore drop their inner border (`border-r-0` / `border-l-0`) and let the hairline be
 * the only division, which is what keeps the law true for every variant the type allows.
 */
const splitButtonDividerVariants = cva("pointer-events-none w-px self-stretch", {
  variants: {
    variant: {
      default: "bg-primary-foreground/25",
      brand: "bg-brand-foreground/25",
      outline: "bg-border",
      secondary: "bg-secondary-foreground/25",
    },
    size: {
      page: "my-1.5",
      toolbar: "my-1.5",
      inline: "my-1",
    },
  },
  defaultVariants: { variant: "brand", size: "page" },
})

/** The context height tiers a split button supports, matching the {@link Button} size scale. */
export type SplitButtonSize = "page" | "toolbar" | "inline"

/** The emphasis tiers a split button supports. A split button commits; it is never a link. */
export type SplitButtonVariant = "default" | "brand" | "outline" | "secondary"

/** The chevron half's horizontal padding per context — narrower than a square icon button. */
const TRIGGER_PADDING: Record<SplitButtonSize, string> = {
  page: "px-2",
  toolbar: "px-2",
  inline: "px-1.5",
}

export type SplitButtonProps = {
  /** Visible label of the primary verb. */
  label: React.ReactNode
  /** The primary verb's `type`. Defaults to `button`; the chevron half is always a `button`. */
  type?: "button" | "submit" | "reset"
  /** Leading icon of the primary verb. */
  icon?: React.ReactNode
  onClick: () => void
  variant?: SplitButtonVariant
  size?: SplitButtonSize
  /** Accessible name of the primary verb when the visible label needs expanding. */
  actionAriaLabel?: string
  actionDisabled?: boolean
  /** Accessible name and tooltip of the icon-only chevron half. */
  menuLabel: string
  menuAlign?: "start" | "center" | "end"
  menuClassName?: string
  menuDisabled?: boolean
  className?: string
  /** The menu behind the chevron: `DropdownMenuItem`s and separators. */
  children: React.ReactNode
}

/**
 * The D4 split button: one capsule holding a primary verb plus a chevron menu behind a divider
 * (`docs/PRODUCT.md` §6 "Buttons"). Reach for it where a surface has one obvious action and a small
 * set of adjacent ones — "New contact", with import and export behind the chevron — instead of
 * joining a button and a menu trigger by hand.
 *
 * The chevron half is icon-only, so `menuLabel` is both its accessible name and its tooltip. They
 * are the same string by construction, which is what WCAG's label-in-name asks for.
 */
export function SplitButton({
  label,
  type = "button",
  icon,
  onClick,
  variant = "brand",
  size = "page",
  actionAriaLabel,
  actionDisabled,
  menuLabel,
  menuAlign = "end",
  menuClassName,
  menuDisabled,
  className,
  children,
}: SplitButtonProps) {
  return (
    <div data-slot="split-button" className={cn(CAPSULE, className)}>
      <Button
        type={type}
        variant={variant}
        size={size}
        press="none"
        className="rounded-r-none border-r-0"
        aria-label={actionAriaLabel}
        disabled={actionDisabled}
        onClick={onClick}
      >
        {icon}
        {label}
      </Button>
      <span aria-hidden="true" className={splitButtonDividerVariants({ variant, size })} />
      <DropdownMenu>
        <Tooltip>
          <DropdownMenuTrigger asChild>
            <TooltipTrigger asChild>
              <Button
                type="button"
                variant={variant}
                size={size}
                press="none"
                className={cn("rounded-l-none border-l-0", TRIGGER_PADDING[size])}
                aria-label={menuLabel}
                disabled={menuDisabled}
              >
                <ChevronDownIcon
                  aria-hidden="true"
                  className="size-3.5 transition-transform duration-(--motion-micro) group-aria-expanded/button:rotate-180 motion-reduce:transition-none"
                />
              </Button>
            </TooltipTrigger>
          </DropdownMenuTrigger>
          <TooltipContent>{menuLabel}</TooltipContent>
        </Tooltip>
        <DropdownMenuContent align={menuAlign} className={cn("w-fit min-w-56", menuClassName)}>
          {children}
        </DropdownMenuContent>
      </DropdownMenu>
    </div>
  )
}
