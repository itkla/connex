"use client"

import * as React from "react"
import { ScrollArea } from "@base-ui/react/scroll-area"

import { cn } from "@/lib/utils"

type MessageId = string | number
type DefaultScrollPosition = "start" | "end" | "last-anchor"

type MessageScrollerVisibility = {
  currentAnchorId: MessageId | null
  visibleMessageIds: ReadonlySet<MessageId>
}

type MessageScrollerCommands = {
  scrollToStart: () => void
  scrollToEnd: () => void
  scrollToMessage: (messageId: MessageId) => boolean
}

type MessageItem = {
  id: MessageId
  node: HTMLDivElement
  scrollAnchor: boolean
}

type MessageScrollerContextValue = MessageScrollerCommands & {
  contentChanged: () => void
  registerButton: (node: HTMLButtonElement | null) => void
  registerContent: (node: HTMLDivElement | null) => void
  registerItem: (key: string, item: MessageItem | null) => void
  registerRoot: (node: HTMLDivElement | null) => void
  registerViewport: (node: HTMLDivElement | null) => void
  subscribeVisibility: (listener: () => void) => () => void
  getVisibility: () => MessageScrollerVisibility
  viewportScrolled: () => void
}

const EMPTY_VISIBILITY: MessageScrollerVisibility = {
  currentAnchorId: null,
  visibleMessageIds: new Set(),
}

const MessageScrollerContext = React.createContext<MessageScrollerContextValue | null>(null)

function assignRef<T>(ref: React.Ref<T> | undefined, value: T | null) {
  if (typeof ref === "function") {
    ref(value)
  } else if (ref) {
    ref.current = value
  }
}

function useMessageScrollerContext() {
  const context = React.useContext(MessageScrollerContext)
  if (!context) {
    throw new Error("Message scroller parts must be used within a <MessageScrollerProvider>.")
  }
  return context
}

