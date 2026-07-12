"use client"

import * as React from "react"

import { useIsMobile } from "@/app/hooks/useIsMobile"
import { cn } from "@/lib/utils"
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogTitle,
  DialogTrigger,
} from "@/components/ui/dialog"
import {
  Drawer,
  DrawerClose,
  DrawerContent,
  DrawerDescription,
  DrawerTitle,
  DrawerTrigger,
} from "@/components/ui/drawer"

const ResponsiveDialogContext = React.createContext<boolean | null>(null)

/**
 * Reads the platform the surrounding {@link ResponsiveDialog} committed to. Every part derives
 * its primitive from this single value rather than reading the viewport independently, so the
 * root and its parts can never disagree mid-resize (which would render a Radix part inside a
 * Base UI drawer, or vice versa).
 */
function useResponsiveIsMobile(): boolean {
  const context = React.useContext(ResponsiveDialogContext)

  if (context === null) {
    throw new Error(
      "ResponsiveDialog parts must be used within a <ResponsiveDialog>."
    )
  }

  return context
}

type ResponsiveDialogProps = {
  open?: boolean
  onOpenChange?: (open: boolean) => void
  children?: React.ReactNode
}

/**
 * A dialog that renders as a centered {@link Dialog} on desktop and a bottom {@link Drawer}
 * on mobile (below the `md` breakpoint). The desktop branch is a straight pass-through to
 * `Dialog`, so existing dialog markup keeps its exact look and behavior; the mobile branch
 * swaps in a swipe-dismissable bottom sheet. Compose it with the matching `ResponsiveDialog*`
 * parts so the correct primitive context is provided on each platform.
 */
function ResponsiveDialog({ open, onOpenChange, children }: ResponsiveDialogProps) {
  const isMobile = useIsMobile()

  return (
    <ResponsiveDialogContext.Provider value={isMobile}>
      {isMobile ? (
        <Drawer open={open} onOpenChange={onOpenChange} swipeDirection="down">
          {children}
        </Drawer>
      ) : (
        <Dialog open={open} onOpenChange={onOpenChange}>
          {children}
        </Dialog>
      )}
    </ResponsiveDialogContext.Provider>
  )
}

type ResponsiveDialogTriggerProps = {
  asChild?: boolean
  children?: React.ReactNode
}

/**
 * Trigger that opens the dialog on desktop or the drawer on mobile. Mirrors Radix's `asChild`
 * on desktop and Base UI's `render` on mobile, so a single
 * `<ResponsiveDialogTrigger asChild><button/></ResponsiveDialogTrigger>` works on both platforms.
 */
function ResponsiveDialogTrigger({
  asChild,
  children,
}: ResponsiveDialogTriggerProps) {
  const isMobile = useResponsiveIsMobile()

  if (isMobile) {
    if (asChild && React.isValidElement(children)) {
      return <DrawerTrigger render={children} />
    }
    return <DrawerTrigger>{children}</DrawerTrigger>
  }

  return <DialogTrigger asChild={asChild}>{children}</DialogTrigger>
}

type ResponsiveDialogContentProps = {
  className?: string
  children?: React.ReactNode
  showCloseButton?: boolean
  scrollable?: boolean
}

/**
 * The surface that holds the dialog body. On mobile it renders a {@link DrawerContent} bottom
 * sheet whose body scrolls when it outgrows the viewport (`scrollable`, default `true`); pass
 * `scrollable={false}` when the content manages its own height and scrolling. On desktop it
 * renders {@link DialogContent} unchanged.
 */
function ResponsiveDialogContent({
  className,
  children,
  showCloseButton,
  scrollable = true,
}: ResponsiveDialogContentProps) {
  const isMobile = useResponsiveIsMobile()

  if (isMobile) {
    return (
      <DrawerContent className={className} showCloseButton={showCloseButton}>
        {scrollable ? (
          <div className="flex min-h-0 flex-1 flex-col overflow-y-auto">
            {children}
          </div>
        ) : (
          children
        )}
      </DrawerContent>
    )
  }

  return (
    <DialogContent className={className} showCloseButton={showCloseButton}>
      {children}
    </DialogContent>
  )
}

/** Header container, styled identically on both platforms. */
function ResponsiveDialogHeader({
  className,
  ...props
}: React.ComponentProps<"div">) {
  return (
    <div
      data-slot="responsive-dialog-header"
      className={cn("flex flex-col gap-2", className)}
      {...props}
    />
  )
}

/** Footer container, styled identically on both platforms. */
function ResponsiveDialogFooter({
  className,
  ...props
}: React.ComponentProps<"div">) {
  return (
    <div
      data-slot="responsive-dialog-footer"
      className={cn(
        "flex flex-col-reverse gap-2 sm:flex-row sm:justify-end",
        className
      )}
      {...props}
    />
  )
}

type ResponsiveDialogTextProps = {
  className?: string
  children?: React.ReactNode
}

/** Accessible title, wired to the active primitive so labelling works on both platforms. */
function ResponsiveDialogTitle({
  className,
  children,
}: ResponsiveDialogTextProps) {
  const isMobile = useResponsiveIsMobile()
  const Title = isMobile ? DrawerTitle : DialogTitle
  return <Title className={className}>{children}</Title>
}

/** Accessible description, wired to the active primitive. */
function ResponsiveDialogDescription({
  className,
  children,
}: ResponsiveDialogTextProps) {
  const isMobile = useResponsiveIsMobile()
  const Description = isMobile ? DrawerDescription : DialogDescription
  return <Description className={className}>{children}</Description>
}

type ResponsiveDialogCloseProps = {
  asChild?: boolean
  children?: React.ReactNode
}

/**
 * Closes the dialog/drawer. Mirrors Radix's `asChild` on desktop and Base UI's `render` on
 * mobile, so a single `<ResponsiveDialogClose asChild><Button/></ResponsiveDialogClose>` closes
 * the surface on both platforms.
 */
function ResponsiveDialogClose({ asChild, children }: ResponsiveDialogCloseProps) {
  const isMobile = useResponsiveIsMobile()

  if (isMobile) {
    if (asChild && React.isValidElement(children)) {
      return <DrawerClose render={children} />
    }
    return <DrawerClose>{children}</DrawerClose>
  }

  return <DialogClose asChild={asChild}>{children}</DialogClose>
}

export {
  ResponsiveDialog,
  ResponsiveDialogTrigger,
  ResponsiveDialogContent,
  ResponsiveDialogHeader,
  ResponsiveDialogFooter,
  ResponsiveDialogTitle,
  ResponsiveDialogDescription,
  ResponsiveDialogClose,
}
