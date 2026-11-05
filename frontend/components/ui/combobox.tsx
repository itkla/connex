"use client"

import * as React from "react"
import { Combobox as ComboboxPrimitive } from "@base-ui/react"
import { useTranslations } from "next-intl"

import { cn } from "@/lib/utils"
import { Button } from "@/components/ui/button"
import {
  InputGroup,
  InputGroupAddon,
  InputGroupButton,
  InputGroupInput,
} from "@/components/ui/input-group"
import { ChevronDownIcon, XIcon, CheckIcon } from "lucide-react"

type PickerInputState = {
  pendingInput: React.RefObject<boolean>
  composing: React.RefObject<boolean>
  registerInput: (element: HTMLInputElement | null) => void
  itemsPending: boolean
}

const PickerInputContext = React.createContext<PickerInputState | null>(null)

function pickerLabel<Value>(value: Value, toString?: (value: Value) => string): string {
  if (toString && value != null) return toString(value)
  if (value !== null && typeof value === "object") {
    if ("label" in value) return String(value.label ?? "")
    if ("value" in value) return String(value.value ?? "")
  }
  return String(value ?? "")
}

/** Base UI's boolean highlight mode reacts to input; settled server results reuse its public keyboard path. */
function highlightSettledResult(element: HTMLInputElement) {
  const listId = element.getAttribute("aria-controls")
  const list = listId ? element.ownerDocument.getElementById(listId) : null
  if (list?.querySelector('[role="option"]:not([aria-disabled="true"])')) {
    element.dispatchEvent(new KeyboardEvent("keydown", { key: "ArrowDown", bubbles: true, cancelable: true }))
  }
}

/** Bounds results, normalizes local matching, and keeps pending server choices unselectable. */
function Combobox<Value, Multiple extends boolean | undefined = false>(
  { onInputValueChange, onValueChange, onOpenChange, itemsPending = false, items, filter, autoHighlight,
    itemToStringLabel, ...props }: ComboboxPrimitive.Root.Props<Value, Multiple> & {
    itemsPending?: boolean
  }
) {
  const pendingInput = React.useRef(false)
  const [queryEdited, setQueryEdited] = React.useState(false)
  const composing = React.useRef(false)
  const input = React.useRef<HTMLInputElement | null>(null)
  const registerInput = React.useCallback((element: HTMLInputElement | null) => {
    input.current = element
  }, [])
  const wasPending = React.useRef(itemsPending)
  const highlight = autoHighlight ?? (filter !== null)
  const [uncontrolledValue, setUncontrolledValue] = React.useState<ComboboxPrimitive.Root.Props<Value, Multiple>["value"]>(props.defaultValue)
  const selectedValue = props.value === undefined ? uncontrolledValue : props.value
  const { contains } = ComboboxPrimitive.useFilter({ value: selectedValue, multiple: props.multiple })
  const { contains: containsQuery } = ComboboxPrimitive.useFilter({ multiple: true })
  const normalizedFilter = (value: Value, query: string) => (queryEdited ? containsQuery : contains)(
    value, query.normalize("NFKC"), (item) => pickerLabel(item, itemToStringLabel).normalize("NFKC")
  )

  React.useLayoutEffect(() => {
    const settled = wasPending.current && !itemsPending
    wasPending.current = itemsPending
    const element = input.current
    if (!settled || !highlight || !pendingInput.current || composing.current || !element
      || element.ownerDocument.activeElement !== element
      || element.getAttribute("aria-expanded") !== "true"
      || element.getAttribute("aria-activedescendant")) return
    highlightSettledResult(element)
  }, [items, itemsPending, highlight])

  return (
    <PickerInputContext.Provider value={{ pendingInput, composing, registerInput, itemsPending }}>
      <ComboboxPrimitive.Root
        autoHighlight={highlight}
        limit={100}
        {...props}
        items={itemsPending ? [] : items}
        filter={filter === undefined ? normalizedFilter : filter}
        itemToStringLabel={itemToStringLabel}
        onOpenChange={(open, details) => {
          onOpenChange?.(open, details)
          if (!details.isCanceled && !open && details.reason === "escape-key" && selectedValue != null
            && (!Array.isArray(selectedValue) || selectedValue.length > 0)) {
            pendingInput.current = false
            setQueryEdited(false)
          }
        }}
        onInputValueChange={(value, details) => {
          onInputValueChange?.(value, details)
          if (details.isCanceled) return
          if (details.reason === "input-change") pendingInput.current = value.trim() !== ""
          else pendingInput.current = false
          setQueryEdited(pendingInput.current)
        }}
        onValueChange={(value, details) => {
          if (itemsPending && details.reason === "item-press") {
            details.cancel()
            return
          }
          onValueChange?.(value, details)
          if (!details.isCanceled) {
            pendingInput.current = false
            setQueryEdited(false)
            if (props.value === undefined) setUncontrolledValue(() => value)
          }
        }}
      />
    </PickerInputContext.Provider>
  )
}