function MessageScrollerProvider({
  autoScroll = false,
  defaultScrollPosition = "end",
  scrollPreviousItemPeek = 0,
  children,
}: {
  autoScroll?: boolean
  defaultScrollPosition?: DefaultScrollPosition
  scrollPreviousItemPeek?: number
  children: React.ReactNode
}) {
  const rootRef = React.useRef<HTMLDivElement | null>(null)
  const viewportRef = React.useRef<HTMLDivElement | null>(null)
  const contentRef = React.useRef<HTMLDivElement | null>(null)
  const buttonRef = React.useRef<HTMLButtonElement | null>(null)
  const itemsRef = React.useRef(new Map<string, MessageItem>())
  const initializedRef = React.useRef(false)
  const lastAnchorKeyRef = React.useRef<string | null>(null)
  const activeAnchorKeyRef = React.useRef<string | null>(null)
  const followOutputRef = React.useRef(true)
  const visibilityRef = React.useRef<MessageScrollerVisibility>(EMPTY_VISIBILITY)
  const visibilityListenersRef = React.useRef(new Set<() => void>())

  const orderedItems = React.useCallback(() => (
    [...itemsRef.current.entries()]
      .filter(([, item]) => item.node.isConnected)
      .sort(([, left], [, right]) => left.node.offsetTop - right.node.offsetTop)
  ), [])

  const updateVisibility = React.useCallback(() => {
    if (visibilityListenersRef.current.size === 0) return
    const viewport = viewportRef.current
    if (!viewport) return
    const viewportRect = viewport.getBoundingClientRect()
    const visible = orderedItems().filter(([, item]) => {
      const rect = item.node.getBoundingClientRect()
      return rect.bottom > viewportRect.top && rect.top < viewportRect.bottom
    })
    const anchor = visible.find(([, item]) => item.scrollAnchor)
    const next: MessageScrollerVisibility = {
      currentAnchorId: anchor?.[1].id ?? null,
      visibleMessageIds: new Set(visible.map(([, item]) => item.id)),
    }
    const current = visibilityRef.current
    const sameIds = current.visibleMessageIds.size === next.visibleMessageIds.size
      && [...current.visibleMessageIds].every((id) => next.visibleMessageIds.has(id))
    if (current.currentAnchorId === next.currentAnchorId && sameIds) return
    visibilityRef.current = next
    visibilityListenersRef.current.forEach((listener) => listener())
  }, [orderedItems])

  const updateScrollAffordances = React.useCallback(() => {
    const viewport = viewportRef.current
    const button = buttonRef.current
    const root = rootRef.current
    if (!viewport) return
    const canScrollToEnd = viewport.scrollHeight - viewport.clientHeight - viewport.scrollTop > 2
    if (root) {
      root.dataset.scrollable = canScrollToEnd ? "end" : ""
    }
    if (button) {
      button.dataset.active = String(canScrollToEnd)
      button.inert = !canScrollToEnd
      button.tabIndex = canScrollToEnd ? 0 : -1
    }
    updateVisibility()
  }, [updateVisibility])

  const updateScrollState = React.useCallback(() => {
    const viewport = viewportRef.current
    if (!viewport) return
    followOutputRef.current = viewport.scrollHeight - viewport.clientHeight - viewport.scrollTop <= 2
    updateScrollAffordances()
  }, [updateScrollAffordances])

  const clearAnchorSpace = React.useCallback(() => {
    activeAnchorKeyRef.current = null
    const content = contentRef.current
    if (content) content.style.paddingBottom = ""
  }, [])

  const updateAnchorSpace = React.useCallback((item: MessageItem) => {
    const content = contentRef.current
    const viewport = viewportRef.current
    if (!content || !viewport) return
    const currentSpace = Number.parseFloat(content.style.paddingBottom) || 0
    const contentHeight = content.scrollHeight - currentSpace
    const contentBelowAnchor = contentHeight - item.node.offsetTop
    const nextSpace = Math.max(0, viewport.clientHeight - scrollPreviousItemPeek - contentBelowAnchor)
    if (Math.abs(currentSpace - nextSpace) < 1) return
    content.style.paddingBottom = nextSpace === 0 ? "" : `${nextSpace}px`
  }, [scrollPreviousItemPeek])

  const scrollToStart = React.useCallback(() => {
    const viewport = viewportRef.current
    if (!viewport) return
    clearAnchorSpace()
    viewport.scrollTo({ top: 0 })
    followOutputRef.current = false
    updateScrollAffordances()
  }, [clearAnchorSpace, updateScrollAffordances])

  const scrollToEnd = React.useCallback(() => {
    const viewport = viewportRef.current
    if (!viewport) return
    clearAnchorSpace()
    viewport.scrollTo({ top: viewport.scrollHeight })
    followOutputRef.current = true
    updateScrollAffordances()
  }, [clearAnchorSpace, updateScrollAffordances])

  const scrollToItem = React.useCallback((item: MessageItem, peek = 0) => {
    const viewport = viewportRef.current
    if (!viewport) return
    viewport.scrollTo({ top: Math.max(0, item.node.offsetTop - peek) })
    followOutputRef.current = false
    updateScrollAffordances()
  }, [updateScrollAffordances])

  const scrollToMessage = React.useCallback((messageId: MessageId) => {
    const item = [...itemsRef.current.values()].find((candidate) => candidate.id === messageId)
    if (!item) return false
    clearAnchorSpace()
    scrollToItem(item)
    return true
  }, [clearAnchorSpace, scrollToItem])

  const contentChanged = React.useCallback(() => {
    const items = orderedItems()
    const anchors = items.filter(([, item]) => item.scrollAnchor)
    const lastAnchor = anchors.at(-1) ?? null
    if (!initializedRef.current) {
      initializedRef.current = true
      lastAnchorKeyRef.current = lastAnchor?.[0] ?? null
      if (defaultScrollPosition === "start") {
        scrollToStart()
      } else if (defaultScrollPosition === "last-anchor" && lastAnchor) {
        activeAnchorKeyRef.current = lastAnchor[0]
        updateAnchorSpace(lastAnchor[1])
        scrollToItem(lastAnchor[1], scrollPreviousItemPeek)
      } else {
        scrollToEnd()
      }
      return
    }
    if (lastAnchor && lastAnchor[0] !== lastAnchorKeyRef.current) {
      lastAnchorKeyRef.current = lastAnchor[0]
      activeAnchorKeyRef.current = lastAnchor[0]
      updateAnchorSpace(lastAnchor[1])
      scrollToItem(lastAnchor[1], scrollPreviousItemPeek)
      return
    }
    const activeAnchor = items.find(([key]) => key === activeAnchorKeyRef.current)
    if (activeAnchor) updateAnchorSpace(activeAnchor[1])
    if (autoScroll && followOutputRef.current) {
      scrollToEnd()
      return
    }
    if (activeAnchor) {
      updateScrollAffordances()
    } else {
      updateScrollState()
    }
  }, [autoScroll, defaultScrollPosition, orderedItems, scrollPreviousItemPeek, scrollToEnd, scrollToItem, scrollToStart, updateAnchorSpace, updateScrollAffordances, updateScrollState])

  const registerRoot = React.useCallback((node: HTMLDivElement | null) => {
    rootRef.current = node
  }, [])

  const registerViewport = React.useCallback((node: HTMLDivElement | null) => {
    viewportRef.current = node
    if (node) updateScrollState()
  }, [updateScrollState])

  const registerContent = React.useCallback((node: HTMLDivElement | null) => {
    contentRef.current = node
  }, [])

  const registerButton = React.useCallback((node: HTMLButtonElement | null) => {
    buttonRef.current = node
    if (node) updateScrollState()
  }, [updateScrollState])

  const registerItem = React.useCallback((key: string, item: MessageItem | null) => {
    if (item) {
      itemsRef.current.set(key, item)
    } else {
      itemsRef.current.delete(key)
    }
  }, [])

  const subscribeVisibility = React.useCallback((listener: () => void) => {
    visibilityListenersRef.current.add(listener)
    updateVisibility()
    return () => visibilityListenersRef.current.delete(listener)
  }, [updateVisibility])

  const getVisibility = React.useCallback(() => visibilityRef.current, [])

  const value = React.useMemo<MessageScrollerContextValue>(() => ({
    contentChanged,
    getVisibility,
    registerButton,
    registerContent,
    registerItem,
    registerRoot,
    registerViewport,
    scrollToEnd,
    scrollToMessage,
    scrollToStart,
    subscribeVisibility,
    viewportScrolled: updateScrollState,
  }), [contentChanged, getVisibility, registerButton, registerContent, registerItem, registerRoot, registerViewport, scrollToEnd, scrollToMessage, scrollToStart, subscribeVisibility, updateScrollState])

  React.useEffect(() => {
    const content = contentRef.current
    if (!content) return
    const observer = new ResizeObserver(contentChanged)
    observer.observe(content)
    return () => observer.disconnect()
  }, [contentChanged])

  return (
    <MessageScrollerContext.Provider value={value}>
      {children}
    </MessageScrollerContext.Provider>
  )
}

