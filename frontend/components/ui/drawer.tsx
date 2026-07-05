"use client"

import * as React from "react"
import { Drawer as DrawerPrimitive } from "@base-ui/react/drawer"
import { XIcon } from "lucide-react"

import { cn } from "@/lib/utils"
import { Button } from "@/components/ui/button"

function Drawer({
  swipeDirection = "left",
  ...props
}: React.ComponentProps<typeof DrawerPrimitive.Root>) {
  return <DrawerPrimitive.Root data-slot="drawer" swipeDirection={swipeDirection} {...props} />
}

function DrawerTrigger({
  ...props
}: React.ComponentProps<typeof DrawerPrimitive.Trigger>) {
  return <DrawerPrimitive.Trigger data-slot="drawer-trigger" {...props} />
}

function DrawerClose({
  ...props
}: React.ComponentProps<typeof DrawerPrimitive.Close>) {
  return <DrawerPrimitive.Close data-slot="drawer-close" {...props} />
}

function DrawerPortal({
  ...props
}: React.ComponentProps<typeof DrawerPrimitive.Portal>) {
  return <DrawerPrimitive.Portal data-slot="drawer-portal" {...props} />
}

function DrawerOverlay({
  className,
  ...props
}: React.ComponentProps<typeof DrawerPrimitive.Backdrop>) {
  return (
    <DrawerPrimitive.Backdrop
      data-slot="drawer-overlay"
      className={cn(
        "fixed inset-0 z-50 bg-black/10 bg-clip-padding transition-opacity duration-300 ease-[cubic-bezier(0.32,0.72,0,1)] select-none dark:bg-black/50 supports-backdrop-filter:backdrop-blur-xs data-swiping:duration-0 data-starting-style:opacity-0 data-ending-style:pointer-events-none data-ending-style:opacity-0 supports-[-webkit-touch-callout:none]:absolute",
        className
      )}
      {...props}
    />
  )
}

function DrawerContent({
  className,
  children,
  showCloseButton = true,
  ...props
}: React.ComponentProps<typeof DrawerPrimitive.Popup> & {
  showCloseButton?: boolean
}) {
  return (
    <DrawerPortal>
      <DrawerOverlay />
      <DrawerPrimitive.Viewport className="pointer-events-none fixed inset-0 z-50 flex items-stretch justify-start p-2 sm:p-3">
        <DrawerPrimitive.Popup
          data-slot="drawer-content"
          className={cn(
            "group/drawer-popup pointer-events-auto relative flex h-full w-3/4 flex-col overflow-hidden rounded-2xl bg-popover bg-clip-padding text-sm text-popover-foreground shadow-2xl ring-1 ring-foreground/10 outline-none sm:max-w-sm",
            "[transform:translateX(var(--drawer-swipe-movement-x,0px))] transition-transform duration-300 ease-[cubic-bezier(0.32,0.72,0,1)] data-swiping:transition-none data-starting-style:[transform:translateX(calc(-100%-1.5rem))] data-ending-style:[transform:translateX(calc(-100%-1.5rem))]",
            className
          )}
          {...props}
        >
          <DrawerPrimitive.Content
            data-slot="drawer-inner"
            className="flex min-h-0 flex-1 flex-col overflow-hidden rounded-[inherit] select-text group-data-swiping/drawer-popup:select-none"
          >
            {children}
          </DrawerPrimitive.Content>
          {showCloseButton && (
            <DrawerPrimitive.Close
              data-slot="drawer-close-button"
              render={<Button variant="ghost" size="icon-sm" className="absolute top-4 right-4 z-10" />}
            >
              <XIcon />
              <span className="sr-only">Close</span>
            </DrawerPrimitive.Close>
          )}
        </DrawerPrimitive.Popup>
      </DrawerPrimitive.Viewport>
    </DrawerPortal>
  )
}

function DrawerHeader({ className, ...props }: React.ComponentProps<"div">) {
  return (
    <div
      data-slot="drawer-header"
      className={cn("flex shrink-0 flex-col gap-1.5 p-4", className)}
      {...props}
    />
  )
}

function DrawerFooter({ className, ...props }: React.ComponentProps<"div">) {
  return (
    <div
      data-slot="drawer-footer"
      className={cn("mt-auto flex shrink-0 flex-col gap-2 p-4", className)}
      {...props}
    />
  )
}

function DrawerTitle({
  className,
  ...props
}: React.ComponentProps<typeof DrawerPrimitive.Title>) {
  return (
    <DrawerPrimitive.Title
      data-slot="drawer-title"
      className={cn("font-heading font-medium text-foreground", className)}
      {...props}
    />
  )
}

function DrawerDescription({
  className,
  ...props
}: React.ComponentProps<typeof DrawerPrimitive.Description>) {
  return (
    <DrawerPrimitive.Description
      data-slot="drawer-description"
      className={cn("text-sm text-muted-foreground", className)}
      {...props}
    />
  )
}

export {
  Drawer,
  DrawerPortal,
  DrawerOverlay,
  DrawerTrigger,
  DrawerClose,
  DrawerContent,
  DrawerHeader,
  DrawerFooter,
  DrawerTitle,
  DrawerDescription,
}