function usePickerInput({ onKeyDown, onCompositionStart, onCompositionEnd, ...props }: ComboboxPrimitive.Input.Props) {
  const localComposing = React.useRef(false)
  const picker = React.useContext(PickerInputContext)
  const composing = picker?.composing ?? localComposing
  const inputProps = {
    ...props,
    onCompositionStart(event) {
      composing.current = true
      onCompositionStart?.(event)
    },
    onCompositionEnd(event) {
      composing.current = false
      onCompositionEnd?.(event)
    },
    onKeyDown(event) {
      if (event.key === "Enter") {
        const isComposing = composing.current || event.nativeEvent.isComposing || event.nativeEvent.keyCode === 229
        const expanded = event.currentTarget.getAttribute("aria-expanded") === "true"
        if (isComposing || (picker?.itemsPending && (expanded || picker.pendingInput.current))) {
          event.preventDefault()
          event.stopPropagation()
          event.preventBaseUIHandler()
          return
        }
        if (expanded || picker?.pendingInput.current) {
          event.preventDefault()
          if (!event.currentTarget.getAttribute("aria-activedescendant")) event.preventBaseUIHandler()
        }
      }
      onKeyDown?.(event)
    },
  } satisfies ComboboxPrimitive.Input.Props
  return { inputProps, registerInput: picker?.registerInput }
}

function ComboboxValue({ ...props }: ComboboxPrimitive.Value.Props) {
  return <ComboboxPrimitive.Value data-slot="combobox-value" {...props} />
}

function ComboboxTrigger({
  className,
  children,
  ...props
}: ComboboxPrimitive.Trigger.Props) {
  return (
    <ComboboxPrimitive.Trigger
      data-slot="combobox-trigger"
      className={cn("[&_svg:not([class*='size-'])]:size-4", className)}
      {...props}
    >
      {children}
      <ChevronDownIcon className="pointer-events-none size-4 text-muted-foreground" />
    </ComboboxPrimitive.Trigger>
  )
}

function ComboboxClear({ className, ...props }: ComboboxPrimitive.Clear.Props) {
  return (
    <ComboboxPrimitive.Clear
      data-slot="combobox-clear"
      render={<InputGroupButton variant="ghost" size="icon-xs" />}
      className={cn(className)}
      {...props}
    >
      <XIcon className="pointer-events-none" />
    </ComboboxPrimitive.Clear>
  )
}

function ComboboxInput({
  className,
  children,
  disabled = false,
  showTrigger = true,
  showClear = false,
  ...props
}: ComboboxPrimitive.Input.Props & {
  showTrigger?: boolean
  showClear?: boolean
}) {
  const { inputProps, registerInput } = usePickerInput(props)
  return (
    <InputGroup className={cn("w-auto", className)}>
      <ComboboxPrimitive.Input
        render={<InputGroupInput ref={registerInput} disabled={disabled} />}
        {...inputProps}
      />
      <InputGroupAddon align="inline-end">
        {showTrigger && (
          <InputGroupButton
            size="icon-xs"
            variant="ghost"
            asChild
            data-slot="input-group-button"
            className="group-has-data-[slot=combobox-clear]/input-group:hidden data-pressed:bg-transparent"
            disabled={disabled}
          >
            <ComboboxTrigger />
          </InputGroupButton>
        )}
        {showClear && <ComboboxClear disabled={disabled} />}
      </InputGroupAddon>
      {children}
    </InputGroup>
  )
}