function MessageScroller({
  className,
  children,
  ref,
  ...props
}: React.ComponentProps<typeof ScrollArea.Root>) {
  const context = useMessageScrollerContext()
  const mergedRef = React.useCallback((node: HTMLDivElement | null) => {
    context.registerRoot(node)
    assignRef(ref, node)
  }, [context, ref])

  return (
    <ScrollArea.Root
      ref={mergedRef}
      data-slot="message-scroller"
      className={cn("relative flex min-h-0 flex-col", className)}
      {...props}
    >
      {children}
      <ScrollArea.Scrollbar
        data-slot="message-scroller-scrollbar"
        className="absolute inset-y-0 right-0 z-20 flex w-2.5 touch-none p-px select-none"
      >
        <ScrollArea.Thumb
          data-slot="message-scroller-thumb"
          className="w-full rounded-full bg-border"
        />
      </ScrollArea.Scrollbar>
    </ScrollArea.Root>
  )
}

function MessageScrollerViewport({
  className,
  ref,
  onScroll,
  ...props
}: React.ComponentProps<typeof ScrollArea.Viewport>) {
  const context = useMessageScrollerContext()
  const mergedRef = React.useCallback((node: HTMLDivElement | null) => {
    context.registerViewport(node)
    assignRef(ref, node)
  }, [context, ref])

  return (
    <ScrollArea.Viewport
      ref={mergedRef}
      data-slot="message-scroller-viewport"
      role="region"
      tabIndex={0}
      className={cn("min-h-0 flex-1 overflow-x-hidden outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-ring/50", className)}
      onScroll={(event) => {
        context.viewportScrolled()
        onScroll?.(event)
      }}
      {...props}
    />
  )
}

