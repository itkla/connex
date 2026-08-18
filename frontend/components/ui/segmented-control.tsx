"use client"

import * as React from "react"
import { cva, type VariantProps } from "class-variance-authority"
import { motion, useReducedMotion } from "motion/react"

import { cn } from "@/lib/utils"
import { instant, springSnappy } from "@/app/lib/motion"

/**
 * The track. Deliberately the same shape language as `TabsList` — a pill of `bg-muted` on a
 * hairline ring — because a segmented control and a tab strip are the same idea at two scales, and
 * the product already reads the inset-track-with-a-travelling-thumb as "pick one of these".
 */
const segmentedControlVariants = cva(
  "inline-flex items-center rounded-full bg-muted p-0.5 ring-1 ring-border/60",
  {
    variants: {
      size: {
        toolbar: "",
        inline: "",
      },
    },
    defaultVariants: { size: "toolbar" },
  }
)

/**
 * A segment. It carries no background of its own: the selected state is the thumb sliding
 * underneath, so the control animates one element instead of cross-fading two, and an interrupted
 * change reverses from wherever the thumb currently is.
 */
const segmentVariants = cva(
  "relative inline-flex items-center justify-center gap-1.5 whitespace-nowrap rounded-full font-medium outline-none transition-[color,transform] duration-(--motion-micro) hover:text-foreground focus-visible:ring-2 focus-visible:ring-brand/40 disabled:pointer-events-none disabled:opacity-50 motion-safe:active:scale-[0.97] [&_svg]:pointer-events-none [&_svg]:shrink-0 [&_svg:not([class*='size-'])]:size-3.5",
  {
    variants: {
      size: {
        toolbar: "h-8 text-xs",
        inline: "h-6 text-xs",
      },
      labelled: {
        true: "",
        false: "",
      },
    },
    compoundVariants: [
      { size: "toolbar", labelled: true, class: "px-2.5" },
      { size: "toolbar", labelled: false, class: "w-8" },
      { size: "inline", labelled: true, class: "px-2" },
      { size: "inline", labelled: false, class: "w-6" },
    ],
    defaultVariants: { size: "toolbar", labelled: true },
  }
)

/** One choice in a {@link SegmentedControl}. */
export type SegmentedControlOption<T extends string> = {
  value: T
  label?: React.ReactNode
  icon?: React.ReactNode
  /** Accessible name — required when the segment renders icon-only. */
  ariaLabel?: string
  disabled?: boolean
  /**
   * Escape hatch for a segment that is itself a trigger — the analytics custom-range popover is
   * the only case. The rendered node receives the segment's resolved classes and the shared thumb
   * so it still travels with the rest of the control.
   */
  render?: (context: {
    active: boolean
    className: string
    thumb: React.ReactNode
  }) => React.ReactNode
}

export type SegmentedControlProps<T extends string> = VariantProps<
  typeof segmentedControlVariants
> & {
  value: T
  onChange: (next: T) => void
  options: readonly SegmentedControlOption<T>[]
  /** Accessible name of the group — what is being switched, e.g. "View". */
  ariaLabel?: string
  className?: string
  /**
   * Shares one travelling thumb across two controls. Leave unset; each control gets its own
   * identity from `useId`.
   */
  layoutId?: string
}

/**
 * The D4 segmented control: the one way to switch a mode or a view (`docs/PRODUCT.md` §6
 * "Buttons"). A row of toggle buttons says "these are independent"; a segmented control says
 * "exactly one of these is true", and that is the only claim a view switcher, a density switch, or
 * an active/archived scope should be making.
 *
 * Segments stay toggle buttons (`aria-pressed`) rather than radios, which is what the surfaces
 * shipped and what assistive technology already reports for them; arrow keys, `Home`, and `End`
 * move the selection so the control answers the keyboard the way a native one does.
 */
export function SegmentedControl<T extends string>({
  value,
  onChange,
  options,
  ariaLabel,
  className,
  size = "toolbar",
  layoutId,
}: SegmentedControlProps<T>) {
  const generatedLayoutId = React.useId()
  const thumbLayoutId = layoutId ?? generatedLayoutId
  const reduce = useReducedMotion() ?? false

  const selectable = options.filter((option) => !option.disabled)

  function move(delta: number) {
    if (selectable.length === 0) return
    const current = selectable.findIndex((option) => option.value === value)
    const next = selectable[(current + delta + selectable.length) % selectable.length]
    if (next && next.value !== value) onChange(next.value)
  }

  function handleKeyDown(event: React.KeyboardEvent<HTMLDivElement>) {
    if (event.key === "ArrowRight" || event.key === "ArrowDown") {
      event.preventDefault()
      move(1)
    } else if (event.key === "ArrowLeft" || event.key === "ArrowUp") {
      event.preventDefault()
      move(-1)
    } else if (event.key === "Home") {
      event.preventDefault()
      if (selectable[0]) onChange(selectable[0].value)
    } else if (event.key === "End") {
      event.preventDefault()
      const last = selectable[selectable.length - 1]
      if (last) onChange(last.value)
    }
  }

  return (
    <div
      role="group"
      data-slot="segmented-control"
      aria-label={ariaLabel}
      onKeyDown={handleKeyDown}
      className={cn(segmentedControlVariants({ size }), className)}
    >
      {options.map((option) => {
        const active = option.value === value
        const segmentClassName = cn(
          segmentVariants({ size, labelled: option.label != null }),
          active ? "text-foreground" : "text-muted-foreground"
        )
        const thumb = active ? (
          <motion.span
            layoutId={thumbLayoutId}
            aria-hidden="true"
            className="absolute inset-0 rounded-full bg-background shadow-sm"
            transition={reduce ? instant : springSnappy}
          />
        ) : null

        if (option.render) {
          return (
            <React.Fragment key={option.value}>
              {option.render({ active, className: segmentClassName, thumb })}
            </React.Fragment>
          )
        }

        return (
          <button
            key={option.value}
            type="button"
            onClick={() => onChange(option.value)}
            aria-pressed={active}
            aria-label={option.ariaLabel}
            disabled={option.disabled}
            className={segmentClassName}
          >
            {thumb}
            <span className="relative z-10 inline-flex items-center gap-1.5">
              {option.icon}
              {option.label}
            </span>
          </button>
        )
      })}
    </div>
  )
}
