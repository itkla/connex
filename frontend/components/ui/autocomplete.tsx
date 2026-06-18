"use client"

import * as React from "react"
import { Autocomplete as AutocompletePrimitive } from "@base-ui/react"

import { cn } from "@/lib/utils"
import { InputGroup, InputGroupInput } from "@/components/ui/input-group"

const Autocomplete = AutocompletePrimitive.Root

function AutocompleteInput({
    className,
    children,
    ...props
}: AutocompletePrimitive.Input.Props) {
    return (
        <InputGroup className={cn("w-auto", className)}>
            <AutocompletePrimitive.Input render={<InputGroupInput />} {...props} />
            {children}
        </InputGroup>
    )
}

function AutocompleteContent({
    className,
    side = "bottom",
    sideOffset = 6,
    align = "start",
    alignOffset = 0,
    ...props
}: AutocompletePrimitive.Popup.Props &
    Pick<
        AutocompletePrimitive.Positioner.Props,
        "side" | "align" | "sideOffset" | "alignOffset"
    >) {
    return (
        <AutocompletePrimitive.Portal>
            <AutocompletePrimitive.Positioner
                side={side}
                sideOffset={sideOffset}
                align={align}
                alignOffset={alignOffset}
                className="isolate z-50"
            >
                <AutocompletePrimitive.Popup
                    data-slot="autocomplete-content"
                    className={cn(
                        // pointer-events-auto: the popup portals to <body>, which Radix Dialog marks pointer-events:none while open.
                        "pointer-events-auto group/autocomplete-content relative max-h-(--available-height) w-(--anchor-width) max-w-(--available-width) origin-(--transform-origin) overflow-hidden rounded-md bg-popover text-popover-foreground shadow-md ring-1 ring-foreground/10 duration-100 data-[side=bottom]:slide-in-from-top-2 data-[side=top]:slide-in-from-bottom-2 data-open:animate-in data-open:fade-in-0 data-open:zoom-in-95 data-closed:animate-out data-closed:fade-out-0 data-closed:zoom-out-95",
                        className
                    )}
                    {...props}
                />
            </AutocompletePrimitive.Positioner>
        </AutocompletePrimitive.Portal>
    )
}

function AutocompleteList({ className, ...props }: AutocompletePrimitive.List.Props) {
    return (
        <AutocompletePrimitive.List
            data-slot="autocomplete-list"
            className={cn(
                "no-scrollbar max-h-64 scroll-py-1 overflow-y-auto overscroll-contain p-1 data-empty:p-0",
                className
            )}
            {...props}
        />
    )
}

function AutocompleteItem({ className, ...props }: AutocompletePrimitive.Item.Props) {
    return (
        <AutocompletePrimitive.Item
            data-slot="autocomplete-item"
            className={cn(
                "relative flex w-full cursor-default items-center gap-2 rounded-sm px-2 py-1.5 text-sm outline-hidden select-none data-highlighted:bg-accent data-highlighted:text-accent-foreground data-disabled:pointer-events-none data-disabled:opacity-50",
                className
            )}
            {...props}
        />
    )
}

function AutocompleteEmpty({ className, ...props }: AutocompletePrimitive.Empty.Props) {
    return (
        <AutocompletePrimitive.Empty
            data-slot="autocomplete-empty"
            className={cn(
                "hidden w-full justify-center px-2 py-2 text-center text-sm text-muted-foreground group-data-empty/autocomplete-content:flex",
                className
            )}
            {...props}
        />
    )
}

export {
    Autocomplete,
    AutocompleteInput,
    AutocompleteContent,
    AutocompleteList,
    AutocompleteItem,
    AutocompleteEmpty,
}