function MessageScrollerContent({
  className,
  ref,
  children,
  ...props
}: React.ComponentProps<typeof ScrollArea.Content>) {
  const context = useMessageScrollerContext()
  const mergedRef = React.useCallback((node: HTMLDivElement | null) => {
    context.registerContent(node)
    assignRef(ref, node)
  }, [context, ref])

  React.useLayoutEffect(() => {
    context.contentChanged()
  }, [children, context])

  return (
    <ScrollArea.Content
      ref={mergedRef}
      data-slot="message-scroller-content"
      role="log"
      aria-relevant="additions"
      className={cn("min-w-0", className)}
      {...props}
    >
      {children}
    </ScrollArea.Content>
  )
}

function MessageScrollerItem({
  className,
  messageId,
  scrollAnchor = false,
  ref,
  ...props
}: React.ComponentProps<"div"> & {
  messageId: MessageId
  scrollAnchor?: boolean
}) {
  const context = useMessageScrollerContext()
  const key = String(messageId)
  const mergedRef = React.useCallback((node: HTMLDivElement | null) => {
    context.registerItem(key, node ? { id: messageId, node, scrollAnchor } : null)
    assignRef(ref, node)
  }, [context, key, messageId, ref, scrollAnchor])

  return (
    <div
      ref={mergedRef}
      data-slot="message-scroller-item"
      data-message-id={key}
      data-scroll-anchor={scrollAnchor ? "" : undefined}
      className={cn("[content-visibility:auto] [contain-intrinsic-size:auto_5rem]", className)}
      {...props}
    />
  )
}

function MessageScrollerButton({
  className,
  ref,
  onClick,
  ...props
}: React.ComponentProps<"button">) {
  const context = useMessageScrollerContext()
  const mergedRef = React.useCallback((node: HTMLButtonElement | null) => {
    context.registerButton(node)
    assignRef(ref, node)
  }, [context, ref])

  return (
    <button
      ref={mergedRef}
      type="button"
      data-slot="message-scroller-button"
      data-active="false"
      tabIndex={-1}
      className={cn("absolute right-4 bottom-4 z-10 inline-flex size-8 items-center justify-center rounded-full border border-border bg-background text-foreground opacity-0 shadow-sm transition-[opacity,background-color,color,transform] duration-(--motion-micro) ease-(--ease-out) data-[active=false]:pointer-events-none data-[active=true]:opacity-100 hover:bg-accent hover:text-accent-foreground active:scale-[0.97] motion-reduce:active:scale-100 motion-reduce:transition-none", className)}
      onClick={(event) => {
        context.scrollToEnd()
        onClick?.(event)
      }}
      {...props}
    />
  )
}

function useMessageScroller(): MessageScrollerCommands {
  const { scrollToEnd, scrollToMessage, scrollToStart } = useMessageScrollerContext()
  return { scrollToEnd, scrollToMessage, scrollToStart }
}

function useMessageScrollerVisibility(): MessageScrollerVisibility {
  const context = useMessageScrollerContext()
  return React.useSyncExternalStore(
    context.subscribeVisibility,
    context.getVisibility,
    () => EMPTY_VISIBILITY,
  )
}

export {
  MessageScroller,
  MessageScrollerButton,
  MessageScrollerContent,
  MessageScrollerItem,
  MessageScrollerProvider,
  MessageScrollerViewport,
  useMessageScroller,
  useMessageScrollerVisibility,
}
