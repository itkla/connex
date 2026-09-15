"use client"

import * as React from "react"

import { Button, type ButtonProps } from "@/components/ui/button"
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip"

export type IconButtonProps = Omit<
  ButtonProps,
  "menu" | "asChild" | "aria-label" | "children"
> & {
  /**
   * What the button does, in the user's words. It is the accessible name *and* the tooltip, so the
   * two can never drift — which is what WCAG's label-in-name asks for.
   */
  label: string
  children: React.ReactNode
  tooltipSide?: React.ComponentProps<typeof TooltipContent>["side"]
}

/**
 * The D4 icon button: circular and always tooltipped (`docs/PRODUCT.md` §6 "Buttons"). An icon
 * alone is a guess until the user hovers it, so the label is mandatory rather than optional, and
 * the component — not the call site — is what guarantees the tooltip exists.
 *
 * It forwards every other prop to the underlying {@link Button}, so it composes as the child of a
 * `DropdownMenuTrigger asChild` / `PopoverTrigger asChild` exactly as a bare `Button` does.
 */
export function IconButton({
  label,
  children,
  size = "icon-toolbar",
  tooltipSide,
  ...props
}: IconButtonProps) {
  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <Button aria-label={label} size={size} {...props}>
          {children}
        </Button>
      </TooltipTrigger>
      <TooltipContent side={tooltipSide}>{label}</TooltipContent>
    </Tooltip>
  )
}
