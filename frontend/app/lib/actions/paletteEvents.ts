const PALETTE_OPENED_EVENT = "connex:palette-opened";

/** Notifies workspace action contributors when the palette needs current availability. */
export function publishPaletteOpened(): void {
    window.dispatchEvent(new Event(PALETTE_OPENED_EVENT));
}

/** Subscribes to actual palette opens, including keyboard and shell launcher entry points. */
export function subscribeToPaletteOpened(handler: () => void): () => void {
    window.addEventListener(PALETTE_OPENED_EVENT, handler);
    return () => window.removeEventListener(PALETTE_OPENED_EVENT, handler);
}
