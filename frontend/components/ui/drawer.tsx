"use client"

import * as React from "react"
import { Drawer as DrawerPrimitive } from "@base-ui/react/drawer"
import { XIcon } from "lucide-react"

import { cn } from "@/lib/utils"
import { Button } from "@/components/ui/button"

type SwipeDirection = NonNullable<DrawerPrimitive.Root.Props["swipeDirection"]>

type DrawerContextValue = {
  modal: DrawerPrimitive.Root.Props["modal"]
  showSwipeHandle: boolean
  swipeDirection: SwipeDirection
  hasSnapPoints: boolean
}

const DrawerContext = React.createContext<DrawerContextValue | null>(null)

function useDrawerContext() {
  const context = React.useContext(DrawerContext)

  if (!context) {
    throw new Error("Drawer parts must be used within a <Drawer>.")
  }

  return context
}

function Drawer({
  modal = true,
  showSwipeHandle = false,
  snapPoints,
  swipeDirection = "down",
  ...props
}: DrawerPrimitive.Root.Props & {
  showSwipeHandle?: boolean
}) {
  const hasSnapPoints = snapPoints != null && snapPoints.length > 0
  const contextValue = React.useMemo<DrawerContextValue>(
    () => ({ modal, showSwipeHandle, swipeDirection, hasSnapPoints }),
    [modal, showSwipeHandle, swipeDirection, hasSnapPoints]
  )

  return (
    <DrawerContext.Provider value={contextValue}>
      <DrawerPrimitive.Root
        data-slot="drawer"
        modal={modal}
        snapPoints={snapPoints}
        swipeDirection={swipeDirection}
        {...props}
      />
    </DrawerContext.Provider>
  )
}

function DrawerTrigger({
  ...props
}: DrawerPrimitive.Trigger.Props) {
  return <DrawerPrimitive.Trigger data-slot="drawer-trigger" {...props} />
}

function DrawerClose({ ...props }: DrawerPrimitive.Close.Props) {
  return <DrawerPrimitive.Close data-slot="drawer-close" {...props} />
}

function DrawerPortal({ ...props }: DrawerPrimitive.Portal.Props) {
  return <DrawerPrimitive.Portal data-slot="drawer-portal" {...props} />
}

function DrawerOverlay({
  className,
  ...props
}: DrawerPrimitive.Backdrop.Props) {
  return (
    <DrawerPrimitive.Backdrop
      data-slot="drawer-overlay"
      className={cn(
        "fixed inset-0 z-50 bg-black/10 bg-clip-padding opacity-[max(0,calc(1-var(--drawer-swipe-progress,0)))] transition-opacity duration-300 ease-[cubic-bezier(0.32,0.72,0,1)] select-none dark:bg-black/50 supports-backdrop-filter:backdrop-blur-xs motion-reduce:transition-none data-swiping:duration-0 data-starting-style:opacity-0 data-ending-style:pointer-events-none data-ending-style:opacity-0 supports-[-webkit-touch-callout:none]:absolute",
        className
      )}
      {...props}
    />
  )
}

function DrawerSwipeHandle({
  className,
  ...props
}: React.ComponentProps<"div">) {
  return (
    <div
      data-slot="drawer-swipe-handle"
      aria-hidden="true"
      className={cn(
        "mx-auto mt-2 h-1.5 w-12 shrink-0 cursor-grab rounded-full bg-border transition-opacity duration-200 group-data-[swipe-axis=x]/drawer-popup:hidden group-data-[swipe-direction=up]/drawer-popup:order-last active:cursor-grabbing",
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
}: DrawerPrimitive.Popup.Props & {
  showCloseButton?: boolean
}) {
  const { modal, showSwipeHandle, swipeDirection, hasSnapPoints } =
    useDrawerContext()
  const swipeAxis =
    swipeDirection === "down" || swipeDirection === "up" ? "y" : "x"

  const viewportAlign =
    swipeDirection === "right"
      ? "items-stretch justify-end p-4 sm:p-6"
      : swipeDirection === "left"
        ? "items-stretch justify-start p-4 sm:p-6"
        : swipeDirection === "up"
          ? "items-start justify-center"
          : "items-end justify-center"

  const layout =
    swipeAxis === "y"
      ? cn(
          "w-full",
          hasSnapPoints ? "h-dvh" : "h-auto max-h-[calc(100dvh-4rem)]",
          "[transform:translateY(calc(var(--drawer-snap-point-offset,0px)+var(--drawer-swipe-movement-y,0px)))]",
          swipeDirection === "down"
            ? "rounded-t-2xl data-starting-style:[transform:translateY(100%)] data-ending-style:[transform:translateY(100%)]"
            : "rounded-b-2xl data-starting-style:[transform:translateY(-100%)] data-ending-style:[transform:translateY(-100%)]"
        )
      : cn(
          "h-full w-3/4 rounded-2xl sm:max-w-sm",
          "[transform:translateX(var(--drawer-swipe-movement-x,0px))]",
          swipeDirection === "right"
            ? "data-starting-style:[transform:translateX(calc(100%+2rem))] data-ending-style:[transform:translateX(calc(100%+2rem))]"
            : "data-starting-style:[transform:translateX(calc(-100%-2rem))] data-ending-style:[transform:translateX(calc(-100%-2rem))]"
        )

  return (
    <DrawerPortal>
      {modal === true && <DrawerOverlay />}
      <DrawerPrimitive.Viewport
        data-slot="drawer-viewport"
        className={cn(
          "pointer-events-none fixed inset-0 z-50 flex select-none",
          viewportAlign
        )}
      >
        <DrawerPrimitive.Popup
          data-slot="drawer-content"
          data-swipe-axis={swipeAxis}
          data-snap-points={hasSnapPoints ? "" : undefined}
          className={cn(
            "group/drawer-popup pointer-events-auto relative flex min-h-0 flex-col overflow-hidden bg-popover bg-clip-padding text-sm text-popover-foreground shadow-2xl ring-1 ring-foreground/10 outline-none",
            "transition-transform duration-300 ease-[cubic-bezier(0.32,0.72,0,1)] will-change-transform motion-reduce:transition-none data-swiping:transition-none",
            layout,
            className
          )}
          {...props}
        >
          {showSwipeHandle && <DrawerSwipeHandle />}
          <DrawerPrimitive.Content
            data-slot="drawer-inner"
            className="flex min-h-0 flex-1 flex-col overflow-hidden rounded-[inherit] select-text group-data-swiping/drawer-popup:select-none"
          >
            {children}
          </DrawerPrimitive.Content>
          {showCloseButton && (
            <DrawerPrimitive.Close
              data-slot="drawer-close-button"
              render={
                <Button
                  variant="ghost"
                  size="icon-sm"
                  className="absolute top-4 right-4 z-10"
                />
              }
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
}: DrawerPrimitive.Title.Props) {
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
}: DrawerPrimitive.Description.Props) {
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
  DrawerSwipeHandle,
  DrawerTrigger,
  DrawerClose,
  DrawerContent,
  DrawerHeader,
  DrawerFooter,
  DrawerTitle,
  DrawerDescription,
}