function ComboboxContent({
  className,
  side = "bottom",
  sideOffset = 6,
  align = "start",
  alignOffset = 0,
  anchor,
  ...props
}: ComboboxPrimitive.Popup.Props &
  Pick<
    ComboboxPrimitive.Positioner.Props,
    "side" | "align" | "sideOffset" | "alignOffset" | "anchor"
  >) {
  return (
    <ComboboxPrimitive.Portal>
      <ComboboxPrimitive.Positioner
        side={side}
        sideOffset={sideOffset}
        align={align}
        alignOffset={alignOffset}
        anchor={anchor}
        className="isolate z-50"
      >
        <ComboboxPrimitive.Popup
          data-slot="combobox-content"
          data-chips={!!anchor}
          className={cn("group/combobox-content relative max-h-(--available-height) w-(--anchor-width) max-w-(--available-width) min-w-[calc(var(--anchor-width)+--spacing(7))] origin-(--transform-origin) overflow-hidden rounded-md bg-popover text-popover-foreground shadow-md ring-1 ring-foreground/10 duration-(--motion-micro) data-open:ease-hand data-[chips=true]:min-w-(--anchor-width) data-[side=bottom]:slide-in-from-top-2 data-[side=inline-end]:slide-in-from-left-2 data-[side=inline-start]:slide-in-from-right-2 data-[side=left]:slide-in-from-right-2 data-[side=right]:slide-in-from-left-2 data-[side=top]:slide-in-from-bottom-2 *:data-[slot=input-group]:m-1 *:data-[slot=input-group]:mb-0 *:data-[slot=input-group]:h-8 *:data-[slot=input-group]:border-input/30 *:data-[slot=input-group]:bg-input/30 *:data-[slot=input-group]:shadow-none data-open:animate-in data-open:fade-in-0 data-open:zoom-in-95 data-closed:animate-out data-closed:fade-out-0 data-closed:zoom-out-95 motion-reduce:animate-none!", className )}
          {...props}
        />
      </ComboboxPrimitive.Positioner>
    </ComboboxPrimitive.Portal>
  )
}

/** Requires the collection renderer so static children cannot bypass Base UI's filtered items. */
function ComboboxList<Value>({ className, ...props }: Omit<ComboboxPrimitive.List.Props, "children"> & {
  children: (item: Value, index: number) => React.ReactNode
}) {
  const picker = React.useContext(PickerInputContext)
  return (
    <ComboboxPrimitive.List
      data-slot="combobox-list"
      aria-busy={picker?.itemsPending || undefined}
      className={cn(
        "no-scrollbar max-h-[min(calc(--spacing(72)---spacing(9)),calc(var(--available-height)---spacing(9)))] scroll-py-1 overflow-y-auto overscroll-contain p-1 data-empty:p-0",
        className
      )}
      {...props}
    />
  )
}

function ComboboxItem({
  className,
  children,
  ...props
}: ComboboxPrimitive.Item.Props) {
  return (
    <ComboboxPrimitive.Item
      data-slot="combobox-item"
      className={cn(
        "relative flex w-full cursor-default items-center gap-2 rounded-sm py-1.5 pr-8 pl-2 text-sm outline-hidden select-none data-highlighted:bg-accent data-highlighted:text-accent-foreground not-data-[variant=destructive]:data-highlighted:**:text-accent-foreground data-disabled:pointer-events-none data-disabled:opacity-50 [&_svg]:pointer-events-none [&_svg]:shrink-0 [&_svg:not([class*='size-'])]:size-4",
        className
      )}
      {...props}
    >
      {children}
      <ComboboxPrimitive.ItemIndicator
        render={
          <span className="pointer-events-none absolute right-2 flex size-4 items-center justify-center" />
        }
      >
        <CheckIcon className="pointer-events-none" />
      </ComboboxPrimitive.ItemIndicator>
    </ComboboxPrimitive.Item>
  )
}

function ComboboxGroup({ className, ...props }: ComboboxPrimitive.Group.Props) {
  return (
    <ComboboxPrimitive.Group
      data-slot="combobox-group"
      className={cn(className)}
      {...props}
    />
  )
}

function ComboboxLabel({
  className,
  ...props
}: ComboboxPrimitive.GroupLabel.Props) {
  return (
    <ComboboxPrimitive.GroupLabel
      data-slot="combobox-label"
      className={cn("px-2 py-1.5 text-xs text-muted-foreground", className)}
      {...props}
    />
  )
}

