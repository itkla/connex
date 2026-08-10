export type OverlayLifecycleCapabilities = {
  reportsMount: boolean
  reportsCloseCompletion: boolean
}

export type RetainedOverlay<T> = {
  generation: number
  value: T
  open: boolean
  mounted: boolean
  capabilities: OverlayLifecycleCapabilities
}

export type OverlayRetentionEvent<T> =
  | {
      type: "opened"
      generation: number
      value: T
      capabilities: OverlayLifecycleCapabilities
    }
  | { type: "mounted"; generation: number }
  | { type: "cancelled"; generation: number }
  | { type: "close-completed"; generation: number }
  | { type: "load-failed"; generation: number }

/** Retains mounted overlays for their exit while releasing requests cancelled before they mount. */
export function reduceOverlayRetention<T>(
  state: RetainedOverlay<T> | null,
  event: OverlayRetentionEvent<T>
): RetainedOverlay<T> | null {
  switch (event.type) {
    case "opened":
      return {
        generation: event.generation,
        value: event.value,
        open: true,
        mounted: false,
        capabilities: event.capabilities,
      }
    case "mounted":
      return state?.generation === event.generation
        ? { ...state, mounted: true }
        : state
    case "cancelled":
      if (state?.generation !== event.generation) return state
      if (!state.capabilities.reportsCloseCompletion) return null
      if (state.capabilities.reportsMount && !state.mounted) return null
      return { ...state, open: false }
    case "close-completed":
      return state?.generation === event.generation ? null : state
    case "load-failed":
      return state?.generation === event.generation ? null : state
  }
}

export type CloseCompletionGate = {
  observe: (open: boolean) => void
  consume: () => boolean
}

/** Produces one completion for each observed open-to-closed transition and ignores teardown alone. */
export function createCloseCompletionGate(initialOpen: boolean): CloseCompletionGate {
  let wasOpen = initialOpen
  let pending = false

  return {
    observe: (open) => {
      if (open) {
        wasOpen = true
        pending = false
        return
      }
      if (wasOpen) pending = true
      wasOpen = false
    },
    consume: () => {
      if (!pending) return false
      pending = false
      return true
    },
  }
}
