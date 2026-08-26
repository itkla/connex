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
 * shipped and what assistive technology already reports for them. Keyboard behaviour is the native
 * one: a roving tabindex means `Tab` reaches the control once, and arrow keys, `Home`, and `End`
 * move focus *and* the selection together. A segment supplied through `render` owns its own focus
 * order, since the control cannot reach inside it.
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
  const segmentRefs = React.useRef(new Map<T, HTMLButtonElement>())

  const selectable = options.filter((option) => !option.disabled)
  /**
   * Roving tabindex: `Tab` reaches the control once and the arrows move within it. When `value`
   * matches no option — a stale preference, a value owned by a `render` slot — the first segment
   * stays tabbable so the control can never become unreachable from the keyboard.
   */
  const focusableValue = options.some((option) => option.value === value)
    ? value
    : selectable[0]?.value

  function select(next: SegmentedControlOption<T> | undefined) {
    if (!next) return
    segmentRefs.current.get(next.value)?.focus()
    if (next.value !== value) onChange(next.value)
  }

  function move(delta: number) {
    if (selectable.length === 0) return
    const current = selectable.findIndex((option) => option.value === value)
    select(selectable[(current + delta + selectable.length) % selectable.length])
  }

  /**
   * Bound to each segment rather than to the track. A `render` slot may host a popup whose content
   * is portalled but still bubbles through the React tree — the analytics custom-range calendar is
   * one — and a track-level handler would read that calendar's arrow keys as a change of preset.
   */
  function handleKeyDown(event: React.KeyboardEvent<HTMLButtonElement>) {
    if (event.key === "ArrowRight" || event.key === "ArrowDown") {
      event.preventDefault()
      move(1)
    } else if (event.key === "ArrowLeft" || event.key === "ArrowUp") {
      event.preventDefault()
      move(-1)
    } else if (event.key === "Home") {
      event.preventDefault()
      select(selectable[0])
    } else if (event.key === "End") {
      event.preventDefault()
      select(selectable[selectable.length - 1])
    }
  }

  return (
    <div
      role="group"
      data-slot="segmented-control"
      aria-label={ariaLabel}
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
            ref={(node) => {
              if (node) segmentRefs.current.set(option.value, node)
              else segmentRefs.current.delete(option.value)
            }}
            type="button"
            onClick={() => onChange(option.value)}
            onKeyDown={handleKeyDown}
            aria-pressed={active}
            aria-label={option.ariaLabel}
            disabled={option.disabled}
            tabIndex={option.value === focusableValue ? 0 : -1}
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