function ComboboxCollection({ ...props }: ComboboxPrimitive.Collection.Props) {
  return (
    <ComboboxPrimitive.Collection data-slot="combobox-collection" {...props} />
  )
}

function ComboboxPendingLabel() {
  const t = useTranslations("UI")
  return t("searching")
}

function ComboboxEmpty({ className, children, ...props }: ComboboxPrimitive.Empty.Props) {
  const picker = React.useContext(PickerInputContext)
  return (
    <ComboboxPrimitive.Empty
      data-slot="combobox-empty"
      className={cn(
        "hidden w-full justify-center py-2 text-center text-sm text-muted-foreground group-data-empty/combobox-content:flex",
        className
      )}
      {...props}
    >
      {picker?.itemsPending ? <ComboboxPendingLabel /> : children}
    </ComboboxPrimitive.Empty>
  )
}

function ComboboxSeparator({
  className,
  ...props
}: ComboboxPrimitive.Separator.Props) {
  return (
    <ComboboxPrimitive.Separator
      data-slot="combobox-separator"
      className={cn("-mx-1 my-1 h-px bg-border", className)}
      {...props}
    />
  )
}

function ComboboxChips({
  className,
  ...props
}: React.ComponentPropsWithRef<typeof ComboboxPrimitive.Chips> &
  ComboboxPrimitive.Chips.Props) {
  return (
    <ComboboxPrimitive.Chips
      data-slot="combobox-chips"
      className={cn(
        "flex min-h-9 flex-wrap items-center gap-1.5 rounded-md border border-input bg-transparent bg-clip-padding px-2.5 py-1.5 text-sm shadow-xs transition-[color,box-shadow] focus-within:border-ring focus-within:ring-3 focus-within:ring-ring/50 has-aria-invalid:border-destructive has-aria-invalid:ring-3 has-aria-invalid:ring-destructive/20 has-data-[slot=combobox-chip]:px-1.5 dark:bg-input/30 dark:has-aria-invalid:border-destructive/50 dark:has-aria-invalid:ring-destructive/40",
        className
      )}
      {...props}
    />
  )
}

function ComboboxChip({
  className,
  children,
  showRemove = true,
  removeLabel,
  ...props
}: ComboboxPrimitive.Chip.Props & {
  showRemove?: boolean
  removeLabel?: string
}) {
  return (
    <ComboboxPrimitive.Chip
      data-slot="combobox-chip"
      className={cn(
        "flex h-[calc(--spacing(5.5))] w-fit items-center justify-center gap-1 rounded-sm bg-muted px-1.5 text-xs font-medium whitespace-nowrap text-foreground has-disabled:pointer-events-none has-disabled:cursor-not-allowed has-disabled:opacity-50 has-data-[slot=combobox-chip-remove]:pr-0",
        className
      )}
      {...props}
    >
      {children}
      {showRemove && (
        <ComboboxPrimitive.ChipRemove
          render={<Button variant="ghost" size="icon-xs" />}
          aria-label={removeLabel}
          className="-ml-1 opacity-50 hover:opacity-100"
          data-slot="combobox-chip-remove"
        >
          <XIcon className="pointer-events-none" />
        </ComboboxPrimitive.ChipRemove>
      )}
    </ComboboxPrimitive.Chip>
  )
}

function ComboboxChipsInput({
  className,
  ...props
}: ComboboxPrimitive.Input.Props) {
  const { inputProps, registerInput } = usePickerInput(props)
  return (
    <ComboboxPrimitive.Input
      data-slot="combobox-chip-input"
      render={<input ref={registerInput} />}
      className={cn("min-w-16 flex-1 outline-none", className)}
      {...inputProps}
    />
  )
}

function useComboboxAnchor() {
  return React.useRef<HTMLDivElement | null>(null)
}

export {
  Combobox,
  ComboboxInput,
  ComboboxContent,
  ComboboxList,
  ComboboxItem,
  ComboboxGroup,
  ComboboxLabel,
  ComboboxCollection,
  ComboboxEmpty,
  ComboboxSeparator,
  ComboboxChips,
  ComboboxChip,
  ComboboxChipsInput,
  ComboboxTrigger,
  ComboboxValue,
  useComboboxAnchor,
}
