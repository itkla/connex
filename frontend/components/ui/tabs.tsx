"use client"

import * as React from "react"
import { cva, type VariantProps } from "class-variance-authority"
import { Tabs as TabsPrimitive } from "radix-ui"
import { motion, useReducedMotion } from "motion/react"

import { cn } from "@/lib/utils"

type TabsContextValue = { activeValue: string | undefined; layoutId: string }

const TabsContext = React.createContext<TabsContextValue | null>(null)

function Tabs({
  className,
  orientation = "horizontal",
  value,
  defaultValue,
  onValueChange,
  ...props
}: React.ComponentProps<typeof TabsPrimitive.Root>) {
  const layoutId = React.useId()
  const [uncontrolled, setUncontrolled] = React.useState(defaultValue)
  const activeValue = value ?? uncontrolled

  const handleValueChange = React.useCallback(
    (next: string) => {
      if (value === undefined) setUncontrolled(next)
      onValueChange?.(next)
    },
    [value, onValueChange]
  )

  return (
    <TabsContext.Provider value={{ activeValue, layoutId }}>
      <TabsPrimitive.Root
        data-slot="tabs"
        data-orientation={orientation}
        value={value}
        defaultValue={defaultValue}
        onValueChange={handleValueChange}
        className={cn(
          "group/tabs flex gap-2 data-horizontal:flex-col",
          className
        )}
        {...props}
      />
    </TabsContext.Provider>
  )
}

const tabsListVariants = cva(
  "group/tabs-list inline-flex w-fit items-center justify-center text-muted-foreground group-data-vertical/tabs:h-fit group-data-vertical/tabs:flex-col",
  {
    variants: {
      variant: {
        default: "rounded-full bg-muted p-0.5 ring-1 ring-border/60",
        line: "gap-1 bg-transparent",
      },
    },
    defaultVariants: {
      variant: "default",
    },
  }
)

function TabsList({
  className,
  variant = "default",
  ...props
}: React.ComponentProps<typeof TabsPrimitive.List> &
  VariantProps<typeof tabsListVariants>) {
  return (
    <TabsPrimitive.List
      data-slot="tabs-list"
      data-variant={variant}
      className={cn(tabsListVariants({ variant }), className)}
      {...props}
    />
  )
}

function TabsTrigger({
  className,
  value,
  children,
  ...props
}: React.ComponentProps<typeof TabsPrimitive.Trigger>) {
  const context = React.useContext(TabsContext)
  const reduce = useReducedMotion() ?? false
  const active = context != null && context.activeValue === value

  return (
    <TabsPrimitive.Trigger
      data-slot="tabs-trigger"
      value={value}
      className={cn(
        "relative inline-flex h-8 flex-1 items-center justify-center gap-1.5 whitespace-nowrap rounded-full px-2.5 text-xs font-medium text-muted-foreground outline-none transition-[color,transform] duration-(--motion-micro) hover:text-foreground active:scale-[0.97] motion-reduce:active:scale-100 data-[state=active]:text-foreground disabled:pointer-events-none disabled:opacity-50 focus-visible:ring-2 focus-visible:ring-brand/40",
        "group-data-vertical/tabs:w-full group-data-vertical/tabs:justify-start",
        "group-data-[variant=line]/tabs-list:h-9 group-data-[variant=line]/tabs-list:rounded-none group-data-[variant=line]/tabs-list:text-sm",
        "[&_svg]:pointer-events-none [&_svg]:shrink-0 [&_svg:not([class*='size-'])]:size-3.5",
        className
      )}
      {...props}
    >
      {active && context != null && (
        <motion.span
          layoutId={context.layoutId}
          aria-hidden
          className={cn(
            "absolute inset-0 rounded-full bg-background shadow-sm",
            "group-data-[variant=line]/tabs-list:inset-x-1 group-data-[variant=line]/tabs-list:inset-y-auto group-data-[variant=line]/tabs-list:bottom-0 group-data-[variant=line]/tabs-list:h-0.5 group-data-[variant=line]/tabs-list:rounded-full group-data-[variant=line]/tabs-list:bg-foreground group-data-[variant=line]/tabs-list:shadow-none"
          )}
          transition={
            reduce
              ? { duration: 0 }
              : { type: "spring", stiffness: 520, damping: 42 }
          }
        />
      )}
      <span className="relative z-10 inline-flex items-center gap-1.5">
        {children}
      </span>
    </TabsPrimitive.Trigger>
  )
}

function TabsContent({
  className,
  ...props
}: React.ComponentProps<typeof TabsPrimitive.Content>) {
  return (
    <TabsPrimitive.Content
      data-slot="tabs-content"
      className={cn("flex-1 text-sm outline-none", className)}
      {...props}
    />
  )
}

export { Tabs, TabsList, TabsTrigger, TabsContent, tabsListVariants }
