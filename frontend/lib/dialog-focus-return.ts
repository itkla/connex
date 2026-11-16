"use client"

import { useCallback, useLayoutEffect, useRef } from "react"

type AutoFocusHandler = (event: Event) => void

/** Captures before sibling inputs run React's native autofocus during their layout commit. */
export function DialogFocusCapture({ capture }: { capture: () => void }) {
  useLayoutEffect(capture, [capture])
  return null
}

function canRestoreFocus(element: Element | null): element is HTMLElement {
  if (!(element instanceof HTMLElement) || !element.isConnected ||
      element === document.body || element.matches(":disabled") ||
      element.closest("[hidden], [inert]")) return false

  for (let ancestor: HTMLElement | null = element; ancestor; ancestor = ancestor.parentElement) {
    const style = window.getComputedStyle(ancestor)
    if (style.display === "none" || style.visibility === "hidden") return false
  }
  return true
}

/** Restores state-opened overlays to their opener, preserving explicit autofocus overrides. */
export function useDialogFocusReturn(
  onOpenAutoFocus?: AutoFocusHandler,
  onCloseAutoFocus?: AutoFocusHandler,
) {
  const opener = useRef<Element | null>(null)
  const captured = useRef(false)
  const captureOpener = useCallback(() => {
    if (captured.current) return
    opener.current = document.activeElement
    captured.current = true
  }, [])

  return {
    captureOpener,
    onOpenAutoFocus(event: Event) {
      if (!captured.current) captureOpener()
      onOpenAutoFocus?.(event)
    },
    onCloseAutoFocus(event: Event) {
      const target = opener.current
      captured.current = false
      onCloseAutoFocus?.(event)
      if (event.defaultPrevented) return
      event.preventDefault()
      if (canRestoreFocus(target)) {
        target.focus({ preventScroll: true })
        if (document.activeElement === target) return
      }
      document.getElementById("app-main")?.focus({ preventScroll: true })
    },
  }
}
