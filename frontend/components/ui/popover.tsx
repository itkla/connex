"use client"

import { Popover as PopoverPrimitive } from "@base-ui/react"

import { cn } from "@/lib/utils"

const Popover = PopoverPrimitive.Root

function PopoverTrigger(props: PopoverPrimitive.Trigger.Props) {
  return <PopoverPrimitive.Trigger data-slot="popover-trigger" {...props} />
}

/**
 * The anchored popup. `anchor` is forwarded so a popover can point at something other than its own
 * trigger — a clicked calendar event, a cell, a virtual rect — which is what an anchored peek needs
 * when the thing it describes was clicked rather than pressed as a trigger.
 */
function PopoverContent({
  className,
  sideOffset = 8,
  side,
  align,
  anchor,
  ...props
}: PopoverPrimitive.Popup.Props &
  Pick<PopoverPrimitive.Positioner.Props, "side" | "align" | "sideOffset" | "anchor">) {
  return (
    <PopoverPrimitive.Portal>
      <PopoverPrimitive.Positioner
        className="z-[100] data-anchor-hidden:pointer-events-none data-anchor-hidden:opacity-0"
        sideOffset={sideOffset}
        side={side}
        align={align}
        anchor={anchor}
      >
        <PopoverPrimitive.Popup
          data-slot="popover-content"
          className={cn(
            "z-50 w-80 max-w-[calc(100vw-2rem)] origin-(--transform-origin) rounded-xl bg-popover p-4 text-popover-foreground shadow-md ring-1 ring-foreground/10 duration-(--motion-micro) data-open:ease-hand data-open:animate-in data-open:fade-in-0 data-open:zoom-in-95 data-closed:animate-out data-closed:fade-out-0 data-closed:zoom-out-95 motion-reduce:animate-none!",
            className,
          )}
          {...props}
        />
      </PopoverPrimitive.Positioner>
    </PopoverPrimitive.Portal>
  )
}

function PopoverTitle(props: PopoverPrimitive.Title.Props) {
  return <PopoverPrimitive.Title data-slot="popover-title" {...props} />
}

function PopoverDescription(props: PopoverPrimitive.Description.Props) {
  return <PopoverPrimitive.Description data-slot="popover-description" {...props} />
}

function PopoverClose(props: PopoverPrimitive.Close.Props) {
  return <PopoverPrimitive.Close data-slot="popover-close" {...props} />
}

export { Popover, PopoverTrigger, PopoverContent, PopoverTitle, PopoverDescription, PopoverClose }
